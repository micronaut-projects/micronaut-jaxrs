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

import io.micronaut.context.BeanContext;
import io.micronaut.context.processor.ExecutableMethodProcessor;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.beans.BeanMethod;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.Executable;
import io.micronaut.core.util.SupplierUtil;
import io.micronaut.http.AsyncServerHttpRequest;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.form.FormData;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.ProxyBeanDefinition;
import io.micronaut.inject.annotation.AnnotationMetadataHierarchy;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.reflection.ReflectionBeanIntrospection;
import io.micronaut.web.router.builder.HttpRouteBuilder;
import io.micronaut.web.router.builder.HttpRouteSpec;
import io.micronaut.web.router.builder.HttpRoutes;
import io.micronaut.web.router.builder.PathVariables;
import io.micronaut.web.router.builder.RouteDeclaration;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The routes of the JAX-RS resources, built at runtime like the routes of the controllers (see
 * {@code AnnotatedMethodRouteBuilder}): the executable resource methods and sub-resource locators,
 * which the annotation mappers mark with {@link JaxRsResourceMethod}, are processed on startup, and
 * the classes that have them are the root resources, whose methods are routed with handler
 * functions. The handlers read the
 * {@link Argument}s of a method with {@link JaxRsRouteSupport}, call it and convert its result.
 * The methods of a sub-resource are the executable methods of its introspection.
 *
 * <p>A class the annotation processor never saw, which an {@code Application} lists, is described
 * by a reflective introspection.</p>
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
@Singleton
final class JaxRsRuntimeRoutes implements HttpRoutes, ExecutableMethodProcessor<JaxRsResourceMethod> {

    private static final Logger LOG = LoggerFactory.getLogger(JaxRsRuntimeRoutes.class);

    private static final Set<String> NO_BODY_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private static final String NAMED = "jakarta.inject.Named";

    private static final String INTRODUCTION = "io.micronaut.aop.Introduction";

    /**
     * How many times a type can appear in a chain of sub-resource locators: a locator returning
     * its own type is followed to this depth, then located at runtime.
     */
    private static final int MAX_LOCATOR_REPEAT = 2;

    private static final Set<String> CONTEXT_TYPES = Set.of(
        "jakarta.ws.rs.core.HttpHeaders",
        "jakarta.ws.rs.core.UriInfo",
        "jakarta.ws.rs.core.SecurityContext",
        "jakarta.ws.rs.core.Request",
        "jakarta.ws.rs.core.Application",
        "jakarta.ws.rs.core.Configuration",
        "jakarta.ws.rs.ext.Providers",
        "jakarta.ws.rs.container.ResourceContext",
        "jakarta.ws.rs.container.ResourceInfo",
        "jakarta.ws.rs.core.Cookie",
        "io.micronaut.http.HttpRequest",
        "jakarta.servlet.ServletContext",
        "jakarta.servlet.ServletRequest",
        "jakarta.servlet.http.HttpServletRequest",
        "jakarta.servlet.ServletResponse",
        "jakarta.servlet.http.HttpServletResponse",
        "jakarta.servlet.ServletConfig"
    );

    private static final List<Class<? extends Annotation>> REQUEST_ANNOTATIONS = List.of(
        PathParam.class, QueryParam.class, MatrixParam.class, HeaderParam.class, CookieParam.class,
        FormParam.class, BeanParam.class, Context.class
    );

    private final BeanContext beanContext;
    private final JaxRsRouteSupport support;
    private final Map<Class<?>, Resource<?>> resources = new LinkedHashMap<>();
    // how to create the classes the locators return
    private final Map<Class<?>, Creator<?>> creators = new ConcurrentHashMap<>();

    JaxRsRuntimeRoutes(BeanContext beanContext, JaxRsRouteSupport support) {
        this.beanContext = beanContext;
        this.support = support;
    }

    @Override
    public <B> void process(BeanDefinition<B> beanDefinition, ExecutableMethod<B, ?> method) {
        if (beanDefinition.hasStereotype(Controller.class) || beanDefinition.hasStereotype(INTRODUCTION)) {
            // routed as a controller, e.g. compiled by another language, or an interface
            // implemented by introduction advice, e.g. a declarative HTTP client
            return;
        }
        resource(beanDefinition).methods().add(method);
    }

    /**
     * The resource of a bean definition, by the class of the resource, not the one of its AOP
     * proxy.
     */
    @SuppressWarnings("unchecked")
    private <B> Resource<B> resource(BeanDefinition<B> beanDefinition) {
        Class<?> type = beanDefinition instanceof ProxyBeanDefinition<?> proxy ? proxy.getTargetType() : beanDefinition.getBeanType();
        return (Resource<B>) resources.computeIfAbsent(type, t -> new Resource<>(beanDefinition, new ArrayList<>()));
    }

    @Override
    public void routes(HttpRouteBuilder routes) {
        rootResources().forEach((type, root) -> {
            if (support.isRegistered(type)) {
                route(routes, type, root);
            }
        });
    }

