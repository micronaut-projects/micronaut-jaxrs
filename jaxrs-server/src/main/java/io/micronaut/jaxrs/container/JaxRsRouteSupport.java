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
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.jaxrs.common.JaxRsMessageBodyReader;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.bind.ArgumentBinder;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.ExceptionUtils;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.bind.RequestBinderRegistry;
import io.micronaut.http.uri.UriTemplate;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.jaxrs.common.JaxRsGenericEntity;
import io.micronaut.jaxrs.common.JaxRsMutableResponse;
import io.micronaut.http.form.FormData;
import io.micronaut.web.router.builder.HttpRouteSpec;
import io.micronaut.web.router.builder.RouteDeclaration;
import io.micronaut.web.router.builder.PathVariables;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The runtime of the routes generated for JAX-RS resources: the generated handler functions read
 * the parameters of a resource method with it, and convert the result of the method to a response.
 * Everything that depends only on a parameter or a route is resolved once and cached by the
 * {@link Argument} constants of the generated code.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
@Singleton
public final class JaxRsRouteSupport {

    private final ApplicationProvider applicationProvider;
    private final String applicationPath;
    private final ConversionService conversionService;
    private final List<ParamConverterProvider> paramConverterProviders;
    private final RequestBinderRegistry binderRegistry;
    private final Map<Argument<?>, Optional<ArgumentBinder<Object, HttpRequest<?>>>> contextBinders = new ConcurrentHashMap<>();
    private final BeanContext beanContext;
    private final Map<Argument<?>, StringConverter> converters = new ConcurrentHashMap<>();
    private final Map<String, java.lang.reflect.Field> fields = new ConcurrentHashMap<>();
    private final Map<Class<?>, BeanParamBinder> beanParams = new ConcurrentHashMap<>();

    JaxRsRouteSupport(ApplicationProvider applicationProvider,
                      ConversionService conversionService,
                      List<ParamConverterProvider> paramConverterProviders,
                      RequestBinderRegistry binderRegistry,
                      BeanContext beanContext) {
        this.applicationProvider = applicationProvider;
        this.applicationPath = applicationProvider.getAnnotationMetadata().stringValue(jakarta.ws.rs.ApplicationPath.class)
            .map(path -> java.net.URLDecoder.decode(path, java.nio.charset.StandardCharsets.UTF_8))
            .orElse("");
        this.conversionService = conversionService;
        this.paramConverterProviders = paramConverterProviders;
        this.binderRegistry = binderRegistry;
        this.beanContext = beanContext;
    }

    /**
     * The URI template of a route, under the {@code @ApplicationPath} of the application. The
     * context path is applied by the router.
     *
     * @param template The template of a resource method, the paths of its class and itself
     * @return The URI template of the route
     */
    public String uri(String template) {
        if (applicationPath.isEmpty() || "/".equals(applicationPath)) {
            return template;
        }
        String prefix = applicationPath.charAt(0) == '/' ? applicationPath : '/' + applicationPath;
        return UriTemplate.of(prefix).nest(template).toString();
    }

    /**
     * The declaration of a route, with its template in the language of JAX-RS, under the
     * {@code @ApplicationPath} of the application. The context path is applied by the router.
     *
     * @param method   The HTTP method
     * @param template The joined {@code @Path} values of the resource method
     * @return The declaration
     */
    public RouteDeclaration declaration(HttpMethod method, String template) {
        String expression = template;
        if (!applicationPath.isEmpty() && !"/".equals(applicationPath)) {
            String prefix = applicationPath.charAt(0) == '/' ? applicationPath : '/' + applicationPath;
            prefix = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
            expression = "/".equals(template) ? prefix : prefix + template;
        }
        return RouteDeclaration.of(method, JaxRsRouteTemplateEngine.template(expression));
    }

