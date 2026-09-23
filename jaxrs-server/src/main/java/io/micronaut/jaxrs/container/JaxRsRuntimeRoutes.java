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
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.form.FormData;
import io.micronaut.http.AsyncServerHttpRequest;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.reflection.ReflectionAnnotations;
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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * The routes of the JAX-RS resources, built at runtime: the public resource methods and
 * sub-resource locators of each root resource class are collected with reflection, with the
 * annotations JAX-RS inherits (section 3.6), and routed with handler functions that read their
 * parameters with {@link JaxRsRouteSupport}, call them and convert their result. A sub-resource
 * locator locates its target at runtime, whose class is routed the same way.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
@Singleton
final class JaxRsRuntimeRoutes implements HttpRoutes {

    private static final Logger LOG = LoggerFactory.getLogger(JaxRsRuntimeRoutes.class);

    private static final Set<String> NO_BODY_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private static final String NAMED = "jakarta.inject.Named";

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

    JaxRsRuntimeRoutes(BeanContext beanContext, JaxRsRouteSupport support) {
        this.beanContext = beanContext;
        this.support = support;
    }

    @Override
    public void routes(HttpRouteBuilder routes) {
        Map<Class<?>, Supplier<Object>> resources = rootResources();
        resources.forEach((type, instances) -> {
            if (!support.isRegistered(type)) {
                return;
            }
            Path path = rootPath(type);
            String rootPath = path == null ? "" : path.value();
            // the root resource class is selected first by the specificity of its @Path: the end
            // of it is marked in the templates, for the JAX-RS route template engine
            String prefix = strip(rootPath).isEmpty() ? rootPath : strip(rootPath) + JaxRsRouteTemplateEngine.ROOT_MARK;
            RequestType root = requestType(type);
            int rootSegments = segments(rootPath);
            Instances instance = root == null
                ? (request, pathVariables, form) -> support.matched(request, instances.get(), rootSegments)
                : (request, pathVariables, form) -> support.matched(request, create(root, request, pathVariables, form), rootSegments);
            declare(routes, type, type, prefix, false, instance, root, new LinkedHashMap<>(Map.of(type, 1)), false);
        });
    }

    /**
     * Declare the routes of the class of a target a locator located at runtime, relative to the
     * prefix of the locator.
     *
     * @param type   The class of the target
     * @param routes The builder
     */
    void located(Class<?> type, HttpRouteBuilder routes) {
        declare(routes, type, type, "", true, (request, pathVariables, form) -> pathVariables.locatedTarget(type), null,
            new LinkedHashMap<>(Map.of(type, 1)), false);
    }

    /**
     * The root resource classes and how to get their instance: the beans with {@code @Path}, or
     * marked as resources, and the classes and instances of the {@code Application}.
     */
    private Map<Class<?>, Supplier<Object>> rootResources() {
        Map<Class<?>, Supplier<Object>> resources = new LinkedHashMap<>();
        List<BeanDefinition<?>> definitions = new ArrayList<>(beanContext.getBeanDefinitions(Qualifiers.byStereotype(Path.class)));
        definitions.addAll(beanContext.getBeanDefinitions(Qualifiers.byStereotype(JaxRsResource.class)));
        for (BeanDefinition<?> definition : definitions) {
            // the class of the resource, not the one of its AOP proxy
            Class<?> type = resourceClass(definition.getBeanType());
            if (definition.hasStereotype(Controller.class) || !isRootResource(type)) {
                // routed as a controller, e.g. compiled by another language
                continue;
            }
            resources.putIfAbsent(type, () -> beanContext.getBean(type));
        }
        Application application = beanContext.findBean(Application.class).orElse(null);
        if (application != null) {
            for (Object singleton : application.getSingletons()) {
                if (isRootResource(singleton.getClass())) {
                    // the instance of the application is the resource (JAX-RS 2.3)
                    resources.put(singleton.getClass(), () -> singleton);
                }
            }
            for (Class<?> type : application.getClasses()) {
                if (isRootResource(type) && !resources.containsKey(type)) {
                    // a class the annotation processors never saw
                    resources.put(type, () -> newInstance(type));
                }
            }
        }
        return resources;
    }