    private <B> void route(HttpRouteBuilder routes, Class<?> type, Root<B> root) {
        String rootPath = root.metadata().stringValue(Path.class).orElse("");
        // the root resource class is selected first by the specificity of its @Path: the end
        // of it is marked in the templates, for the JAX-RS route template engine
        String prefix = strip(rootPath).isEmpty() ? rootPath : strip(rootPath) + JaxRsRouteTemplateEngine.ROOT_MARK;
        RequestType<B> requestType = requestType(root.type(), root.definition());
        int rootSegments = segments(rootPath);
        Supplier<B> instances = root.instances();
        Instances<B> instance = requestType == null
            ? (request, pathVariables, form) -> support.matched(request, instances.get(), rootSegments)
            : (request, pathVariables, form) -> support.matched(request, create(requestType, request, pathVariables, form), rootSegments);
        Map<Class<?>, Integer> visited = new LinkedHashMap<>();
        visited.put(type, 1);
        declare(routes, type, root.methods(), type, prefix, false, instance, requestType != null && requestType.usesForm(), visited, false);
    }

    /**
     * Declare the routes of the class of a target a locator located at runtime, relative to the
     * prefix of the locator.
     *
     * @param type   The class of the target
     * @param routes The builder
     */
    <T> void located(Class<T> type, HttpRouteBuilder routes) {
        Map<Class<?>, Integer> visited = new LinkedHashMap<>();
        visited.put(type, 1);
        declare(routes, type, methods(type), type, "", true, (request, pathVariables, form) -> pathVariables.locatedTarget(type), false,
            visited, false);
    }

    /**
     * The root resources: the processed bean definitions, and the classes and instances of the
     * {@code Application}.
     */
    private Map<Class<?>, Root<?>> rootResources() {
        Map<Class<?>, Root<?>> roots = new LinkedHashMap<>();
        resources.forEach((type, resource) -> roots.put(type, root(resource)));
        // the resources the annotation processor did not describe, e.g. the instances of an
        // application the Java SE bootstrap registers as beans: routed from their introspection
        for (BeanDefinition<?> definition : beanContext.getBeanDefinitions(Qualifiers.byStereotype(Path.class))) {
            if (!definition.hasStereotype(Controller.class) && !definition.hasStereotype(INTRODUCTION)
                && !roots.containsKey(definition.getBeanType())) {
                Root<?> root = beanRoot(definition);
                if (root != null) {
                    roots.put(definition.getBeanType(), root);
                }
            }
        }
        Application application = beanContext.findBean(Application.class).orElse(null);
        if (application != null) {
            for (Class<?> type : application.getClasses()) {
                if (!roots.containsKey(type)) {
                    Root<?> root = classRoot(type);
                    if (root != null) {
                        roots.put(type, root);
                    }
                }
            }
        }
        return roots;
    }

    /**
     * The instances of a bean, resolved by its type like it is injected, so that a definition
     * that replaces it provides them, e.g. the instances of an application the Java SE bootstrap
     * registers. A singleton is resolved once, when first used.
     */
    private <B> Supplier<B> instances(BeanDefinition<B> definition) {
        Class<B> type = definition.getBeanType();
        return definition.isSingleton()
            ? SupplierUtil.memoized(() -> beanContext.getBean(type))
            : () -> beanContext.getBean(type);
    }

    private <B> Root<B> root(Resource<B> resource) {
        BeanDefinition<B> definition = resource.definition();
        List<JaxRsMethod<B>> methods = new ArrayList<>();
        for (ExecutableMethod<B, ?> method : resource.methods()) {
            methods.add(new JaxRsMethod<>(method.getMethodName(), method.getArguments(), method.getReturnType().asArgument(),
                methodMetadata(method.getAnnotationMetadata()), method));
        }
        return new Root<>(definition.getBeanType(), definition, definition.getAnnotationMetadata(), methods, instances(definition));
    }

    private <B> @Nullable Root<B> beanRoot(BeanDefinition<B> definition) {
        Class<B> type = definition.getBeanType();
        BeanIntrospection<B> introspection = introspection(type);
        if (introspection == null) {
            return null;
        }
        List<JaxRsMethod<B>> methods = methods(introspection);
        return methods.isEmpty() ? null
            : new Root<>(type, definition, definition.getAnnotationMetadata(), methods, instances(definition));
    }

    /**
     * The root resource of a class of the application that is not a bean: a class the
     * annotation processors never saw.
     */
    private <T> @Nullable Root<T> classRoot(Class<T> type) {
        BeanIntrospection<T> introspection = introspection(type);
        if (introspection == null || !isRootResource(introspection)) {
            return null;
        }
        return new Root<>(type, null, introspection.getAnnotationMetadata(), methods(introspection),
            () -> beanContext.inject(introspection.instantiate()));
    }