    /**
     * Configure a route: its media types, and the resource method it implements. With it, the
     * route is seen like a controller route by the container filters and their name bindings,
     * the security rules, {@code ResourceInfo} and the message body writers.
     *
     * @param route    The route
     * @param metadata The metadata of the resource method
     */
    public void configure(HttpRouteSpec route, RouteMetadata metadata) {
        if (metadata.consumes.length == 0) {
            route.consumesAll();
        } else {
            route.consumes(mediaTypes(metadata.consumes));
        }
        route.produces(metadata.produces.length == 0 ? new MediaType[]{MediaType.ALL_TYPE} : mediaTypes(metadata.produces));
        Optional<? extends ExecutableMethod<?, ?>> method = beanContext.findBeanDefinition(metadata.resourceClass)
            .flatMap(definition -> definition.findMethod(metadata.methodName, metadata.parameterTypes));
        if (method.isPresent()) {
            route.implementing(method.get());
        } else {
            // a sub-resource that is not a bean: the route has the annotations of the root resource
            beanContext.findBeanDefinition(metadata.rootClass).ifPresent(root -> route.annotationMetadata(root.getAnnotationMetadata()));
        }
    }

    /**
     * @param request       The request
     * @param pathVariables The path variables of the matched route
     * @param name          The name of the path parameter
     * @param argument      The type of the parameter
     * @param defaultValue  The {@code @DefaultValue}
     * @return The value, converted
     */
    public @Nullable Object pathParam(HttpRequest<?> request, PathVariables pathVariables, String name, Argument<?> argument,
                                      @Nullable String defaultValue, boolean encoded) {
        Optional<String> value = pathVariables.findString(name);
        Class<?> type = argument.getType();
        if (type == PathSegment.class) {
            return value.map(v -> JaxRsMatrixParams.pathSegment(request, v, encoded)).orElse(null);
        }
        if (type == List.class && argument.getFirstTypeVariable().map(Argument::getType).orElse(null) == PathSegment.class) {
            // a variable over several segments
            return value.map(v -> Arrays.stream(v.split("/")).map(segment -> JaxRsMatrixParams.pathSegment(request, segment, encoded)).toList())
                .orElse(List.of());
        }
        if (encoded) {
            value = value.map(JaxRsRouteSupport::encode);
        }
        return convert(value.map(List::of).orElse(List.of()), argument, defaultValue, true);
    }

    /**
     * @param request      The request
     * @param name         The name of the query parameter
     * @param argument     The type of the parameter
     * @param defaultValue The {@code @DefaultValue}
     * @return The value, converted
     */
    public @Nullable Object queryParam(HttpRequest<?> request, String name, Argument<?> argument,
                                       @Nullable String defaultValue, boolean encoded) {
        List<String> values = encoded ? rawQueryValues(request, name) : request.getParameters().getAll(name);
        return convert(values, argument, defaultValue, true);
    }

    /**
     * @param request      The request
     * @param name         The name of the matrix parameter, of the last segment of the path
     * @param argument     The type of the parameter
     * @param defaultValue The {@code @DefaultValue}
     * @return The value, converted
     */
    public @Nullable Object matrixParam(HttpRequest<?> request, String name, Argument<?> argument,
                                        @Nullable String defaultValue, boolean encoded) {
        return convert(JaxRsMatrixParams.values(request, name, encoded), argument, defaultValue, true);
    }

    /**
     * @param request      The request
     * @param name         The name of the header
     * @param argument     The type of the parameter
     * @param defaultValue The {@code @DefaultValue}
     * @return The value, converted
     */
    public @Nullable Object headerParam(HttpRequest<?> request, String name, Argument<?> argument, @Nullable String defaultValue) {
        return convert(request.getHeaders().getAll(name), argument, defaultValue, false);
    }

    /**
     * @param request      The request
     * @param name         The name of the cookie
     * @param argument     The type of the parameter
     * @param defaultValue The {@code @DefaultValue}
     * @return The value, converted
     */
    public @Nullable Object cookieParam(HttpRequest<?> request, String name, Argument<?> argument, @Nullable String defaultValue) {
        Optional<io.micronaut.http.cookie.Cookie> cookie = request.getCookies().findCookie(name);
        if (argument.getType() == Cookie.class) {
            if (cookie.isPresent()) {
                io.micronaut.http.cookie.Cookie c = cookie.get();
                return new Cookie.Builder(c.getName()).value(c.getValue()).path(c.getPath()).domain(c.getDomain()).build();
            }
            return defaultValue == null ? null : new Cookie.Builder(name).value(defaultValue).build();
        }
        return convert(cookie.map(c -> List.of(c.getValue())).orElse(List.of()), argument, defaultValue, false);
    }