    /**
     * The {@code @Path} of a root resource class: of the class, else of a superclass or of an
     * interface it implements, as the annotation processor sees it.
     */
    private static @Nullable Path rootPath(Class<?> type) {
        for (Class<?> t = type; t != null && t != Object.class; t = t.getSuperclass()) {
            Path path = t.getAnnotation(Path.class);
            if (path != null) {
                return path;
            }
        }
        for (Class<?> t = type; t != null && t != Object.class; t = t.getSuperclass()) {
            Path path = interfacePath(t.getInterfaces());
            if (path != null) {
                return path;
            }
        }
        return null;
    }

    private static @Nullable Path interfacePath(Class<?>[] interfaces) {
        for (Class<?> i : interfaces) {
            Path path = i.getAnnotation(Path.class);
            if (path == null) {
                path = interfacePath(i.getInterfaces());
            }
            if (path != null) {
                return path;
            }
        }
        return null;
    }

    private static Class<?> resourceClass(Class<?> type) {
        Class<?> resource = type;
        while (isProxy(resource) && resource.getSuperclass() != null) {
            resource = resource.getSuperclass();
        }
        return resource;
    }

    private static boolean isProxy(Class<?> type) {
        for (Class<?> i : type.getInterfaces()) {
            if ("io.micronaut.aop.Intercepted".equals(i.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRootResource(Class<?> type) {
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            return false;
        }
        if (rootPath(type) != null) {
            return true;
        }
        for (Method method : type.getMethods()) {
            if (httpMethod(annotated(type, method)) != null) {
                return true;
            }
        }
        return false;
    }

    private Object newInstance(Class<?> type) {
        if (beanContext.containsBean(type)) {
            return beanContext.getBean(type);
        }
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return beanContext.inject(constructor.newInstance());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot create the resource " + type.getName(), e);
        }
    }

    /**
     * Declare the routes of the resource methods and locators of a class.
     */
    private void declare(HttpRouteBuilder routes, Class<?> type, Class<?> rootClass, String path, boolean located,
                         Instances instances, @Nullable RequestType root, Map<Class<?>, Integer> visited, boolean chainUsesForm) {
        Map<String, Method> methods = new LinkedHashMap<>();
        for (Method method : type.getMethods()) {
            if (method.isBridge() || method.isSynthetic() || Modifier.isStatic(method.getModifiers())
                || Modifier.isAbstract(method.getModifiers()) || method.getDeclaringClass() == Object.class) {
                continue;
            }
            methods.putIfAbsent(method.getName() + Arrays.toString(method.getParameterTypes()), method);
        }
        for (Method method : methods.values()) {
            Method annotated = annotated(type, method);
            String httpMethod = httpMethod(annotated);
            Path methodPath = annotated.getAnnotation(Path.class);
            if (httpMethod != null) {
                String template = template(path, methodPath == null ? "" : methodPath.value());
                ResourceMethod resourceMethod = resourceMethod(type, rootClass, method, annotated, httpMethod, template);
                if (resourceMethod != null) {
                    route(routes, resourceMethod, located, instances, root, chainUsesForm);
                }
            } else if (methodPath != null) {
                // a resource method is selected before a locator: the end of its @Path is marked
                String prefix = template(path, methodPath.value()) + JaxRsRouteTemplateEngine.LOCATOR_MARK;
                locator(routes, type, rootClass, method, annotated, prefix, located, instances, root, visited, chainUsesForm);
            }
        }
    }

    /**
     * Route a sub-resource locator. A locator whose target class is known is followed: the
     * routes of the class are declared under its prefix, and call the locator. A target known
     * only at runtime, or a locator that repeats, is located by the router, which routes the
     * rest of the path with the routes of the class of the target.
     */
    private void locator(HttpRouteBuilder routes, Class<?> owner, Class<?> rootClass, Method method, Method annotated, String prefix,
                         boolean located, Instances instances, @Nullable RequestType root, Map<Class<?>, Integer> visited,
                         boolean chainUsesForm) {
        if (!Modifier.isPublic(method.getDeclaringClass().getModifiers()) && !method.trySetAccessible()) {
            LOG.warn("The JAX-RS sub-resource locator {} is not accessible: it is not routed", method);
            return;
        }
        Parameter[] parameters = parameters(method, annotated);
        Reader[] readers = new Reader[parameters.length];
        boolean usesForm = chainUsesForm;
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            if (!isRequestAnnotated(parameter.metadata())) {
                LOG.info("The JAX-RS sub-resource locator {} has a parameter that is not read from the request: it is not routed", method);
                return;
            }
            readers[i] = reader(parameter, method);
            usesForm |= usesForm(parameter.metadata(), parameter.argument().getType());
        }
        Type returned = method.getGenericReturnType();
        boolean classReturn = method.getReturnType() == Class.class;
        if (classReturn) {
            returned = returned instanceof ParameterizedType parameterized ? parameterized.getActualTypeArguments()[0] : Object.class;
        }
        Class<?> target = returned instanceof Class<?> c ? c : returned instanceof ParameterizedType p && p.getRawType() instanceof Class<?> r ? r : null;
        int occurrences = target == null ? 0 : visited.getOrDefault(target, 0);
        if (target == null || target.isPrimitive() || target == Object.class || Response.class.isAssignableFrom(target)
            || occurrences >= MAX_LOCATOR_REPEAT) {
            // known only at runtime, or recursive: the paths cannot be enumerated
            if (classReturn) {
                LOG.info("The JAX-RS sub-resource locator {} returns a class known only at runtime: it is not routed", method);
                return;
            }
            if (usesForm && !chainUsesForm) {
                LOG.info("The JAX-RS sub-resource locator {} known only at runtime has a form parameter: it is not routed", method);
                return;
            }
            int segments = located ? -1 : segments(prefix);
            routes.locate(located ? support.locatedPrefix(prefix) : support.prefix(prefix), (request, pathVariables) -> {
                Object instance = instances.get(request, pathVariables, null);
                return support.matched(request, locate(method, instance, readers, request, pathVariables, null), segments);
            }, support.locatedTables());
            return;
        }
        int segments = segments(prefix);
        Instances chained = (request, pathVariables, form) -> {
            Object instance = instances.get(request, pathVariables, form);
            return support.matched(request, locate(method, instance, readers, request, pathVariables, form), segments);
        };
        visited.put(target, occurrences + 1);
        try {
            declare(routes, target, rootClass, prefix, located, chained, root, visited, usesForm);
        } finally {
            visited.put(target, occurrences);
        }
    }

    /**
     * Call a locator: its target, or an instance created for the request of the class it returns.
     */
    private Object locate(Method method, Object instance, Reader[] readers, HttpRequest<?> request, PathVariables pathVariables,
                          @Nullable FormData form) {
        Object[] arguments = new Object[readers.length];
        for (int i = 0; i < readers.length; i++) {
            arguments[i] = readers[i].read(request, pathVariables, form, null);
        }
        // a locator returning null is a 404
        Object target = support.located(invoke(method, instance, arguments));
        if (target instanceof Class<?> targetClass) {
            // the locator returned the class: an instance is created for the request
            RequestType created = requestType(targetClass);
            target = created == null ? newInstance(targetClass) : create(created, request, pathVariables, form);
        }
        return target;
    }

    private void route(HttpRouteBuilder routes, ResourceMethod method, boolean located, Instances instances,
                       @Nullable RequestType root, boolean chainUsesForm) {
        String name = method.httpMethod();
        RouteDeclaration declaration = located
            ? support.locatedDeclaration(name, method.template())
            : support.declaration(name, method.template());
        boolean body = method.entity() >= 0 && !method.formEntity() && !"GET".equals(name);
        boolean usesForm = method.usesForm() || chainUsesForm || root != null && root.usesForm();
        // a form is read for a method that can have one and reads no entity
        boolean form = method.form() || usesForm && !NO_BODY_METHODS.contains(name) && method.entity() < 0;
        // a route that reads the entity and form parameters: the form is parsed from the entity
        boolean entityForm = body && !form && usesForm;
        HttpRouteSpec spec;
        if (method.async()) {
            if (form) {
                spec = routes.handleAsync(declaration, (request, pathVariables) -> support.formAsync(request, pathVariables,
                    (readRequest, readPathVariables, value) -> (CompletionStage<? extends HttpResponse<?>>) call(method, instances, readRequest, readPathVariables, value, null)));
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
    private Object call(ResourceMethod method, Instances instances, HttpRequest<?> request, PathVariables pathVariables,
                        @Nullable FormData form, byte @Nullable [] body) throws Exception {
        if (!method.produces().isEmpty()) {
            // a negotiated type that is not concrete is not acceptable
            support.acceptable(pathVariables);
        }
        Object instance = instances.get(request, pathVariables, form);
        Reader[] readers = method.readers();
        Object[] arguments = new Object[readers.length];
        for (int i = 0; i < readers.length; i++) {
            arguments[i] = readers[i].read(request, pathVariables, form, body);
        }
        Object result = invoke(method.method(), instance, arguments);
        if (method.async()) {
            CompletionStage<?> stage = (CompletionStage<?>) result;
            if (stage == null) {
                throw new IllegalStateException("The asynchronous resource method " + method.method() + " returned no stage");
            }
            return stage.thenApply(value -> response(method, request, value));
        }
        HttpResponse<?> response = response(method, request, result);
        if (method.produces().isEmpty()) {
            // the type of the response from the JAX-RS writers of its entity
            return support.negotiate(request, response, method.returnType());
        }
        // the entity has the negotiated type, also when a HEAD request drops it (JAX-RS 3.8)
        return support.produced(pathVariables, response);
    }

    /**
     * The response of a result, converted as its declared type says.
     */
    private HttpResponse<?> response(ResourceMethod method, HttpRequest<?> request, @Nullable Object result) {
        Type type = method.valueType();
        if (type == void.class || type == Void.class && !method.async()) {
            return support.noContent();
        }
        Class<?> raw = Argument.of(type).getType();
        if (type instanceof TypeVariable<?> || type instanceof WildcardType || raw == Object.class) {
            // known only at runtime
            return support.anyResponse(request, result, method.returnType());
        }
        if (Response.class.isAssignableFrom(raw)) {
            return support.jaxRsResponse((Response) result);
        }
        if (HttpResponse.class.isAssignableFrom(raw)) {
            return support.httpResponse((HttpResponse<?>) result);
        }
        if (GenericEntity.class.isAssignableFrom(raw)) {
            // the type of the generic entity selects the message body writer
            return support.genericEntityResponse(request, (GenericEntity<?>) result);
        }
        if (type instanceof ParameterizedType) {
            // the declared type, with its type arguments, selects the message body writer
            return support.genericEntityResponse(request, result, method.returnType());
        }
        return support.entityResponse(result);
    }

    private static @Nullable Object invoke(Method method, Object instance, @Nullable Object[] arguments) {
        try {
            return method.invoke(instance, arguments);
        } catch (InvocationTargetException e) {
            // what the resource method throws, unchanged
            throw JaxRsRouteSupport.rethrow(e.getCause() == null ? e : e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot call the resource method " + method, e);
        }
    }

    private @Nullable ResourceMethod resourceMethod(Class<?> owner, Class<?> rootClass, Method method, Method annotated,
                                                    String httpMethod, String template) {
        if (!Modifier.isPublic(method.getDeclaringClass().getModifiers()) && !method.trySetAccessible()) {
            LOG.warn("The JAX-RS resource method {} is not accessible: it is not routed", method);
            return null;
        }
        Parameter[] parameters = parameters(method, annotated);
        Reader[] readers = new Reader[parameters.length];
        boolean form = false;
        boolean usesForm = false;
        int entity = -1;
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            AnnotationMetadata metadata = parameter.metadata();
            if (metadata.hasAnnotation(Suspended.class)) {
                LOG.warn("JAX-RS asynchronous responses with @Suspended are not routed yet: {}", method);
                return null;
            }
            if (metadata.hasAnnotation(FormParam.class) && NO_BODY_METHODS.contains(httpMethod)) {
                // no form without a body: read from the query, like the controllers did
                String name = metadata.stringValue(FormParam.class).orElse("");
                String defaultValue = metadata.stringValue(DefaultValue.class).orElse(null);
                boolean encoded = encoded(parameter, method);
                Argument<?> argument = parameter.argument();
                readers[i] = (request, pathVariables, f, body) -> support.queryParam(request, name, argument, defaultValue, encoded);
            } else if (isRequestAnnotated(metadata)) {
                readers[i] = reader(parameter, method);
                form |= metadata.hasAnnotation(FormParam.class);
                usesForm |= metadata.hasAnnotation(BeanParam.class) && usesForm(parameter.argument().getType(), 0);
            } else if (CONTEXT_TYPES.contains(parameter.argument().getType().getName())) {
                Argument<?> argument = parameter.argument();
                String named = metadata.stringValue(NAMED).orElse(null);
                readers[i] = (request, pathVariables, f, body) -> support.context(request, argument, named);
            } else {
                if (entity >= 0) {
                    LOG.error("The JAX-RS resource method {} has more than one entity parameter: it is not routed", method);
                    return null;
                }
                entity = i;
            }
        }
        boolean formEntity = false;
        if (entity >= 0) {
            Parameter parameter = parameters[entity];
            Argument<?> argument = parameter.argument();
            if (form) {
                Class<?> entityType = argument.getType();
                if (entityType != MultivaluedMap.class && entityType != Form.class) {
                    LOG.error("The entity of the JAX-RS resource method {} with @FormParam parameters must be a form: it is not routed", method);
                    return null;
                }
                formEntity = true;
                readers[entity] = (request, pathVariables, f, body) -> f == null ? null : support.formEntity(f, argument);
            } else {
                // the readers see the annotations of the parameter
                Argument<?> entityArgument = parameter.metadata().isEmpty() ? argument
                    : Argument.of(argument.getType(), parameter.metadata(), argument.getTypeParameters());
                readers[entity] = (request, pathVariables, f, body) -> support.entity(request, body, entityArgument);
            }
        }
        Type genericReturnType = method.getGenericReturnType();
        Class<?> returnClass = method.getReturnType();
        boolean async = returnClass == CompletionStage.class
            || CompletionStage.class.isAssignableFrom(returnClass) && returnClass.getName().startsWith("java.util.concurrent.");
        Type valueType = async
            ? genericReturnType instanceof ParameterizedType parameterized ? parameterized.getActualTypeArguments()[0] : Object.class
            : genericReturnType;
        Argument<?> returnType = valueType == void.class || valueType == Void.class ? Argument.VOID : Argument.of(valueType);
        List<String> produces = mediaTypes(annotated, owner, Produces.class);
        List<String> consumes = mediaTypes(annotated, owner, Consumes.class);
        JaxRsRouteSupport.RouteMetadata metadata = new JaxRsRouteSupport.RouteMetadata(owner, method.getName(), method.getParameterTypes(),
            produces.toArray(String[]::new), consumes.toArray(String[]::new), rootClass);
        return new ResourceMethod(method, httpMethod, template, readers, form, formEntity, usesForm, entity, async, valueType,
            returnType, produces, metadata);
    }

    private Reader reader(Parameter parameter, Method method) {
        JaxRsRouteSupport.ValueReader reader = support.reader(parameter.metadata(), parameter.argument(), encoded(parameter, method));
        if (reader == null) {
            return (request, pathVariables, form, body) -> null;
        }
        return (request, pathVariables, form, body) -> reader.read(request, pathVariables, form);
    }

    private static boolean encoded(Parameter parameter, Method method) {
        return parameter.metadata().hasAnnotation(Encoded.class) || method.isAnnotationPresent(Encoded.class);
    }

    private static boolean isRequestAnnotated(AnnotationMetadata metadata) {
        for (Class<? extends Annotation> annotation : REQUEST_ANNOTATIONS) {
            if (metadata.hasAnnotation(annotation)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRequestAnnotated(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if (REQUEST_ANNOTATIONS.contains(annotation.annotationType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The parameters of a method, with the annotations of the method that declares its JAX-RS
     * annotations.
     */
    private static Parameter[] parameters(Method method, Method annotated) {
        Type[] types = method.getGenericParameterTypes();
        Annotation[][] annotations = annotated.getParameterAnnotations();
        Parameter[] parameters = new Parameter[types.length];
        for (int i = 0; i < types.length; i++) {
            AnnotationMetadata metadata = annotations[i].length == 0 ? AnnotationMetadata.EMPTY_METADATA
                : ReflectionAnnotations.metadataOf(annotations[i]);
            parameters[i] = new Parameter(Argument.of(types[i]), metadata);
        }
        return parameters;
    }

    /**
     * The method whose JAX-RS annotations a method has (JAX-RS 3.6): the method itself, else the
     * one it overrides in a superclass, then in an interface, that has JAX-RS annotations. A
     * method with JAX-RS annotations inherits none.
     */
    private static Method annotated(Class<?> type, Method method) {
        if (hasJaxRsAnnotations(method)) {
            return method;
        }
        for (Class<?> t = type; t != null && t != Object.class; t = t.getSuperclass()) {
            Method declared = declared(t, method);
            if (declared != null && hasJaxRsAnnotations(declared)) {
                return declared;
            }
        }
        for (Class<?> t = type; t != null && t != Object.class; t = t.getSuperclass()) {
            Method found = fromInterfaces(t.getInterfaces(), method);
            if (found != null) {
                return found;
            }
        }
        return method;
    }

    private static @Nullable Method fromInterfaces(Class<?>[] interfaces, Method method) {
        for (Class<?> i : interfaces) {
            Method declared = declared(i, method);
            if (declared != null && hasJaxRsAnnotations(declared)) {
                return declared;
            }
            Method inherited = fromInterfaces(i.getInterfaces(), method);
            if (inherited != null) {
                return inherited;
            }
        }
        return null;
    }

    private static @Nullable Method declared(Class<?> type, Method method) {
        try {
            return type.getDeclaredMethod(method.getName(), method.getParameterTypes());
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static boolean hasJaxRsAnnotations(Method method) {
        for (Annotation annotation : method.getDeclaredAnnotations()) {
            if (isJaxRs(annotation)) {
                return true;
            }
        }
        for (Annotation[] parameter : method.getParameterAnnotations()) {
            for (Annotation annotation : parameter) {
                if (isJaxRs(annotation)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isJaxRs(Annotation annotation) {
        Class<? extends Annotation> type = annotation.annotationType();
        return type.getName().startsWith("jakarta.ws.rs.") || type.isAnnotationPresent(HttpMethod.class);
    }

    private static @Nullable String httpMethod(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            HttpMethod httpMethod = annotation.annotationType().getAnnotation(HttpMethod.class);
            if (httpMethod != null) {
                return httpMethod.value().toUpperCase(Locale.ENGLISH);
            }
        }
        return null;
    }

    private static List<String> mediaTypes(Method method, Class<?> owner, Class<? extends Annotation> annotation) {
        String[] values = values(method.getAnnotation(annotation));
        if (values.length == 0) {
            values = values(owner.getAnnotation(annotation));
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

    private static String[] values(@Nullable Annotation annotation) {
        if (annotation instanceof Produces produces) {
            return produces.value();
        }
        if (annotation instanceof Consumes consumes) {
            return consumes.value();
        }
        return new String[0];
    }

    /**
     * How to create a class for every request: with the values of the request in its constructor,
     * fields and setters. {@code null} for a class that is not created per request.
     */
    private @Nullable RequestType requestType(Class<?> type) {
        Constructor<?> constructor = requestConstructor(type);
        List<Member> members = new ArrayList<>();
        for (Class<?> t = type; t != null && t != Object.class; t = t.getSuperclass()) {
            for (Field field : t.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && isRequestAnnotated(field.getAnnotations())) {
                    AnnotationMetadata metadata = ReflectionAnnotations.metadataOf(field.getAnnotations());
                    boolean encoded = metadata.hasAnnotation(Encoded.class) || type.isAnnotationPresent(Encoded.class);
                    members.add(new Member(field, null, support.reader(metadata, Argument.of(field.getGenericType()), encoded),
                        usesForm(metadata, field.getType())));
                }
            }
        }
        for (Method method : type.getMethods()) {
            if (method.getParameterCount() == 1 && !Modifier.isStatic(method.getModifiers()) && httpMethod(method) == null
                && !method.isAnnotationPresent(Path.class) && isRequestAnnotated(method.getAnnotations())) {
                AnnotationMetadata metadata = ReflectionAnnotations.metadataOf(method.getAnnotations());
                boolean encoded = metadata.hasAnnotation(Encoded.class) || type.isAnnotationPresent(Encoded.class);
                members.add(new Member(null, method, support.reader(metadata, Argument.of(method.getGenericParameterTypes()[0]), encoded),
                    usesForm(metadata, method.getParameterTypes()[0])));
            }
        }
        if (constructor == null && members.isEmpty()) {
            return null;
        }
        JaxRsRouteSupport.@Nullable ValueReader[] constructorReaders = new JaxRsRouteSupport.ValueReader[0];
        String[] names = new String[0];
        if (constructor != null) {
            Annotation[][] annotations = constructor.getParameterAnnotations();
            Type[] types = constructor.getGenericParameterTypes();
            constructorReaders = new JaxRsRouteSupport.ValueReader[types.length];
            for (int i = 0; i < types.length; i++) {
                if (isRequestAnnotated(annotations[i])) {
                    AnnotationMetadata metadata = ReflectionAnnotations.metadataOf(annotations[i]);
                    boolean encoded = metadata.hasAnnotation(Encoded.class) || type.isAnnotationPresent(Encoded.class);
                    constructorReaders[i] = support.reader(metadata, Argument.of(types[i]), encoded);
                }
            }
            // the names of the @Parameters of the bean, which the annotation processor saw
            names = beanContext.findBeanDefinition(type)
                .map(definition -> Arrays.stream(definition.getConstructor().getArguments()).map(Argument::getName).toArray(String[]::new))
                .orElse(names);
        }
        return new RequestType(type, constructor, constructorReaders, names, members);
    }

    /**
     * Create a type for a request: the prototype bean with the values of the request as its
     * {@code @Parameter}s, or else the class, then its fields and setters with the values of the
     * request.
     */
    private Object create(RequestType requestType, HttpRequest<?> request, PathVariables pathVariables, @Nullable FormData form) {
        Class<?> type = requestType.type();
        Constructor<?> constructor = requestType.constructor();
        Object instance;
        if (constructor == null) {
            instance = beanContext.containsBean(type) ? support.create(type, new String[0], new Object[0]) : newInstance(type);
        } else {
            JaxRsRouteSupport.@Nullable ValueReader[] readers = requestType.constructorReaders();
            Object[] values = new Object[readers.length];
            for (int i = 0; i < readers.length; i++) {
                JaxRsRouteSupport.ValueReader reader = readers[i];
                values[i] = reader == null ? null : reader.read(request, pathVariables, form);
            }
            if (beanContext.containsBean(type) && requestType.names().length == values.length) {
                List<String> names = new ArrayList<>();
                List<Object> requestValues = new ArrayList<>();
                for (int i = 0; i < readers.length; i++) {
                    if (readers[i] != null) {
                        names.add(requestType.names()[i]);
                        requestValues.add(values[i]);
                    }
                }
                instance = support.create(type, names.toArray(String[]::new), requestValues.toArray());
            } else {
                try {
                    constructor.setAccessible(true);
                    instance = beanContext.inject(constructor.newInstance(values));
                } catch (InvocationTargetException e) {
                    throw JaxRsRouteSupport.rethrow(e.getCause() == null ? e : e.getCause());
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Cannot create " + type.getName(), e);
                }
            }
        }
        for (Member member : requestType.members()) {
            JaxRsRouteSupport.ValueReader reader = member.reader();
            Object value = reader == null ? null : reader.read(request, pathVariables, form);
            Field field = member.field();
            if (field != null) {
                support.setField(instance, field.getDeclaringClass(), field.getName(), value);
            } else {
                Method setter = java.util.Objects.requireNonNull(member.setter());
                setter.setAccessible(true);
                invoke(setter, instance, new Object[]{value});
            }
        }
        return instance;
    }

    private static @Nullable Constructor<?> requestConstructor(Class<?> type) {
        Constructor<?> selected = null;
        for (Constructor<?> constructor : type.getConstructors()) {
            if (selected == null || constructor.getParameterCount() > selected.getParameterCount()) {
                selected = constructor;
            }
        }
        if (selected == null) {
            return null;
        }
        for (Annotation[] annotations : selected.getParameterAnnotations()) {
            if (isRequestAnnotated(annotations)) {
                return selected;
            }
        }
        return null;
    }

    private boolean usesForm(AnnotationMetadata metadata, Class<?> type) {
        return metadata.hasAnnotation(FormParam.class) || metadata.hasAnnotation(BeanParam.class) && usesForm(type, 0);
    }

    /**
     * @return Whether a {@code @BeanParam} type, or one nested in it, reads a form field
     */
    private static boolean usesForm(Class<?> type, int depth) {
        if (depth > 8) {
            return false;
        }
        for (Class<?> t = type; t != null && t != Object.class; t = t.getSuperclass()) {
            for (Field field : t.getDeclaredFields()) {
                if (field.isAnnotationPresent(FormParam.class)
                    || field.isAnnotationPresent(BeanParam.class) && usesForm(field.getType(), depth + 1)) {
                    return true;
                }
            }
        }
        for (Method method : type.getMethods()) {
            if (method.getParameterCount() == 1 && method.isAnnotationPresent(FormParam.class)) {
                return true;
            }
        }
        Constructor<?> constructor = requestConstructor(type);
        if (constructor != null) {
            for (Annotation[] annotations : constructor.getParameterAnnotations()) {
                for (Annotation annotation : annotations) {
                    if (annotation instanceof FormParam) {
                        return true;
                    }
                }
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
     * Gets the instance a route calls.
     */
    @FunctionalInterface
    private interface Instances {
        Object get(HttpRequest<?> request, PathVariables pathVariables, @Nullable FormData form) throws Exception;
    }

    /**
     * Reads the value of a parameter of a resource method.
     */
    @FunctionalInterface
    private interface Reader {
        @Nullable Object read(HttpRequest<?> request, PathVariables pathVariables, @Nullable FormData form, byte @Nullable [] body);
    }

    /**
     * A parameter of a method.
     *
     * @param argument The type of the parameter
     * @param metadata Its JAX-RS annotations, of the method that declares them
     */
    private record Parameter(Argument<?> argument, AnnotationMetadata metadata) {
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
     * @param valueType  The type of its result
     * @param returnType The argument of the type of its result
     * @param produces   Its media types
     * @param metadata   Its route metadata
     */
    private record ResourceMethod(Method method, String httpMethod, String template, Reader[] readers, boolean form,
                                  boolean formEntity, boolean usesForm, int entity, boolean async, Type valueType,
                                  Argument<?> returnType, List<String> produces, JaxRsRouteSupport.RouteMetadata metadata) {
    }

    /**
     * A field or setter of a type created per request.
     *
     * @param field    The field
     * @param setter   The setter
     * @param reader   How to read its value
     * @param usesForm Whether it reads the form
     */
    private record Member(@Nullable Field field, @Nullable Method setter, JaxRsRouteSupport.@Nullable ValueReader reader,
                          boolean usesForm) {
    }

    /**
     * A type created per request.
     *
     * @param type        The type
     * @param constructor        Its constructor with values of the request
     * @param constructorReaders How to read the values of the parameters of the constructor
     * @param names              The names of the parameters of the constructor of the bean
     * @param members            Its fields and setters with values of the request
     */
    private record RequestType(Class<?> type, @Nullable Constructor<?> constructor,
                               JaxRsRouteSupport.@Nullable ValueReader[] constructorReaders, String[] names,
                               List<Member> members) {

        boolean usesForm() {
            for (Member member : members) {
                if (member.usesForm()) {
                    return true;
                }
            }
            return constructor != null && JaxRsRuntimeRoutes.usesForm(type, 0);
        }
    }
}