    private static boolean isRootResource(BeanIntrospection<?> introspection) {
        if (introspection.hasAnnotation(Path.class)) {
            return true;
        }
        for (BeanMethod<?, ?> method : introspection.getBeanMethods()) {
            if (httpMethod(method.getAnnotationMetadata()) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * The introspection of a class: generated, or else reflective, for a class the annotation
     * processor never saw.
     */
    private static <T> @Nullable BeanIntrospection<T> introspection(Class<T> type) {
        BeanIntrospection<T> introspection = BeanIntrospector.SHARED.findIntrospection(type).orElse(null);
        if (introspection != null) {
            return introspection;
        }
        if (!ReflectionBeanIntrospection.isIntrospectable(type)) {
            return null;
        }
        return ReflectionBeanIntrospection.of(type, Set.of(Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD));
    }

    /**
     * The resource methods and sub-resource locators of a class: of its bean definition, else of
     * its introspection.
     */
    private <T> List<JaxRsMethod<T>> methods(Class<T> type) {
        BeanDefinition<T> definition = beanContext.findBeanDefinition(type).orElse(null);
        if (definition != null && !definition.hasStereotype(Controller.class)) {
            List<JaxRsMethod<T>> methods = executableMethods(definition);
            if (!methods.isEmpty()) {
                return methods;
            }
        }
        BeanIntrospection<T> introspection = introspection(type);
        return introspection == null ? List.of() : methods(introspection);
    }

    private static <T> List<JaxRsMethod<T>> executableMethods(BeanDefinition<T> definition) {
        List<JaxRsMethod<T>> methods = new ArrayList<>();
        for (ExecutableMethod<T, ?> method : definition.getExecutableMethods()) {
            AnnotationMetadata metadata = methodMetadata(method.getAnnotationMetadata());
            if (metadata.hasStereotype(JaxRsResourceMethod.class)) {
                methods.add(new JaxRsMethod<>(method.getMethodName(), method.getArguments(), method.getReturnType().asArgument(),
                    metadata, method));
            }
        }
        return methods;
    }

    private static <T> List<JaxRsMethod<T>> methods(BeanIntrospection<T> introspection) {
        List<JaxRsMethod<T>> methods = new ArrayList<>();
        for (BeanMethod<T, Object> method : introspection.getBeanMethods()) {
            AnnotationMetadata metadata = methodMetadata(method.getAnnotationMetadata());
            // marked by the annotation processor, else a public method of a class it never saw
            boolean routed = metadata.hasStereotype(JaxRsResourceMethod.class)
                || httpMethod(metadata) != null || metadata.hasDeclaredAnnotation(Path.class);
            if (routed) {
                methods.add(new JaxRsMethod<>(method.getName(), method.getArguments(), method.getReturnType().asArgument(),
                    metadata, method));
            }
        }
        return methods;
    }

    /**
     * The annotations of a method, without the ones of its class.
     */
    private static AnnotationMetadata methodMetadata(AnnotationMetadata metadata) {
        return metadata instanceof AnnotationMetadataHierarchy hierarchy ? hierarchy.getDeclaredMetadata() : metadata;
    }

    /**
     * Declare the routes of the resource methods and locators of a class.
     */
    private <B> void declare(HttpRouteBuilder routes, Class<?> type, List<JaxRsMethod<B>> methods, Class<?> rootClass, String path,
                             boolean located, Instances<B> instances, boolean rootUsesForm, Map<Class<?>, Integer> visited,
                             boolean chainUsesForm) {
        AnnotationMetadata classMetadata = classMetadata(type);
        for (JaxRsMethod<B> method : methods) {
            AnnotationMetadata metadata = method.metadata();
            String httpMethod = httpMethod(metadata);
            String methodPath = metadata.stringValue(Path.class).orElse(null);
            if (httpMethod != null) {
                String template = template(path, methodPath == null ? "" : methodPath);
                ResourceMethod<B> resourceMethod = resourceMethod(type, classMetadata, rootClass, method, httpMethod, template);
                if (resourceMethod != null) {
                    route(routes, resourceMethod, located, instances, rootUsesForm || chainUsesForm);
                }
            } else if (methodPath != null) {
                // a resource method is selected before a locator: the end of its @Path is marked
                String prefix = template(path, methodPath) + JaxRsRouteTemplateEngine.LOCATOR_MARK;
                locator(routes, rootClass, method, prefix, located, instances, rootUsesForm, visited, chainUsesForm);
            }
        }
    }

    private AnnotationMetadata classMetadata(Class<?> type) {
        Resource<?> resource = resources.get(type);
        if (resource != null) {
            return resource.definition().getAnnotationMetadata();
        }
        BeanIntrospection<?> introspection = introspection(type);
        return introspection == null ? AnnotationMetadata.EMPTY_METADATA : introspection.getAnnotationMetadata();
    }

    /**
     * Route a sub-resource locator. A locator whose target class is known is followed: the
     * routes of the class are declared under its prefix, and call the locator. A target known
     * only at runtime, or a locator that repeats, is located by the router, which routes the
     * rest of the path with the routes of the class of the target.
     */
    private <B> void locator(HttpRouteBuilder routes, Class<?> rootClass, JaxRsMethod<B> method, String prefix, boolean located,
                             Instances<B> instances, boolean rootUsesForm, Map<Class<?>, Integer> visited, boolean chainUsesForm) {
        Argument<?>[] arguments = arguments(method);
        Reader[] readers = new Reader[arguments.length];
        boolean usesForm = chainUsesForm;
        for (int i = 0; i < arguments.length; i++) {
            Argument<?> argument = arguments[i];
            if (!isRequestAnnotated(argument.getAnnotationMetadata())) {
                LOG.info("The JAX-RS sub-resource locator {} has a parameter that is not read from the request: it is not routed", method.name());
                return;
            }
            readers[i] = reader(argument, method.metadata());
            usesForm |= usesForm(argument);
        }
        Argument<?> returned = method.returnType();
        boolean classReturn = returned.getType() == Class.class;
        Class<?> target = returned.getType();
        if (classReturn) {
            Argument<?> typeArgument = returned.getFirstTypeVariable().orElse(null);
            target = typeArgument == null ? Object.class : typeArgument.getType();
        }
        int occurrences = visited.getOrDefault(target, 0);
        boolean known = !target.isPrimitive() && target != Object.class && !Response.class.isAssignableFrom(target)
            && occurrences < MAX_LOCATOR_REPEAT;
        if (known && follow(routes, rootClass, method, readers, prefix, located, instances, target, rootUsesForm, visited, usesForm)) {
            return;
        }
        // known only at runtime, or recursive: the paths cannot be enumerated
        if (classReturn) {
            LOG.info("The JAX-RS sub-resource locator {} returns a class known only at runtime: it is not routed", method.name());
            return;
        }
        if (usesForm && !chainUsesForm) {
            LOG.info("The JAX-RS sub-resource locator {} known only at runtime has a form parameter: it is not routed", method.name());
            return;
        }
        int segments = located ? -1 : segments(prefix);
        routes.locate(located ? support.locatedPrefix(prefix) : support.prefix(prefix), (request, pathVariables) -> {
            B instance = instances.get(request, pathVariables, null);
            return support.matched(request, locate(method, instance, readers, request, pathVariables, null), segments);
        }, support.locatedTables());
    }

    /**
     * Follow a locator to the class of its target: the routes of the class under the prefix of
     * the locator, which call the locator.
     *
     * @return Whether the class has routes
     */
    private <B, T> boolean follow(HttpRouteBuilder routes, Class<?> rootClass, JaxRsMethod<B> method, Reader[] readers, String prefix,
                                  boolean located, Instances<B> instances, Class<T> target, boolean rootUsesForm,
                                  Map<Class<?>, Integer> visited, boolean usesForm) {
        List<JaxRsMethod<T>> targetMethods = methods(target);
        if (targetMethods.isEmpty()) {
            return false;
        }
        int segments = segments(prefix);
        Instances<T> chained = (request, pathVariables, form) -> {
            B instance = instances.get(request, pathVariables, form);
            return support.matched(request, target.cast(locate(method, instance, readers, request, pathVariables, form)), segments);
        };
        int occurrences = visited.getOrDefault(target, 0);
        visited.put(target, occurrences + 1);
        try {
            declare(routes, target, targetMethods, rootClass, prefix, located, chained, rootUsesForm, visited, usesForm);
        } finally {
            visited.put(target, occurrences);
        }
        return true;
    }

    /**
     * Call a locator: its target, or an instance created for the request of the class it returns.
     */
    private <B> Object locate(JaxRsMethod<B> method, B instance, Reader[] readers, HttpRequest<?> request, PathVariables pathVariables,
                              @Nullable FormData form) {
        Object[] arguments = new Object[readers.length];
        for (int i = 0; i < readers.length; i++) {
            arguments[i] = readers[i].read(request, pathVariables, form, null);
        }
        // a locator returning null is a 404
        Object target = support.located(method.executable().invoke(instance, arguments));
        if (target instanceof Class<?> targetClass) {
            // the locator returned the class: an instance is created for the request
            return createLocated(targetClass, request, pathVariables, form);
        }
        return target;
    }

    @SuppressWarnings("unchecked")
    private <T> T createLocated(Class<T> type, HttpRequest<?> request, PathVariables pathVariables, @Nullable FormData form) {
        return ((Creator<T>) creators.computeIfAbsent(type, this::creator)).create(request, pathVariables, form);
    }

    /**
     * How to create an instance of a class a locator returned, for a request.
     */
    private <T> Creator<T> creator(Class<T> type) {
        RequestType<T> created = requestType(type, beanContext.findBeanDefinition(type).orElse(null));
        if (created != null) {
            return (request, pathVariables, form) -> create(created, request, pathVariables, form);
        }
        BeanIntrospection<T> introspection = introspection(type);
        if (introspection == null) {
            throw new IllegalStateException("Cannot create the sub-resource " + type.getName());
        }
        return (request, pathVariables, form) -> beanContext.inject(introspection.instantiate());
    }

    private <B> void route(HttpRouteBuilder routes, ResourceMethod<B> method, boolean located, Instances<B> instances,
                           boolean chainUsesForm) {
        String name = method.httpMethod();
        RouteDeclaration declaration = located
            ? support.locatedDeclaration(name, method.template())
            : support.declaration(name, method.template());
        boolean body = method.entity() >= 0 && !method.formEntity() && !"GET".equals(name);
        boolean usesForm = method.usesForm() || chainUsesForm;
        // a form is read for a method that can have one and reads no entity
        boolean form = method.form() || (usesForm && !NO_BODY_METHODS.contains(name) && method.entity() < 0);
        // a route that reads the entity and form parameters: the form is parsed from the entity
        boolean entityForm = body && !form && usesForm;
        HttpRouteSpec spec;
        if (method.async()) {
            if (form) {
                spec = routes.handleAsync(declaration, (request, pathVariables) -> support.formAsync(request, pathVariables,
                    (readRequest, readPathVariables, value) -> (CompletionStage<? extends HttpResponse<?>>) call(method, instances, readRequest,
                        readPathVariables, value, null)));
            } else if (body) {
                spec = routes.handleAsync(declaration, (request, pathVariables) -> support.entityAsync(request, pathVariables,
                    (readRequest, readPathVariables, value) -> (CompletionStage<? extends HttpResponse<?>>) call(method, instances, readRequest,
                        readPathVariables, entityForm ? support.entityForm(readRequest, value) : null, value)));
            } else {
                spec = routes.handleAsync(declaration, (AsyncServerHttpRequest<?> request, PathVariables pathVariables) ->
                    (CompletionStage<? extends HttpResponse<?>>) call(method, instances, request, pathVariables, null, null));
            }
        } else if (form) {
            spec = routes.handleForm(declaration, (request, pathVariables, value) ->
                (HttpResponse<?>) call(method, instances, request, pathVariables, value, null));
        } else if (body) {
            spec = routes.handle(declaration, JaxRsRouteSupport.ENTITY, (request, pathVariables, value) ->
                (HttpResponse<?>) call(method, instances, request, pathVariables,
                    entityForm ? support.entityForm(request, value) : null, value));
        } else {
            spec = routes.handle(declaration, (request, pathVariables) ->
                (HttpResponse<?>) call(method, instances, request, pathVariables, null, null));
        }
        support.configure(spec, method.metadata());
    }

    /**
     * Call a resource method: get the instance, read the parameters, call it and convert its
     * result to the response, or the stage of the response of an asynchronous method.
     */
    private <B> Object call(ResourceMethod<B> method, Instances<B> instances, HttpRequest<?> request, PathVariables pathVariables,
                            @Nullable FormData form, byte @Nullable [] body) throws Exception {
        if (method.produces()) {
            // a negotiated type that is not concrete is not acceptable
            support.acceptable(pathVariables);
        }
        B instance = instances.get(request, pathVariables, form);
        Reader[] readers = method.readers();
        Object[] arguments = new Object[readers.length];
        for (int i = 0; i < readers.length; i++) {
            arguments[i] = readers[i].read(request, pathVariables, form, body);
        }
        Object result = method.method().executable().invoke(instance, arguments);
        Converter converter = method.converter();
        if (method.async()) {
            CompletionStage<?> stage = (CompletionStage<?>) result;
            if (stage == null) {
                throw new IllegalStateException("The asynchronous resource method " + method.method().name() + " returned no stage");
            }
            return stage.thenApply(value -> converter.convert(request, value));
        }
        return method.completion().complete(request, pathVariables, converter.convert(request, result));
    }

    /**
     * How to convert the result of a method to its response, selected from its declared type.
     */
    private Converter converter(Argument<?> type, boolean async) {
        Class<?> raw = type.getType();
        if (raw == void.class || (raw == Void.class && !async)) {
            return (request, result) -> support.noContent();
        }
        if (raw == Object.class || type.isTypeVariable()) {
            // known only at runtime
            return (request, result) -> support.anyResponse(request, result, type);
        }
        if (Response.class.isAssignableFrom(raw)) {
            return (request, result) -> support.jaxRsResponse((Response) result);
        }
        if (HttpResponse.class.isAssignableFrom(raw)) {
            return (request, result) -> support.httpResponse((HttpResponse<?>) result);
        }
        if (GenericEntity.class.isAssignableFrom(raw)) {
            // the type of the generic entity selects the message body writer
            return (request, result) -> support.genericEntityResponse(request, (GenericEntity<?>) result);
        }
        if (!raw.isArray() && type.getTypeParameters().length > 0) {
            // the declared type, with its type arguments, selects the message body writer
            return (request, result) -> support.genericEntityResponse(request, result, type);
        }
        return (request, result) -> support.entityResponse(result);
    }

    /**
     * How to complete the response of a method that is not asynchronous.
     */
    private Completion completion(boolean produces, Argument<?> returnType) {
        if (produces) {
            // the entity has the negotiated type, also when a HEAD request drops it (JAX-RS 3.8)
            return (request, pathVariables, response) -> support.produced(pathVariables, response);
        }
        // the type of the response from the JAX-RS writers of its entity
        return (request, pathVariables, response) -> support.negotiate(request, response, returnType);
    }

    private <B> @Nullable ResourceMethod<B> resourceMethod(Class<?> owner, AnnotationMetadata classMetadata, Class<?> rootClass,
                                                           JaxRsMethod<B> method, String httpMethod, String template) {
        Argument<?>[] arguments = arguments(method);
        Reader[] readers = new Reader[arguments.length];
        boolean form = false;
        boolean usesForm = false;
        int entity = -1;
        for (int i = 0; i < arguments.length; i++) {
            Argument<?> argument = arguments[i];
            AnnotationMetadata metadata = argument.getAnnotationMetadata();
            if (metadata.hasAnnotation(Suspended.class)) {
                LOG.warn("JAX-RS asynchronous responses with @Suspended are not routed yet: {}", method.name());
                return null;
            }
            if (metadata.hasAnnotation(FormParam.class) && NO_BODY_METHODS.contains(httpMethod)) {
                // no form without a body: read from the query, like the controllers did
                String name = metadata.stringValue(FormParam.class).orElse(argument.getName());
                String defaultValue = metadata.stringValue(DefaultValue.class).orElse(null);
                boolean encoded = encoded(argument, method.metadata());
                readers[i] = (request, pathVariables, f, body) -> support.queryParam(request, name, argument, defaultValue, encoded);
            } else if (isRequestAnnotated(metadata)) {
                readers[i] = reader(argument, method.metadata());
                form |= metadata.hasAnnotation(FormParam.class);
                usesForm |= metadata.hasAnnotation(BeanParam.class) && usesForm(argument);
            } else if (CONTEXT_TYPES.contains(argument.getType().getName())) {
                String named = metadata.stringValue(NAMED).orElse(null);
                readers[i] = (request, pathVariables, f, body) -> support.context(request, argument, named);
            } else {
                if (entity >= 0) {
                    LOG.error("The JAX-RS resource method {} has more than one entity parameter: it is not routed", method.name());
                    return null;
                }
                entity = i;
            }
        }
        boolean formEntity = false;
        if (entity >= 0) {
            Argument<?> argument = arguments[entity];
            if (form) {
                Class<?> entityType = argument.getType();
                if (entityType != MultivaluedMap.class && entityType != Form.class) {
                    LOG.error("The entity of the JAX-RS resource method {} with @FormParam parameters must be a form: it is not routed", method.name());
                    return null;
                }
                formEntity = true;
                readers[entity] = (request, pathVariables, f, body) -> f == null ? null : support.formEntity(f, argument);
            } else {
                // the readers see the annotations of the parameter
                readers[entity] = (request, pathVariables, f, body) -> support.entity(request, body, argument);
            }
        }
        Argument<?> returnType = method.returnType();
        Class<?> returnClass = returnType.getType();
        boolean async = returnClass == CompletionStage.class
            || (CompletionStage.class.isAssignableFrom(returnClass) && returnClass.getName().startsWith("java.util.concurrent."));
        Argument<?> valueType = async ? returnType.getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT) : returnType;
        if (valueType.getType() == Void.class && async) {
            valueType = Argument.VOID;
        }
        List<String> produces = mediaTypes(method.metadata(), classMetadata, Produces.class);
        List<String> consumes = mediaTypes(method.metadata(), classMetadata, Consumes.class);
        JaxRsRouteSupport.RouteMetadata metadata = new JaxRsRouteSupport.RouteMetadata(owner, method.name(),
            Argument.toClassArray(method.arguments()), produces.toArray(String[]::new), consumes.toArray(String[]::new), rootClass);
        return new ResourceMethod<>(method, httpMethod, template, readers, form, formEntity, usesForm, entity, async,
            !produces.isEmpty(), converter(valueType, async), completion(!produces.isEmpty(), valueType), metadata);
    }

    /**
     * The parameters of a method, with the annotations JAX-RS gives them (section 3.6): a method
     * that declares a JAX-RS annotation itself inherits none from the method it overrides, on the
     * method or its parameters.
     */
    private static Argument<?>[] arguments(JaxRsMethod<?> method) {
        Argument<?>[] arguments = method.arguments();
        boolean declares = false;
        for (String name : method.metadata().getDeclaredMetadata().getAnnotationNames()) {
            if (name.startsWith("jakarta.ws.rs.")) {
                declares = true;
                break;
            }
        }
        if (!declares) {
            return arguments;
        }
        Argument<?>[] declared = new Argument<?>[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            Argument<?> argument = arguments[i];
            AnnotationMetadata own = argument.getAnnotationMetadata().getDeclaredMetadata();
            declared[i] = own == argument.getAnnotationMetadata() ? argument
                : Argument.of(argument.getType(), argument.getName(), own, argument.getTypeParameters());
        }
        return declared;
    }

    private Reader reader(Argument<?> argument, AnnotationMetadata methodMetadata) {
        JaxRsRouteSupport.ValueReader reader = support.reader(argument.getAnnotationMetadata(), argument, encoded(argument, methodMetadata));
        if (reader == null) {
            return (request, pathVariables, form, body) -> null;
        }
        return (request, pathVariables, form, body) -> reader.read(request, pathVariables, form);
    }

    private static boolean encoded(Argument<?> argument, AnnotationMetadata methodMetadata) {
        return argument.getAnnotationMetadata().hasAnnotation(Encoded.class) || methodMetadata.hasAnnotation(Encoded.class);
    }

    private static boolean isRequestAnnotated(AnnotationMetadata metadata) {
        for (Class<? extends Annotation> annotation : REQUEST_ANNOTATIONS) {
            if (metadata.hasAnnotation(annotation)) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable String httpMethod(AnnotationMetadata metadata) {
        return metadata.stringValue(HttpMethod.class).map(name -> name.toUpperCase(Locale.ENGLISH)).orElse(null);
    }

    private static List<String> mediaTypes(AnnotationMetadata methodMetadata, AnnotationMetadata classMetadata,
                                           Class<? extends Annotation> annotation) {
        String[] values = methodMetadata.stringValues(annotation);
        if (values.length == 0) {
            values = classMetadata.stringValues(annotation);
        }
        List<String> mediaTypes = new ArrayList<>();
        for (String value : values) {
            for (String mediaType : value.split(",")) {
                String trimmed = mediaType.trim();
                if (!trimmed.isEmpty()) {
                    mediaTypes.add(trimmed);
                }
            }
        }
        return mediaTypes;
    }

    /**
     * How to create a class for every request: with the values of the request in its constructor,
     * fields and setters. {@code null} for a class that is not created per request.
     */
    private <T> @Nullable RequestType<T> requestType(Class<T> type, @Nullable BeanDefinition<T> definition) {
        BeanIntrospection<T> introspection = introspection(type);
        Argument<?>[] constructorArguments = definition != null ? definition.getConstructor().getArguments()
            : introspection != null ? introspection.getConstructorArguments() : new Argument<?>[0];
        boolean encodedType = introspection != null && introspection.hasAnnotation(Encoded.class);
        JaxRsRouteSupport.@Nullable ValueReader[] constructorReaders = new JaxRsRouteSupport.ValueReader[constructorArguments.length];
        boolean perRequest = false;
        boolean usesForm = false;
        for (int i = 0; i < constructorArguments.length; i++) {
            Argument<?> argument = constructorArguments[i];
            if (isRequestAnnotated(argument.getAnnotationMetadata())) {
                boolean encoded = encodedType || argument.getAnnotationMetadata().hasAnnotation(Encoded.class);
                constructorReaders[i] = support.reader(argument.getAnnotationMetadata(), argument, encoded);
                perRequest = true;
                usesForm |= usesForm(argument);
            }
        }
        List<Member<T>> members = new ArrayList<>();
        if (introspection != null) {
            for (BeanProperty<T, Object> property : introspection.getBeanProperties()) {
                AnnotationMetadata metadata = property.getAnnotationMetadata();
                if (!property.isReadOnly() && isRequestAnnotated(metadata)) {
                    boolean encoded = encodedType || metadata.hasAnnotation(Encoded.class);
                    members.add(new Member<>(property, support.reader(metadata, property.asArgument(), encoded)));
                    usesForm |= usesForm(property.asArgument());
                }
            }
        }
        if (!perRequest && members.isEmpty()) {
            return null;
        }
        String[] names = new String[constructorArguments.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = constructorArguments[i].getName();
        }
        return new RequestType<>(type, definition != null, introspection, constructorReaders, names, members, usesForm);
    }

    /**
     * Create a type for a request: the prototype bean with the values of the request as its
     * {@code @Parameter}s, or else the class, then its fields and setters with the values of the
     * request.
     */
    private <T> T create(RequestType<T> requestType, HttpRequest<?> request, PathVariables pathVariables, @Nullable FormData form) {
        JaxRsRouteSupport.@Nullable ValueReader[] readers = requestType.constructorReaders();
        Object[] values = new Object[readers.length];
        List<String> names = new ArrayList<>();
        List<Object> requestValues = new ArrayList<>();
        for (int i = 0; i < readers.length; i++) {
            JaxRsRouteSupport.ValueReader reader = readers[i];
            if (reader != null) {
                values[i] = reader.read(request, pathVariables, form);
                names.add(requestType.names()[i]);
                requestValues.add(values[i]);
            }
        }
        T instance;
        BeanIntrospection<T> introspection = requestType.introspection();
        if (requestType.bean() || introspection == null) {
            instance = support.create(requestType.type(), names.toArray(String[]::new), requestValues.toArray());
        } else {
            instance = beanContext.inject(values.length == 0 ? introspection.instantiate() : introspection.instantiate(values));
        }
        for (Member<T> member : requestType.members()) {
            JaxRsRouteSupport.ValueReader reader = member.reader();
            member.property().set(instance, reader == null ? null : reader.read(request, pathVariables, form));
        }
        return instance;
    }

    private static boolean usesForm(Argument<?> argument) {
        AnnotationMetadata metadata = argument.getAnnotationMetadata();
        return metadata.hasAnnotation(FormParam.class) || metadata.hasAnnotation(BeanParam.class) && usesForm(argument.getType(), 0);
    }

    /**
     * @return Whether a {@code @BeanParam} type, or one nested in it, reads a form field
     */
    private static boolean usesForm(Class<?> type, int depth) {
        if (depth > 8) {
            return false;
        }
        BeanIntrospection<?> introspection = introspection(type);
        if (introspection == null) {
            return false;
        }
        for (Argument<?> argument : introspection.getConstructorArguments()) {
            if (argument.getAnnotationMetadata().hasAnnotation(FormParam.class)) {
                return true;
            }
        }
        for (BeanProperty<?, ?> property : introspection.getBeanProperties()) {
            AnnotationMetadata metadata = property.getAnnotationMetadata();
            if (metadata.hasAnnotation(FormParam.class)
                || metadata.hasAnnotation(BeanParam.class) && usesForm(property.getType(), depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A route template in the language of JAX-RS: the paths joined by a slash.
     */
    static String template(String prefix, String path) {
        StringBuilder template = new StringBuilder();
        for (String part : new String[]{prefix, path}) {
            String trimmed = strip(part);
            if (!trimmed.isEmpty()) {
                template.append('/').append(trimmed);
            }
        }
        return template.isEmpty() ? "/" : template.toString();
    }

    /**
     * The number of path segments a template matches, a variable with a regular expression
     * counted as one.
     */
    static int segments(String template) {
        int segments = 0;
        int depth = 0;
        boolean inSegment = false;
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
            if (c == '/' && depth == 0) {
                inSegment = false;
            } else if (!inSegment) {
                inSegment = true;
                segments++;
            }
        }
        return segments;
    }

    private static String strip(String path) {
        int start = 0;
        int end = path.length();
        while (start < end && path.charAt(start) == '/') {
            start++;
        }
        while (end > start && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(start, end);
    }

    /**
     * Creates an instance of a class for a request.
     *
     * @param <T> The class
     */
    @FunctionalInterface
    private interface Creator<T> {
        T create(HttpRequest<?> request, PathVariables pathVariables, @Nullable FormData form);
    }

    /**
     * Converts the result of a method to its response.
     */
    @FunctionalInterface
    private interface Converter {
        HttpResponse<?> convert(HttpRequest<?> request, @Nullable Object result);
    }

    /**
     * Completes the response of a method with its media type.
     */
    @FunctionalInterface
    private interface Completion {
        HttpResponse<?> complete(HttpRequest<?> request, PathVariables pathVariables, HttpResponse<?> response);
    }

    /**
     * Gets the instance a route calls.
     *
     * @param <B> The type of the instance
     */
    @FunctionalInterface
    private interface Instances<B> {
        B get(HttpRequest<?> request, PathVariables pathVariables, @Nullable FormData form) throws Exception;
    }

    /**
     * Reads the value of a parameter of a resource method.
     */
    @FunctionalInterface
    private interface Reader {
        @Nullable Object read(HttpRequest<?> request, PathVariables pathVariables, @Nullable FormData form, byte @Nullable [] body);
    }

    /**
     * A resource: its bean definition, and its executable resource methods and locators.
     *
     * @param definition The bean definition
     * @param methods    The methods
     * @param <B>        The type of the bean
     */
    private record Resource<B>(BeanDefinition<B> definition, List<ExecutableMethod<B, ?>> methods) {
    }

    /**
     * A root resource.
     *
     * @param type       Its class
     * @param definition Its bean definition, {@code null} for a class the processor never saw
     * @param metadata   The annotations of its class
     * @param methods    Its resource methods and locators
     * @param instances  Gets its instance
     * @param <B>        Its type
     */
    private record Root<B>(Class<B> type, @Nullable BeanDefinition<B> definition, AnnotationMetadata metadata,
                           List<JaxRsMethod<B>> methods, Supplier<B> instances) {
    }

    /**
     * A resource method or a locator, an executable method of a bean definition or an
     * introspection.
     *
     * @param name       The name
     * @param arguments  The parameters
     * @param returnType The return type
     * @param metadata   The annotations of the method
     * @param executable Calls it
     * @param <B>        The type of the instances it is called on
     */
    private record JaxRsMethod<B>(String name, Argument<?>[] arguments, Argument<?> returnType, AnnotationMetadata metadata,
                                  Executable<B, ?> executable) {
    }

    /**
     * A resource method, and how to call it.
     *
     * @param method     The method
     * @param httpMethod The name of its HTTP method
     * @param template   Its template
     * @param readers    How to read its parameters
     * @param form       Whether it has form parameters
     * @param formEntity Whether its entity is the form
     * @param usesForm   Whether a bean parameter reads the form
     * @param entity     The index of its entity parameter, -1 without one
     * @param async      Whether it returns a stage of its result
     * @param produces   Whether it declares the media types it produces
     * @param converter  Converts its result to its response
     * @param completion Completes its response
     * @param metadata   Its route metadata
     * @param <B>        The type of the instances it is called on
     */
    private record ResourceMethod<B>(JaxRsMethod<B> method, String httpMethod, String template, Reader[] readers, boolean form,
                                     boolean formEntity, boolean usesForm, int entity, boolean async, boolean produces,
                                     Converter converter, Completion completion, JaxRsRouteSupport.RouteMetadata metadata) {
    }

    /**
     * A field or setter of a type created per request.
     *
     * @param property The property
     * @param reader   How to read its value
     * @param <T>      The type that has it
     */
    private record Member<T>(BeanProperty<T, Object> property, JaxRsRouteSupport.@Nullable ValueReader reader) {
    }

    /**
     * A type created per request.
     *
     * @param type               The type
     * @param bean               Whether it is a bean
     * @param introspection      Its introspection
     * @param constructorReaders How to read the values of the parameters of its constructor
     * @param names              The names of the parameters of its constructor
     * @param members            Its fields and setters with values of the request
     * @param usesForm           Whether it reads the form
     * @param <T>                The type
     */
    private record RequestType<T>(Class<T> type, boolean bean, @Nullable BeanIntrospection<T> introspection,
                               JaxRsRouteSupport.@Nullable ValueReader[] constructorReaders, String[] names,
                                  List<Member<T>> members, boolean usesForm) {
    }
}