    /**
     * @param form         The form
     * @param name         The name of the field
     * @param argument     The type of the parameter
     * @param defaultValue The {@code @DefaultValue}
     * @return The value, converted
     */
    public @Nullable Object formParam(HttpRequest<?> request, @Nullable FormData form, String name, Argument<?> argument,
                                      @Nullable String defaultValue, boolean encoded) {
        if (form == null) {
            // no form read by the route
            return queryParam(request, name, argument, defaultValue, encoded);
        }
        List<String> values = form.getValues(name);
        if (encoded) {
            // form encoding: a space is a plus
            values = values.stream().map(value -> java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)).toList();
        }
        return convert(values, argument, defaultValue, false);
    }

    /**
     * The form as the entity of a resource method that also reads {@code @FormParam}s.
     *
     * @param form     The form
     * @param argument The type of the entity, a {@link MultivaluedMap} or a {@link Form}
     * @return The entity
     */
    public Object formEntity(FormData form, Argument<?> argument) {
        MultivaluedMap<String, String> map = new MultivaluedHashMap<>();
        for (String name : form.names()) {
            map.put(name, new ArrayList<>(form.getValues(name)));
        }
        return argument.getType() == Form.class ? new Form(map) : map;
    }

    /**
     * @param request  The request
     * @param body     The entity read by the route, {@code null} for an empty body
     * @param argument The type of the entity
     * @return The entity
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public @Nullable Object entity(HttpRequest<?> request, @Nullable Object body, Argument<?> argument) {
        if (body != null) {
            return body;
        }
        if (argument.getType() == String.class) {
            return "";
        }
        // a JAX-RS reader reads an empty entity too
        MediaType contentType = request.getContentType().orElse(null);
        Optional<MessageBodyReader<Object>> reader = beanContext.getBean(MessageBodyHandlerRegistry.class)
            .findReader((Argument) argument, contentType);
        if (reader.isPresent() && reader.get() instanceof JaxRsMessageBodyReader<?>) {
            return reader.get().read((Argument) argument, contentType, request.getHeaders(), InputStream.nullInputStream());
        }
        return null;
    }

    /**
     * A {@code @Context} parameter, bound like for a controller: by the argument binder of its type,
     * or else as a bean.
     *
     * @param request  The request
     * @param argument The type of the parameter
     * @param named    The {@code @Named} qualifier of a bean, or {@code null}
     * @return The value
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public @Nullable Object context(HttpRequest<?> request, Argument<?> argument, @Nullable String named) {
        Class<?> type = argument.getType();
        if (type.isInstance(request)) {
            return request;
        }
        Optional<ArgumentBinder<Object, HttpRequest<?>>> binder = contextBinders.computeIfAbsent(argument,
            a -> (Optional) binderRegistry.findArgumentBinder((Argument) a));
        if (binder.isPresent()) {
            ArgumentConversionContext context = ConversionContext.of(argument);
            ArgumentBinder.BindingResult<?> result = binder.get().bind(context, request);
            if (result.isPresentAndSatisfied()) {
                return result.get();
            }
        }
        return beanContext.findBean(type, named == null ? null : Qualifiers.byName(named)).orElse(null);
    }

    /**
     * @return The response of a {@code void} resource method
     */
    public HttpResponse<?> noContent() {
        return HttpResponse.noContent();
    }

    /**
     * @param response The JAX-RS response returned by a resource method
     * @return The response
     */
    public HttpResponse<?> jaxRsResponse(@Nullable Response response) {
        if (response == null) {
            return HttpResponse.noContent();
        }
        if (response instanceof JaxRsMutableResponse mutableResponse) {
            // created by the runtime delegate of the module
            return mutableResponse.getResponse();
        }
        // a Response subclass of the application: only its public API is known
        MutableHttpResponse<Object> httpResponse = HttpResponse.status(response.getStatus(), response.getStatusInfo().getReasonPhrase());
        response.getMetadata().forEach((name, values) -> {
            for (Object value : values) {
                httpResponse.getHeaders().add(name, String.valueOf(value));
            }
        });
        if (response.hasEntity()) {
            httpResponse.body(response.getEntity());
        }
        return httpResponse;
    }

    /**
     * @param response The response returned by a resource method
     * @return The response
     */
    public HttpResponse<?> httpResponse(@Nullable HttpResponse<?> response) {
        return response == null ? HttpResponse.noContent() : response;
    }

    /**
     * @param entity The entity returned by a resource method
     * @return The response
     */
    public HttpResponse<?> entityResponse(@Nullable Object entity) {
        return entity == null ? HttpResponse.noContent() : HttpResponse.ok(entity);
    }

    /**
     * @param entity     The entity returned by a resource method
     * @param returnType The declared type, with its type arguments, which selects the message body
     *                   writer
     * @return The response
     */
    @SuppressWarnings("unchecked")
    public HttpResponse<?> genericEntityResponse(@Nullable Object entity, Argument<?> returnType) {
        return entity == null
            ? HttpResponse.noContent()
            : HttpResponse.ok(new JaxRsGenericEntity<>(entity, (Argument<Object>) returnType, null, null));
    }

    /**
     * The response of a resource method declared to return {@code Object}: what it is is known
     * only at runtime.
     *
     * @param request    The request
     * @param result     The result
     * @param returnType The declared type
     * @return The response
     */
    public HttpResponse<?> anyResponse(HttpRequest<?> request, @Nullable Object result, Argument<?> returnType) {
        if (result instanceof Response response) {
            return jaxRsResponse(response);
        }
        if (result instanceof HttpResponse<?> response) {
            return response;
        }
        return entityResponse(result);
    }

    /**
     * A resource the request is matched to, for {@link jakarta.ws.rs.core.UriInfo#getMatchedResources()}
     * and {@link jakarta.ws.rs.core.UriInfo#getMatchedURIs()}.
     *
     * @param request  The request
     * @param resource The resource
     * @param segments The number of path segments matched up to the resource
     * @param <T>      The type
     * @return The resource
     */
    public <T> T matched(HttpRequest<?> request, T resource, int segments) {
        JaxRsMatched.add(request, resource, segments);
        return resource;
    }

    /**
     * The result of a sub-resource locator.
     *
     * @param located The sub-resource, or its class
     * @param <T>     The type
     * @return The sub-resource
     */
    public <T> T located(@Nullable T located) {
        if (located == null) {
            throw new NotFoundException();
        }
        return located;
    }

    /**
     * A {@code @BeanParam}: an introspected type, instantiated and filled with the values of the
     * request its constructor arguments and properties are annotated with.
     *
     * @param type          The type
     * @param request       The request
     * @param pathVariables The path variables
     * @param form          The form, if the route reads one
     * @return The bean parameter
     */
    public Object beanParam(Class<?> type, HttpRequest<?> request, PathVariables pathVariables, @Nullable FormData form) {
        return beanParams.computeIfAbsent(type, this::beanParamBinder).bind(request, pathVariables, form);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private BeanParamBinder beanParamBinder(Class<?> type) {
        BeanIntrospection<Object> introspection = (BeanIntrospection<Object>) BeanIntrospector.SHARED.findIntrospection(type)
            .orElseThrow(() -> new IllegalStateException("The @BeanParam type " + type.getName() + " is not introspected"));
        boolean encoded = introspection.hasAnnotation(Encoded.class);
        Argument<?>[] constructorArguments = introspection.getConstructorArguments();
        ValueReader[] constructorReaders = new ValueReader[constructorArguments.length];
        for (int i = 0; i < constructorArguments.length; i++) {
            ValueReader reader = reader(constructorArguments[i].getAnnotationMetadata(), constructorArguments[i], encoded);
            constructorReaders[i] = reader == null ? (r, p, f) -> null : reader;
        }
        List<Map.Entry<BeanProperty<Object, Object>, ValueReader>> properties = new ArrayList<>();
        for (BeanProperty<Object, Object> property : introspection.getBeanProperties()) {
            if (property.isReadOnly()) {
                continue;
            }
            ValueReader reader = reader(property.getAnnotationMetadata(), property.asArgument(), encoded);
            if (reader != null) {
                properties.add(Map.entry(property, reader));
            }
        }
        return (request, pathVariables, form) -> {
            Object[] arguments = new Object[constructorReaders.length];
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = constructorReaders[i].read(request, pathVariables, form);
            }
            Object instance = arguments.length == 0 ? introspection.instantiate() : introspection.instantiate(arguments);
            for (Map.Entry<BeanProperty<Object, Object>, ValueReader> property : properties) {
                property.getKey().set(instance, property.getValue().read(request, pathVariables, form));
            }
            return instance;
        };
    }

    /**
     * How to read the value of an annotated constructor argument or property of a bean parameter.
     */
    private @Nullable ValueReader reader(AnnotationMetadata metadata, Argument<?> argument, boolean encodedType) {
        String defaultValue = metadata.stringValue(DefaultValue.class).orElse(null);
        boolean encoded = encodedType || metadata.hasAnnotation(Encoded.class);
        Optional<String> name;
        if ((name = metadata.stringValue(PathParam.class)).isPresent()) {
            String n = name.get();
            return (request, pathVariables, form) -> pathParam(request, pathVariables, n, argument, defaultValue, encoded);
        } else if ((name = metadata.stringValue(QueryParam.class)).isPresent()) {
            String n = name.get();
            return (request, pathVariables, form) -> queryParam(request, n, argument, defaultValue, encoded);
        } else if ((name = metadata.stringValue(MatrixParam.class)).isPresent()) {
            String n = name.get();
            return (request, pathVariables, form) -> matrixParam(request, n, argument, defaultValue, encoded);
        } else if ((name = metadata.stringValue(HeaderParam.class)).isPresent()) {
            String n = name.get();
            return (request, pathVariables, form) -> headerParam(request, n, argument, defaultValue);
        } else if ((name = metadata.stringValue(CookieParam.class)).isPresent()) {
            String n = name.get();
            return (request, pathVariables, form) -> cookieParam(request, n, argument, defaultValue);
        } else if ((name = metadata.stringValue(FormParam.class)).isPresent()) {
            String n = name.get();
            return (request, pathVariables, form) -> formParam(request, form, n, argument, defaultValue, encoded);
        } else if (metadata.hasAnnotation(BeanParam.class)) {
            Class<?> type = argument.getType();
            return (request, pathVariables, form) -> beanParam(type, request, pathVariables, form);
        } else if (metadata.hasAnnotation(jakarta.ws.rs.core.Context.class)) {
            String named = metadata.stringValue("jakarta.inject.Named").orElse(null);
            return (request, pathVariables, form) -> context(request, argument, named);
        }
        return null;
    }

    /**
     * Reads a value of the request.
     */
    @FunctionalInterface
    private interface ValueReader {
        @Nullable Object read(HttpRequest<?> request, PathVariables pathVariables, @Nullable FormData form);
    }

    /**
     * Creates a bean parameter.
     */
    @FunctionalInterface
    private interface BeanParamBinder {
        Object bind(HttpRequest<?> request, PathVariables pathVariables, @Nullable FormData form);
    }

    /**
     * Set a field of a type created per request that the generated router cannot access.
     *
     * @param instance      The instance
     * @param declaringType The class that declares the field
     * @param name          The name of the field
     * @param value         The value
     */
    public void setField(Object instance, Class<?> declaringType, String name, @Nullable Object value) {
        java.lang.reflect.Field field = fields.computeIfAbsent(declaringType.getName() + '#' + name, key -> {
            java.lang.reflect.Field f = io.micronaut.core.reflect.ReflectionUtils.getRequiredField(declaringType, name);
            f.setAccessible(true);
            return f;
        });
        io.micronaut.core.reflect.ReflectionUtils.setField(field, instance, value);
    }

    /**
     * Call a setter of a type created per request that the generated router cannot access.
     *
     * @param instance      The instance
     * @param declaringType The class that declares the setter
     * @param name          The name of the setter
     * @param parameterType The type of its parameter
     * @param value         The value
     */
    public void invokeSetter(Object instance, Class<?> declaringType, String name, Class<?> parameterType, @Nullable Object value) {
        java.lang.reflect.Method setter = io.micronaut.core.reflect.ReflectionUtils.getRequiredMethod(declaringType, name, parameterType);
        io.micronaut.core.reflect.ReflectionUtils.invokeMethod(instance, setter, value);
    }

    /**
     * Create a resource for a request: a resource whose constructor reads values of the request
     * is a prototype, and gets them as its {@code @Parameter}s.
     *
     * @param type   The resource class
     * @param names  The names of the constructor parameters read from the request
     * @param values Their values
     * @param <T>    The type of the resource
     * @return The resource
     */
    public <T> T create(Class<T> type, String[] names, @Nullable Object[] values) {
        if (!beanContext.containsBean(type)) {
            // a sub-resource or a bean parameter that is not a bean
            return io.micronaut.core.reflect.InstantiationUtils.instantiate(type);
        }
        Map<String, Object> arguments = new java.util.HashMap<>(names.length * 2);
        for (int i = 0; i < names.length; i++) {
            arguments.put(names[i], values[i]);
        }
        return beanContext.createBean(type, arguments);
    }

    /**
     * Rethrow what a resource method throws, unchanged, from a route handler.
     *
     * @param throwable What the resource method threw
     * @return Never returns
     */
    public static RuntimeException rethrow(Throwable throwable) {
        return ExceptionUtils.sneakyThrow(throwable);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static List<String> rawQueryValues(HttpRequest<?> request, String name) {
        String query = request.getUri().getRawQuery();
        if (query == null || query.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(1);
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String key = java.net.URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), java.nio.charset.StandardCharsets.UTF_8);
            if (key.equals(name)) {
                values.add(equals < 0 ? "" : pair.substring(equals + 1));
            }
        }
        return values;
    }

    private @Nullable Object convert(List<String> values, Argument<?> argument, @Nullable String defaultValue, boolean notFound) {
        Class<?> type = argument.getType();
        if (type == List.class || type == Set.class || type == SortedSet.class || type == Collection.class) {
            Argument<?> elementType = argument.getFirstTypeVariable().orElse(Argument.STRING);
            List<String> elements = values.isEmpty() && defaultValue != null ? List.of(defaultValue) : values;
            Collection<Object> result = type == Set.class ? new LinkedHashSet<>() : type == SortedSet.class ? new TreeSet<>() : new ArrayList<>();
            for (String element : elements) {
                result.add(convertOne(element, elementType, notFound));
            }
            return type == List.class || type == Collection.class ? List.copyOf(result) : result;
        }
        if (type.isArray()) {
            Argument<?> componentType = Argument.of(type.getComponentType());
            List<String> elements = values.isEmpty() && defaultValue != null ? List.of(defaultValue) : values;
            Object array = java.lang.reflect.Array.newInstance(type.getComponentType(), elements.size());
            for (int i = 0; i < elements.size(); i++) {
                java.lang.reflect.Array.set(array, i, convertOne(elements.get(i), componentType, notFound));
            }
            return array;
        }
        String value = values.isEmpty() ? defaultValue : values.get(0);
        if (value == null) {
            return missing(argument);
        }
        return convertOne(value, argument, notFound);
    }

    private @Nullable Object missing(Argument<?> argument) {
        Class<?> type = argument.getType();
        if (type == Optional.class) {
            return Optional.empty();
        }
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return conversionService.convertRequired(0, type);
    }

    private @Nullable Object convertOne(String value, Argument<?> argument, boolean notFound) {
        StringConverter converter = converters.computeIfAbsent(argument, this::converter);
        Object converted;
        try {
            converted = converter.convert(value);
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            throw notFound ? new NotFoundException(e) : new BadRequestException(e);
        }
        if (converted == null) {
            IllegalArgumentException cause = new IllegalArgumentException("Cannot convert [" + value + "] to " + argument.getTypeName());
            throw notFound ? new NotFoundException(cause) : new BadRequestException(cause);
        }
        return converted;
    }

    /**
     * How a parameter of a type is converted from a string, resolved once: by a
     * {@link ParamConverterProvider}, else by the rules of JAX-RS for the type (a static
     * {@code fromString} or {@code valueOf} method, or a constructor with a string), else by the
     * conversion service.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private StringConverter converter(Argument<?> argument) {
        Class<?> type = argument.getType();
        Annotation[] annotations = argument.synthesizeAll();
        for (ParamConverterProvider provider : paramConverterProviders) {
            ParamConverter paramConverter = provider.getConverter(type, argument.asType(), annotations);
            if (paramConverter != null) {
                return paramConverter::fromString;
            }
        }
        if (type == String.class) {
            return value -> value;
        }
        if (!type.isPrimitive() && !type.isArray() && !io.micronaut.core.reflect.ClassUtils.isJavaLangType(type)) {
            // valueOf before fromString, except for an enum
            for (String factory : type.isEnum() ? new String[]{"fromString", "valueOf"} : new String[]{"valueOf", "fromString"}) {
                java.lang.reflect.Method method = io.micronaut.core.reflect.ReflectionUtils.findMethod(type, factory, String.class).orElse(null);
                if (method != null && java.lang.reflect.Modifier.isStatic(method.getModifiers()) && type.isAssignableFrom(method.getReturnType())) {
                    return value -> invoke(() -> method.invoke(null, value));
                }
            }
            java.lang.reflect.Constructor<?> constructor = io.micronaut.core.reflect.ReflectionUtils.findConstructor(type, String.class).orElse(null);
            if (constructor != null) {
                return value -> invoke(() -> constructor.newInstance(value));
            }
        }
        return value -> conversionService.convert(value, argument).orElse(null);
    }

    private static Object invoke(java.util.concurrent.Callable<Object> call) throws Exception {
        try {
            return call.call();
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause instanceof Exception exception ? exception : e;
        }
    }

    /**
     * Converts the string value of a parameter.
     */
    @FunctionalInterface
    private interface StringConverter {
        @Nullable Object convert(String value) throws Exception;
    }

    private static MediaType[] mediaTypes(String[] values) {
        MediaType[] mediaTypes = new MediaType[values.length];
        for (int i = 0; i < values.length; i++) {
            mediaTypes[i] = MediaType.of(values[i]);
        }
        return mediaTypes;
    }

    /**
     * What the generated code knows about a resource method at compile time.
     */
    public static final class RouteMetadata {
        private final Class<?> resourceClass;
        private final String methodName;
        private final Class<?>[] parameterTypes;
        private final String[] produces;
        private final String[] consumes;
        private final Class<?> rootClass;
        private volatile java.lang.reflect.@Nullable Method method;

        /**
         * @param resourceClass  The resource class
         * @param methodName     The name of the method
         * @param parameterTypes The parameter types of the method
         * @param produces       Its {@code @Produces} media types, of the method or else of the class
         * @param consumes       Its {@code @Consumes} media types, of the method or else of the class
         * @param rootClass      The root resource class, which has the method or its sub-resource locators
         */
        public RouteMetadata(Class<?> resourceClass, String methodName, Class<?>[] parameterTypes,
                             String[] produces, String[] consumes, Class<?> rootClass) {
            this.resourceClass = resourceClass;
            this.methodName = methodName;
            this.parameterTypes = parameterTypes;
            this.produces = produces;
            this.consumes = consumes;
            this.rootClass = rootClass;
        }

        /**
         * @return The resource class
         */
        public Class<?> resourceClass() {
            return resourceClass;
        }

        /**
         * @return The resource method, looked up the first time it is asked for
         */
        public java.lang.reflect.Method resourceMethod() {
            java.lang.reflect.Method m = method;
            if (m == null) {
                m = io.micronaut.core.reflect.ReflectionUtils.getRequiredMethod(resourceClass, methodName, parameterTypes);
                method = m;
            }
            return m;
        }
    }
}
