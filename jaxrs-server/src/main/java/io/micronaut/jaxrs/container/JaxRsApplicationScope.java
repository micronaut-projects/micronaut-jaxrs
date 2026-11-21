/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.jaxrs.container;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.BeanRegistration;
import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.SupplierUtil;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.HttpRequest;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanIdentifier;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.jaxrs.common.JaxRsContainerMessageBodyHandlerRegistry;
import io.micronaut.jaxrs.common.JaxRsLocalProviders;
import io.micronaut.jaxrs.common.JaxRsRouteInterceptors;
import io.micronaut.jaxrs.common.JaxRsUtils;
import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import io.micronaut.core.convert.ConversionService;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The state of one {@link Application} of the context: its path, the providers it registers,
 * its features and {@code Configuration}, the readers, writers, filters and interceptors it
 * selects and the templates of its routes. The routes of its resources are declared under its
 * path, and a request is served with the scope of the route that matched, see
 * {@link JaxRsApplications#current(HttpRequest)}.
 *
 * <p>With one application, the scope uses the beans of the context, and the instances the
 * application returns from {@code getSingletons()} become the beans of their classes, see
 * {@link JaxRsApplicationSingletons}. With several, each scope keeps its own instances, which
 * replace the beans of their classes in its lookups only, see {@link #registrations}: the
 * {@code @Provider} beans apply to every application that registers them.</p>
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
final class JaxRsApplicationScope implements JaxRsLocalProviders {

    /**
     * The attribute of a request with the scope of the application that serves it, set by a
     * sub-resource locator for the routes of the target it locates.
     */
    static final String ATTRIBUTE = JaxRsApplicationScope.class.getName();

    private final ApplicationContext context;
    private final @Nullable Application application;
    private final boolean multiple;
    private final ApplicationProvider applicationProvider;
    private final String applicationPath;
    private final JaxRsContainerMessageBodyHandlerRegistry registry;
    private final JaxRsRouteTemplateEngine templates = new JaxRsRouteTemplateEngine();
    // the instances of the application in this scope only, by class, with several applications
    private final Map<Class<?>, BeanRegistration<?>> local = new ConcurrentHashMap<>();
    private final JaxRsFeatures features;
    private final JaxRsContextConfiguration configuration;
    private final ContainerNameBindingPredicate nameBindingPredicate;
    private final Supplier<JaxRsContainerFilters> containerFilters;
    private final Supplier<JaxRsMessageBodyReaders<?>> readers;
    private final Supplier<List<ParamConverterProvider>> paramConverterProviders;
    private final Supplier<Set<Class<?>>> registeredClasses;

    /**
     * @param context             The context
     * @param application         The application, {@code null} without one
     * @param multiple            Whether the context has several applications
     * @param applicationProvider Its path and annotations
     * @param registry            Its readers and writers, {@code null} to create them for this scope
     */
    JaxRsApplicationScope(ApplicationContext context, @Nullable Application application, boolean multiple,
                          ApplicationProvider applicationProvider, @Nullable JaxRsContainerMessageBodyHandlerRegistry registry) {
        this.context = context;
        this.application = application;
        this.multiple = multiple;
        this.applicationProvider = applicationProvider;
        this.applicationPath = applicationProvider.getAnnotationMetadata().stringValue(jakarta.ws.rs.ApplicationPath.class)
            .map(ApplicationProvider::decodePath)
            .orElse("");
        this.registry = registry != null ? registry
            : new JaxRsContainerMessageBodyHandlerRegistry(context, () -> application, this::configuration, this);
        if (multiple && application != null) {
            registerApplicationInstances(application);
        }
        this.features = new JaxRsFeatures(this);
        this.configuration = new JaxRsContextConfiguration(this);
        this.nameBindingPredicate = new ContainerNameBindingPredicate(applicationProvider.getAnnotationMetadata());
        this.containerFilters = SupplierUtil.memoized(() ->
            new JaxRsContainerFilters(applicationProvider, context, nameBindingPredicate, features, this.registry));
        this.readers = SupplierUtil.memoized(() -> new JaxRsMessageBodyReaders<>(this.registry,
            new ArrayList<>(context.getBeanRegistrations(ReaderInterceptor.class)), nameBindingPredicate, features,
            () -> context.getBean(MessageBodyHandlerRegistry.class), context.getBean(ConversionService.class)));
        // the registered ones (JAX-RS 2.3), resolved when first used: after the instances of the
        // application replaced the beans of their classes, see JaxRsApplicationSingletons
        this.paramConverterProviders = SupplierUtil.memoized(() -> registrations(ParamConverterProvider.class,
                context.getBeanRegistrations(ParamConverterProvider.class)).stream()
            .filter(registration -> this.registry.isRegistered(registration.getBeanDefinition().getBeanType(), ParamConverterProvider.class))
            // not the ones constrained to the client (@ConstrainedTo)
            .filter(registration -> JaxRsUtils.isServerProvider(registration.getBeanDefinition().getAnnotationMetadata()))
            // the one of the highest priority, the lowest value, first: the priority a feature
            // registered it with, else its @Priority
            .sorted(Comparator.comparingInt(registration -> this.registry.priority(registration, ParamConverterProvider.class)))
            .map(BeanRegistration::getBean)
            .toList());
        this.registeredClasses = SupplierUtil.memoized(() -> {
            Set<Class<?>> classes = new HashSet<>();
            if (application != null) {
                classes.addAll(application.getClasses());
                for (Object singleton : application.getSingletons()) {
                    classes.add(singleton.getClass());
                }
            }
            return classes;
        });
    }

    ApplicationContext context() {
        return context;
    }

    @Nullable Application application() {
        return application;
    }

    /**
     * @return Whether the context has several applications: the instances of each one are kept
     * in its scope, not registered as beans
     */
    boolean isMultiple() {
        return multiple;
    }

    ApplicationProvider applicationProvider() {
        return applicationProvider;
    }

    /**
     * @return The decoded {@code @ApplicationPath} of the application, an empty string without one
     */
    String applicationPath() {
        return applicationPath;
    }

    JaxRsContainerMessageBodyHandlerRegistry registry() {
        return registry;
    }

    JaxRsRouteTemplateEngine templates() {
        return templates;
    }

    JaxRsFeatures features() {
        return features;
    }

    JaxRsContextConfiguration configuration() {
        return configuration;
    }

    ContainerNameBindingPredicate nameBindingPredicate() {
        return nameBindingPredicate;
    }

    JaxRsContainerFilters containerFilters() {
        return containerFilters.get();
    }

    JaxRsMessageBodyReaders<?> readers() {
        return readers.get();
    }

    List<ParamConverterProvider> paramConverterProviders() {
        return paramConverterProviders.get();
    }

    /**
     * Whether a root resource class is registered with the application: an {@code Application}
     * that lists its classes or singletons registers only those (JAX-RS 2.3), and one that lists
     * none, every resource.
     *
     * @param resourceClass The class
     * @return Whether it is registered
     */
    boolean isRegistered(Class<?> resourceClass) {
        Set<Class<?>> registered = registeredClasses.get();
        return registered.isEmpty() || registered.contains(resourceClass);
    }

    /**
     * @param type A component class
     * @return The instance this scope keeps of it, {@code null} if it keeps none
     */
    @Nullable Object localInstance(Class<?> type) {
        BeanRegistration<?> registration = local.get(type);
        return registration == null ? null : registration.getBean();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<BeanRegistration<T>> registrations(Class<T> contract, Collection<BeanRegistration<T>> beans) {
        if (local.isEmpty()) {
            return beans instanceof List<BeanRegistration<T>> list ? list : new ArrayList<>(beans);
        }
        List<BeanRegistration<T>> registrations = new ArrayList<>(beans.size() + local.size());
        for (BeanRegistration<T> bean : beans) {
            // the instance of the application replaces the bean of its class in this scope
            if (!local.containsKey(bean.getBeanDefinition().getBeanType())) {
                registrations.add(bean);
            }
        }
        for (BeanRegistration<?> registration : local.values()) {
            if (contract.isInstance(registration.getBean())) {
                registrations.add((BeanRegistration<T>) registration);
            }
        }
        return registrations;
    }

    /**
     * The registrations this scope keeps, of a contract: with several applications, the instances
     * of the application and of its features.
     *
     * @param contract The contract
     * @param <T>      The type of the contract
     * @return The registrations
     */
    @SuppressWarnings("unchecked")
    <T> List<BeanRegistration<T>> localRegistrations(Class<T> contract) {
        if (local.isEmpty()) {
            return List.of();
        }
        List<BeanRegistration<T>> registrations = new ArrayList<>();
        for (BeanRegistration<?> registration : local.values()) {
            if (contract.isInstance(registration.getBean())) {
                registrations.add((BeanRegistration<T>) registration);
            }
        }
        return registrations;
    }

    /**
     * @param type A class
     * @return Whether this scope keeps an instance of it, which replaces the bean of the class
     */
    boolean isLocal(Class<?> type) {
        return !local.isEmpty() && local.containsKey(type);
    }

    /**
     * Keep an instance of a component in this scope, with the annotations of its class and the
     * type arguments of the provider contracts it implements, which select it, e.g. the exception
     * type of an {@code ExceptionMapper}.
     *
     * @param instance The instance, injected
     * @param <T>      The type
     */
    @SuppressWarnings("unchecked")
    <T> void addLocal(T instance) {
        Class<T> type = (Class<T>) instance.getClass();
        BeanDefinition<T> generated = context.findBeanDefinition(type).orElse(null);
        RuntimeBeanDefinition.Builder<T> definition = RuntimeBeanDefinition.builder(type, () -> instance)
            .singleton(true)
            .annotationMetadata(MutableAnnotationMetadata.of(generated == null
                ? JaxRsRouteInterceptors.metadata(type) : generated.getAnnotationMetadata()));
        for (Class<?> contract : JaxRsFeatures.CONTRACTS) {
            if (!contract.isAssignableFrom(type)) {
                continue;
            }
            List<Argument<?>> arguments = generated == null ? List.of() : generated.getTypeArguments(contract);
            if (arguments.isEmpty()) {
                Argument<?> resolved = JaxRsReflection.get().resolveGeneric(type, contract);
                arguments = resolved == null ? List.of() : List.of(resolved.getTypeParameters());
            }
            if (!arguments.isEmpty()) {
                definition.typeArguments(contract, arguments.toArray(Argument[]::new));
            }
        }
        BeanDefinition<T> built = definition.build();
        local.put(type, new BeanRegistration<>(BeanIdentifier.of(type.getName() + "@" + System.identityHashCode(this)), built, instance));
        // the lookups made before did not see it
        registry.clearCache();
    }

    /**
     * Keep the provider instances of the application, and an instance of each provider class it
     * lists that is not a bean (JAX-RS 2.3, 4.1.1): one per class in each application.
     */
    private void registerApplicationInstances(Application application) {
        for (Object singleton : application.getSingletons()) {
            if (JaxRsApplicationSingletons.isProvider(singleton)) {
                addLocal(JaxRsApplicationSingletons.inject(context, singleton));
            }
        }
        for (Class<?> type : application.getClasses()) {
            if (JaxRsApplicationSingletons.isProviderClass(type) && !context.containsBean(type) && !local.containsKey(type)) {
                Object instance = JaxRsReflection.get().instantiate(type, t -> context.findBean(t).orElse(null));
                addLocal(JaxRsApplicationSingletons.inject(context, instance));
            }
        }
    }

    /**
     * @return The annotations of the application class
     */
    AnnotationMetadata applicationMetadata() {
        return applicationProvider.getAnnotationMetadata();
    }

    @Override
    public String toString() {
        return "JAX-RS application " + (application == null ? "(none)" : application.getClass().getName())
            + " at " + (applicationPath.isEmpty() ? "/" : applicationPath);
    }
}
