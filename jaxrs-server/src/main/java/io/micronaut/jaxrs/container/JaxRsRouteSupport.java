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
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.bind.ArgumentBinder;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.ExceptionUtils;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.bind.RequestBinderRegistry;
import io.micronaut.http.uri.UriTemplate;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.jaxrs.common.JaxRsGenericEntity;
import io.micronaut.jaxrs.common.JaxRsMutableResponse;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.web.router.FormData;
import io.micronaut.web.router.PathVariables;
import io.micronaut.web.router.RouteDeclaration;
import io.micronaut.web.router.UriRoute;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CompletionException;
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

    private static final Object NO_CONVERTER = new Object();

    private final ApplicationProvider applicationProvider;
    private final String applicationPath;
    private final ConversionService conversionService;
    private final List<ParamConverterProvider> paramConverterProviders;
    private final RequestBinderRegistry binderRegistry;
    private final Map<Argument<?>, Optional<ArgumentBinder<Object, HttpRequest<?>>>> contextBinders = new ConcurrentHashMap<>();
    private final BeanContext beanContext;
    private final Map<Argument<?>, Object> paramConverters = new ConcurrentHashMap<>();
    private final Map<Object, RouteMetadata> handlers = new ConcurrentHashMap<>();

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
     * The declaration of a route, under the {@code @ApplicationPath} of the application. The
     * context path is applied by the router.
     *
     * @param declaration The declaration generated for a resource method
     * @return The declaration of the route
     */
    public RouteDeclaration declaration(RouteDeclaration declaration) {
        if (applicationPath.isEmpty() || "/".equals(applicationPath)) {
            return declaration;
        }
        String prefix = applicationPath.charAt(0) == '/' ? applicationPath : '/' + applicationPath;
        return RouteDeclaration.of(declaration.httpMethod(), UriTemplate.of(prefix).nest(declaration.uriTemplate()).toString());
    }

    /**
     * Configure a route: its media types, the annotations of the resource method, and a blocking
     * executor for a handler that waits. With the annotations of the resource method, the route
     * is seen like a controller route by the container filters and their name bindings, the
     * security rules and the message body writers.
     *
     * @param route    The route
     * @param metadata The metadata of the resource method
     * @param blocking Whether the handler waits for an asynchronous result
     */
    public void configure(UriRoute route, RouteMetadata metadata, boolean blocking) {
        if (metadata.consumes.length == 0) {
            route.consumesAll();
        } else {
            route.consumes(mediaTypes(metadata.consumes));
        }
        route.produces(metadata.produces.length == 0 ? new MediaType[]{MediaType.ALL_TYPE} : mediaTypes(metadata.produces));
        beanContext.findBeanDefinition(metadata.resourceClass)
            .flatMap(definition -> definition.findMethod(metadata.methodName, metadata.parameterTypes))
            .ifPresent(method -> route.annotationMetadata(method.getAnnotationMetadata()));
        if (blocking) {
            route.executeOn(TaskExecutors.BLOCKING);
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
    public @Nullable Object pathParam(HttpRequest<?> request, PathVariables pathVariables, String name, Argument<?> argument, @Nullable String defaultValue) {
        Optional<String> value = pathVariables.findString(name);
        return convert(value.map(List::of).orElse(List.of()), argument, defaultValue, true);
    }

    /**
     * @param request      The request
     * @param name         The name of the query parameter
     * @param argument     The type of the parameter
     * @param defaultValue The {@code @DefaultValue}
     * @return The value, converted
     */
    public @Nullable Object queryParam(HttpRequest<?> request, String name, Argument<?> argument, @Nullable String defaultValue) {
        return convert(request.getParameters().getAll(name), argument, defaultValue, true);
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
    public @Nullable Object formParam(FormData form, String name, Argument<?> argument, @Nullable String defaultValue) {
        return convert(form.getValues(name), argument, defaultValue, false);
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
     * @param body     The entity read by the route
     * @param argument The type of the entity
     * @return The entity
     */
    public @Nullable Object entity(@Nullable Object body, Argument<?> argument) {
        return body;
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
     * The response of a resource method.
     *
     * @param request    The request
     * @param result     The result of the method, {@code null} for a {@code void} method
     * @param returnType The declared type of the result
     * @param metadata   The metadata of the resource method
     * @return The response
     */
    @SuppressWarnings("unchecked")
    public HttpResponse<?> response(HttpRequest<?> request, @Nullable Object result, Argument<?> returnType, RouteMetadata metadata) {
        if (result instanceof JaxRsMutableResponse response) {
            return response.getResponse();
        }
        if (result instanceof HttpResponse<?> response) {
            return response;
        }
        if (result instanceof Response response) {
            throw new IllegalStateException("Unsupported response implementation: " + response.getClass().getName());
        }
        if (result == null) {
            return HttpResponse.noContent();
        }
        // the declared type, with its type arguments, selects the message body writer
        return HttpResponse.ok(returnType.getTypeParameters().length == 0
            ? result
            : new JaxRsGenericEntity<>(result, (Argument<Object>) returnType, null, null));
    }

    /**
     * The response of a resource method that completes later.
     *
     * @param request    The request
     * @param result     The stage returned by the method
     * @param returnType The declared type of its value
     * @param metadata   The metadata of the resource method
     * @return The response
     */
    public CompletionStage<HttpResponse<?>> responseAsync(HttpRequest<?> request, CompletionStage<?> result, Argument<?> returnType, RouteMetadata metadata) {
        return result.thenApply(value -> response(request, value, returnType, metadata));
    }

    /**
     * The response of a resource method that completes later, waited for: used when the route
     * also reads an entity or a form, and runs on a blocking executor.
     *
     * @param request    The request
     * @param result     The stage returned by the method
     * @param returnType The declared type of its value
     * @param metadata   The metadata of the resource method
     * @return The response
     */
    public HttpResponse<?> responseAwait(HttpRequest<?> request, CompletionStage<?> result, Argument<?> returnType, RouteMetadata metadata) {
        try {
            return response(request, result.toCompletableFuture().join(), returnType, metadata);
        } catch (CompletionException e) {
            return ExceptionUtils.sneakyThrow(e.getCause() == null ? e : e.getCause());
        }
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private @Nullable Object convertOne(String value, Argument<?> argument, boolean notFound) {
        Object converter = paramConverters.computeIfAbsent(argument, this::paramConverter);
        try {
            if (converter instanceof ParamConverter paramConverter) {
                return paramConverter.fromString(value);
            }
            if (argument.getType() == String.class) {
                return value;
            }
            Optional<?> converted = conversionService.convert(value, argument);
            if (converted.isPresent()) {
                return converted.get();
            }
        } catch (WebApplicationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw notFound ? new NotFoundException(e) : new BadRequestException(e);
        }
        IllegalArgumentException cause = new IllegalArgumentException("Cannot convert [" + value + "] to " + argument.getTypeName());
        throw notFound ? new NotFoundException(cause) : new BadRequestException(cause);
    }

    private Object paramConverter(Argument<?> argument) {
        Annotation[] annotations = argument.synthesizeAll();
        for (ParamConverterProvider provider : paramConverterProviders) {
            ParamConverter<?> converter = provider.getConverter(argument.getType(), argument.asType(), annotations);
            if (converter != null) {
                return converter;
            }
        }
        return NO_CONVERTER;
    }

    private static MediaType[] mediaTypes(String[] values) {
        MediaType[] mediaTypes = new MediaType[values.length];
        for (int i = 0; i < values.length; i++) {
            mediaTypes[i] = MediaType.of(values[i]);
        }
        return mediaTypes;
    }

    /**
     * Register the handler function of a resource method, so that the route matched by a request
     * leads back to the resource method, for {@link jakarta.ws.rs.container.ResourceInfo}.
     *
     * @param metadata The metadata of the resource method
     * @param handler  The handler function
     * @param <H>      The type of the handler
     * @return The handler
     */
    public <H> H handler(RouteMetadata metadata, H handler) {
        handlers.put(handler, metadata);
        return handler;
    }

    /**
     * @param handler The target of a matched route
     * @return The metadata of the resource method it calls, or {@code null} if it is not a
     * generated handler
     */
    @Nullable RouteMetadata metadata(@Nullable Object handler) {
        return handler == null ? null : handlers.get(handler);
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
        private volatile java.lang.reflect.@Nullable Method method;

        /**
         * @param resourceClass  The resource class
         * @param methodName     The name of the method
         * @param parameterTypes The parameter types of the method
         * @param produces       Its {@code @Produces} media types, of the method or else of the class
         * @param consumes       Its {@code @Consumes} media types, of the method or else of the class
         */
        public RouteMetadata(Class<?> resourceClass, String methodName, Class<?>[] parameterTypes,
                             String[] produces, String[] consumes) {
            this.resourceClass = resourceClass;
            this.methodName = methodName;
            this.parameterTypes = parameterTypes;
            this.produces = produces;
            this.consumes = consumes;
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
