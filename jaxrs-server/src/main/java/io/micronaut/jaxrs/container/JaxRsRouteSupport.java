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

import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationMetadataProvider;
import io.micronaut.core.annotation.Internal;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.RouteInfo;
import io.micronaut.web.router.MethodBasedRouteInfo;
import io.micronaut.http.simple.SimpleHttpHeaders;
import io.micronaut.jaxrs.common.JaxRsUtils;
import io.micronaut.http.body.AsyncRequestBody;
import io.micronaut.http.body.FormOptions;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanMethod;
import io.micronaut.inject.annotation.AnnotationMetadataHierarchy;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.bind.ArgumentBinder;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.codec.CodecException;
import io.micronaut.http.MediaType;
import io.micronaut.http.form.FileUpload;
import io.micronaut.jaxrs.common.multipart.JaxRsEntityPart;
import io.micronaut.http.bind.RequestBinderRegistry;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.jaxrs.common.JaxRsGenericEntity;
import jakarta.ws.rs.core.GenericEntity;
import io.micronaut.jaxrs.common.JaxRsArgumentUtil;
import io.micronaut.jaxrs.common.JaxRsContainerMessageBodyHandlerRegistry;
import io.micronaut.context.BeanRegistration;
import io.micronaut.core.util.SupplierUtil;
import java.util.function.Supplier;
import io.micronaut.jaxrs.common.JaxRsMutableResponse;
import io.micronaut.http.form.FormData;
import io.micronaut.web.router.builder.HttpRouteBuilder;
import io.micronaut.http.uri.RouteTemplate;
import io.micronaut.http.PathVariables;
import io.micronaut.web.router.builder.HttpRouteGroup;
import io.micronaut.web.router.builder.LocatedHttpRouteBuilder;
import io.micronaut.web.router.builder.LocatedHttpRouteGroup;
import io.micronaut.web.router.builder.LocatedRoutes;
import io.micronaut.web.router.builder.RouteSpec;
import io.micronaut.web.router.spi.RouteMatchSelector;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.NotAcceptableException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.sse.SseEventSink;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NoContentException;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The support of the runtime routes of JAX-RS resources, see {@link JaxRsRuntimeRoutes}: their
 * handlers read the parameters of a resource method with it, and convert the result of the method
 * to a response.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
@Singleton
public final class JaxRsRouteSupport {

    /**
     * The body of a route with an entity: its bytes, {@code null} without a body. The route reads
     * the entity with the JAX-RS readers, see {@link #entity(HttpRequest, byte[], Argument)}.
     */
    public static final Argument<byte[]> ENTITY = HttpRouteBuilder.nullableBody(Argument.of(byte[].class));

    /**
     * The attribute of the request that holds its event sink.
     */
    static final String SSE_EVENT_SINK = SseEventSink.class.getName();
    // set when the url-encoded form of the request is read with its raw values, for @Encoded form parameters
    private static final String RAW_FORM = JaxRsRouteSupport.class.getName() + ".rawForm";

    // the types of header values with a header delegate, a subtype first (NewCookie of Cookie)
    private static final List<Class<?>> HEADER_TYPES = List.of(
        jakarta.ws.rs.core.MediaType.class, jakarta.ws.rs.core.NewCookie.class, jakarta.ws.rs.core.Cookie.class,
        jakarta.ws.rs.core.EntityTag.class, jakarta.ws.rs.core.Link.class, jakarta.ws.rs.core.CacheControl.class);
    private static final java.time.format.DateTimeFormatter HTTP_DATE =
        java.time.format.DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US);

    // the servlet request types of a @Context value, bound to a stub when the server is not a
    // servlet container
    private static final Set<String> SERVLET_REQUEST_TYPES = Set.of("jakarta.servlet.ServletRequest", "jakarta.servlet.http.HttpServletRequest");

    // the attribute of the request that holds its stub servlet request
    private static final String STUB_SERVLET_REQUEST = JaxRsRouteSupport.class.getName() + ".servletRequest";

    private final ApplicationProvider applicationProvider;

    private final String applicationPath;

    private final ConversionService conversionService;

    private final Supplier<List<ParamConverterProvider>> paramConverterProviders;

    private final RequestBinderRegistry binderRegistry;

    private final Map<Argument<?>, Optional<ArgumentBinder<Object, HttpRequest<?>>>> contextBinders = new ConcurrentHashMap<>();

    private final BeanContext beanContext;

    private final Map<ConverterKey, StringConverter> converters = new ConcurrentHashMap<>();

    private final Map<Class<?>, BeanParamBinder> beanParams = new ConcurrentHashMap<>();

    private final JaxRsRouteTemplateEngine templates = new JaxRsRouteTemplateEngine();

    private volatile @Nullable JaxRsMessageBodyReaders<?> readers;

    private volatile @Nullable JaxRsContainerFilters containerFilters;

    private volatile @Nullable Set<Class<?>> registeredClasses;

    JaxRsRouteSupport(ApplicationProvider applicationProvider,
                      ConversionService conversionService,
                      RequestBinderRegistry binderRegistry,
                      BeanContext beanContext) {
        this.applicationProvider = applicationProvider;
        this.applicationPath = applicationProvider.getAnnotationMetadata().stringValue(jakarta.ws.rs.ApplicationPath.class)
            .map(path -> java.net.URLDecoder.decode(path, java.nio.charset.StandardCharsets.UTF_8))
            .orElse("");
        this.conversionService = conversionService;
        // the registered ones (JAX-RS 2.3), resolved when first used: after the instances of the
        // application replaced the beans of their classes, see JaxRsApplicationSingletons
        this.paramConverterProviders = SupplierUtil.memoized(() -> {
            JaxRsContainerMessageBodyHandlerRegistry registry = beanContext.getBean(JaxRsContainerMessageBodyHandlerRegistry.class);
            return beanContext.getBeanRegistrations(ParamConverterProvider.class).stream()
                .filter(registration -> registry.isRegistered(registration.getBeanDefinition().getBeanType()))
                // the one of the highest priority, the lowest value, first
                .sorted(java.util.Comparator.comparingInt(JaxRsUtils::getPriorityOrder))
                .map(BeanRegistration::getBean)
                .toList();
        });
        this.binderRegistry = binderRegistry;
        this.beanContext = beanContext;
    }

    /**
     * Whether a root resource class is registered with the application: an {@code Application}
     * that lists its classes or singletons registers only those (JAX-RS 2.3), and one that lists
     * none, every resource.
     *
     * @param resourceClass The class
     * @return Whether it is registered
     */
    public boolean isRegistered(Class<?> resourceClass) {
        Set<Class<?>> registered = registeredClasses;
        if (registered == null) {
            Set<Class<?>> classes = new HashSet<>();
            beanContext.findBean(Application.class).ifPresent(application -> {
                classes.addAll(application.getClasses());
                for (Object singleton : application.getSingletons()) {
                    classes.add(singleton.getClass());
                }
            });
            registered = classes;
            registeredClasses = registered;
        }
        return registered.isEmpty() || registered.contains(resourceClass);
    }

    /**
     * Declare routes: with the templates of JAX-RS, selected by JAX-RS.
     *
     * @param routes       The builder
     * @param declarations Declares the routes
     */
    public void routes(HttpRouteBuilder routes, Consumer<HttpRouteGroup> declarations) {
        routes.group(group -> {
            group.templates(templates);
            group.selectedBy(templates);
            declarations.accept(group);
        });
    }

    /**
     * Declare the routes of a located target: with the templates of JAX-RS, selected by JAX-RS.
     *
     * @param routes       The builder
     * @param declarations Declares the routes
     * @param <T>          The type of the target
     */
    public <T> void locatedRoutes(LocatedHttpRouteBuilder<T> routes, Consumer<LocatedHttpRouteGroup<T>> declarations) {
        routes.group(group -> {
            group.templates(templates);
            group.selectedBy(templates);
            declarations.accept(group);
        });
    }

    /**
     * The prefix of a sub-resource locator known only at runtime, in the language of JAX-RS,
     * under the {@code @ApplicationPath} of the application.
     *
     * @param template The joined {@code @Path} values up to the locator
     * @return The prefix
     */
    public String prefix(String template) {
        return underApplicationPath(template);
    }

    /**
     * Whether JAX-RS commits to the root resource class of a sub-resource locator route (section
     * 3.7.2, step 1): no root resource class with a more specific {@code @Path} matches the path.
     *
     * @param request The request
     * @param prefix  The prefix of the locator route, see {@link #prefix}
     * @return Whether the locator is invoked
     */
    public boolean commitsToLocator(HttpRequest<?> request, String prefix) {
        return templates.commitsToLocator(request, prefix);
    }

    /**
     * The template of a route, in the language of JAX-RS, under the {@code @ApplicationPath} of
     * the application. The context path is applied by the router.
     *
     * @param template The joined {@code @Path} values of the resource method
     * @return The template
     */
    public RouteTemplate template(String template) {
        return templates.parse(underApplicationPath(template));
    }

    /**
     * The template of a route of a located target, relative to the prefix of its locator.
     *
     * @param template The {@code @Path} of the resource method
     * @return The template
     */
    public RouteTemplate locatedTemplate(String template) {
        return templates.parse(template);
    }

    /**
     * @return The routes of the class of a target a locator located at runtime: the router keeps
     * a route table per instance
     */
    public Function<Object, LocatedRoutes<?>> locatedRoutes() {
        return target -> routesFor(target.getClass());
    }

    /**
     * The routes of the class of a located target: the routes of its resource methods and
     * locators, collected at runtime, one instance per class.
     *
     * @param type The class of a located target
     * @return The routes
     */
    private LocatedRoutes<?> routesFor(Class<?> type) {
        return beanContext.getBean(JaxRsRuntimeRoutes.class).locatedRoutes(type);
    }

    private String underApplicationPath(String template) {
        if (applicationPath.isEmpty() || "/".equals(applicationPath)) {
            return template;
        }
        String prefix = applicationPath.charAt(0) == '/' ? applicationPath : '/' + applicationPath;
        prefix = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return "/".equals(template) ? prefix : prefix + template;
    }

    /**
     * Configure a route: its media types, and the resource method it implements. With it, the
     * route is seen like a controller route by the container filters and their name bindings,
     * the security rules, {@code ResourceInfo} and the message body writers.
     *
     * @param route    The route
     * @param metadata The metadata of the resource method
     * @param <S>      The type of the route
     * @return The route
     */
    public <S extends RouteSpec<S>> S configure(S route, RouteMetadata metadata) {
        if (metadata.consumes.length == 0) {
            route.consumesAll();
        } else {
            route.consumes(mediaTypes(metadata.consumes));
        }
        route.produces(metadata.produces.length == 0 ? new MediaType[]{MediaType.ALL_TYPE} : mediaTypes(metadata.produces));
        Optional<? extends ExecutableMethod<?, ?>> method = beanContext.findBeanDefinition(metadata.resourceClass)
            .flatMap(definition -> definition.findMethod(metadata.methodName, metadata.parameterTypes));
        if (method.isPresent()) {
            route.annotationMetadata(method.get());
        } else {
            // a sub-resource that is not a bean: the route has the annotations of its method and class
            route.annotationMetadata(new SubResourceMethod(metadata, subResourceAnnotations(metadata)));
        }
        // the post-matching request filters of the resource method (JAX-RS 6.7.2), once the entity
        // they may read is buffered
        return route.beforeReplacingAsync(request -> containerFilters().filterRequestAsync(RouteAttributes.getRouteInfo(request).orElseThrow(), request)).and();
    }

    /**
     * The annotations of the resource method of a sub-resource that is not a bean, with the ones
     * of its class, e.g. its name bindings, from its introspection.
     */
    private static AnnotationMetadata subResourceAnnotations(RouteMetadata metadata) {
        BeanIntrospection<?> introspection = JaxRsReflection.get().introspection(metadata.resourceClass);
        if (introspection == null) {
            return AnnotationMetadata.EMPTY_METADATA;
        }
        for (BeanMethod<?, Object> method : introspection.getBeanMethods()) {
            if (method.getName().equals(metadata.methodName) && Arrays.equals(Argument.toClassArray(method.getArguments()), metadata.parameterTypes)) {
                AnnotationMetadata annotations = method.getAnnotationMetadata();
                return annotations instanceof AnnotationMetadataHierarchy ? annotations
                    : new AnnotationMetadataHierarchy(introspection.getAnnotationMetadata(), annotations);
            }
        }
        return introspection.getAnnotationMetadata();
    }

    private JaxRsContainerFilters containerFilters() {
        JaxRsContainerFilters filters = this.containerFilters;
        if (filters == null) {
            filters = beanContext.getBean(JaxRsContainerFilters.class);
            this.containerFilters = filters;
        }
        return filters;
    }

    /**
     * Read the entity of an asynchronous resource method, then call it.
     *
     * @param request       The request
     * @param pathVariables The path variables
     * @param body          The body of the request
     * @param handler       Calls the resource method with the bytes of the entity
     * @return The response
     */
    public CompletionStage<? extends HttpResponse<?>> entityAsync(HttpRequest<?> request, PathVariables pathVariables,
                                                                  AsyncRequestBody body, JaxRsAsyncHandler<byte[]> handler) {
        return body.body(ENTITY).thenCompose(entity -> call(handler, request, pathVariables, entity));
    }

    /**
     * Read the form of an asynchronous resource method, then call it.
     *
     * @param request       The request
     * @param pathVariables The path variables
     * @param body          The body of the request
     * @param raw           Whether the values of an url-encoded form are read raw, for {@code @Encoded} form parameters
     * @param handler       Calls the resource method with the form
     * @return The response
     */
    public CompletionStage<? extends HttpResponse<?>> formAsync(HttpRequest<?> request, PathVariables pathVariables,
                                                                AsyncRequestBody body, boolean raw, JaxRsAsyncHandler<FormData> handler) {
        CompletionStage<FormData> read;
        if (raw && isUrlEncoded(request)) {
            request.setAttribute(RAW_FORM, Boolean.TRUE);
            read = body.form(FormOptions.defaults().withRawValues());
        } else {
            read = body.form();
        }
        return read
            .thenCompose(form -> readParts(request, form))
            .thenCompose(form -> call(handler, request, pathVariables, form));
    }

    /**
     * Read the files of a multipart form without blocking, before an asynchronous resource method
     * reads them as parts, see {@link #formPart}.
     */
    private static CompletionStage<FormData> readParts(HttpRequest<?> request, FormData form) {
        Map<String, FormPart> parts = formParts(request);
        CompletableFuture<?>[] reads = form.names().stream()
            .flatMap(name -> form.findFile(name).stream().map(upload -> upload.bytes(Integer.MAX_VALUE)
                .thenAccept(content -> {
                    synchronized (parts) {
                        parts.put(name, filePart(upload, content));
                    }
                }).toCompletableFuture()))
            .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(reads).thenApply(ignored -> form);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, FormPart> formParts(HttpRequest<?> request) {
        Map<String, FormPart> parts = request.getAttribute(FormPart.class.getName(), Map.class).orElse(null);
        if (parts == null) {
            parts = new HashMap<>();
            request.setAttribute(FormPart.class.getName(), parts);
        }
        return parts;
    }

    private static FormPart filePart(FileUpload upload, byte[] content) {
        MediaType mediaType = upload.contentType().orElse(MediaType.APPLICATION_OCTET_STREAM_TYPE);
        return new FormPart(upload.fileName(), jakarta.ws.rs.core.MediaType.valueOf(mediaType.toString()), content);
    }

    @SuppressWarnings("unchecked")
    private static <T> CompletionStage<HttpResponse<?>> call(JaxRsAsyncHandler<T> handler, HttpRequest<?> request,
                                                             PathVariables pathVariables, @Nullable T value) {
        try {
            return (CompletionStage<HttpResponse<?>>) handler.handle(request, pathVariables, value);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * @param request       The request
     * @param pathVariables The path variables of the matched route
     * @param name          The name of the path parameter
     * @param argument      The type of the parameter
     * @param defaultValue  The {@code @DefaultValue}
     * @param encoded       Whether the value is kept encoded ({@code @Encoded})
     * @return The value, converted
     */
    public @Nullable Object pathParam(HttpRequest<?> request, PathVariables pathVariables, String name, Argument<?> argument,
                                      @Nullable String defaultValue, boolean encoded) {
        // an @Encoded parameter gets the value as it appears in the path
        PathVariables variables = encoded ? pathVariables.raw() : pathVariables;
        Optional<String> value = variables.findString(name);
        Class<?> type = argument.getType();
        if (type == PathSegment.class) {
            return value.map(v -> JaxRsPathSegments.pathSegment(request, v, encoded)).orElse(null);
        }
        if (type == List.class && argument.getFirstTypeVariable().map(Argument::getType).orElse(null) == PathSegment.class) {
            // a variable over several segments
            return value.map(v -> Arrays.stream(v.split("/")).map(segment -> JaxRsPathSegments.pathSegment(request, segment, encoded)).toList())
                .orElse(List.of());
        }
        List<String> values;
        if (value.isEmpty()) {
            values = List.of();
        } else {
            // a variable that repeats: the values of every occurrence, the last one first
            Optional<String> earlier = variables.findString(JaxRsRouteTemplateEngine.repeated(name, 1));
            if (earlier.isEmpty()) {
                values = List.of(value.get());
            } else {
                values = new ArrayList<>();
                values.add(value.get());
                for (int i = 1; earlier.isPresent(); earlier = variables.findString(JaxRsRouteTemplateEngine.repeated(name, ++i))) {
                    values.add(earlier.get());
                }
            }
        }
        return convert(values, argument, defaultValue, true);
    }

    /**
     * @param request      The request
     * @param name         The name of the query parameter
     * @param argument     The type of the parameter
     * @param defaultValue The {@code @DefaultValue}
     * @param encoded      Whether the value is kept encoded ({@code @Encoded})
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
     * @param encoded      Whether the value is kept encoded ({@code @Encoded})
     * @return The value, converted
     */
    public @Nullable Object matrixParam(HttpRequest<?> request, String name, Argument<?> argument,
                                        @Nullable String defaultValue, boolean encoded) {
        return convert(JaxRsPathSegments.values(request, name, encoded), argument, defaultValue, true);
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
     * @param request      The request
     * @param form         The form
     * @param name         The name of the field
     * @param argument     The type of the parameter
     * @param defaultValue The {@code @DefaultValue}
     * @param encoded      Whether the value is kept encoded ({@code @Encoded})
     * @return The value, converted
     */
    public @Nullable Object formParam(HttpRequest<?> request, @Nullable FormData form, String name, Argument<?> argument,
                                      @Nullable String defaultValue, boolean encoded) {
        if (form == null) {
            // no form read by the route
            return queryParam(request, name, argument, defaultValue, encoded);
        }
        Class<?> type = argument.getType();
        if (type == EntityPart.class || type == InputStream.class) {
            // a part of a multipart form (JAX-RS 3.1 section 3.5.4)
            EntityPart part = formPart(request, form, name);
            return part == null || type == EntityPart.class ? part : part.getContent();
        }
        // the form of a route with an @Encoded form parameter is read with its raw values
        List<String> values = form.getValues(name);
        if (isRawForm(request)) {
            if (!encoded) {
                Charset charset = formCharset(request);
                values = values.stream().map(value -> URLDecoder.decode(value, charset)).toList();
            }
        } else if (encoded) {
            // the decoded values of a form read for parameters that are not @Encoded: form encoding, a space is a plus
            Charset charset = formCharset(request);
            values = values.stream().map(value -> URLEncoder.encode(value, charset)).toList();
        }
        return convert(values, argument, defaultValue, false);
    }

    /**
     * A part of a multipart form: a new {@link EntityPart} each time, as its content is read once,
     * over the content of the field, which is read once per request. The file of an asynchronous
     * resource method was read before it is called; a resource method that blocks reads it here.
     */
    private static @Nullable EntityPart formPart(HttpRequest<?> request, FormData form, String name) {
        Map<String, FormPart> parts = formParts(request);
        FormPart part;
        synchronized (parts) {
            part = parts.get(name);
        }
        if (part == null) {
            Optional<FileUpload> file = form.findFile(name);
            if (file.isPresent()) {
                FileUpload upload = file.get();
                try (InputStream content = upload.takeBody().toInputStream()) {
                    part = filePart(upload, content.readAllBytes());
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            } else {
                List<String> values = form.getValues(name);
                if (values.isEmpty()) {
                    return null;
                }
                part = new FormPart(null, jakarta.ws.rs.core.MediaType.TEXT_PLAIN_TYPE, values.get(0).getBytes(StandardCharsets.UTF_8));
            }
            parts.put(name, part);
        }
        return JaxRsEntityPart.read(name, part.fileName(), part.mediaType(), part.content());
    }

    /**
     * The form of the entity of a resource method that reads its entity and {@code @FormParam}s:
     * parsed once per request, {@code null} if the entity is not an url-encoded form.
     *
     * @param request The request
     * @param body    The entity, {@code null} for an empty body
     * @param raw     Whether the values are kept as they appear, for {@code @Encoded} form parameters
     * @return The form
     */
    public @Nullable FormData entityForm(HttpRequest<?> request, byte @Nullable [] body, boolean raw) {
        MediaType contentType = request.getContentType().orElse(null);
        if (body == null || contentType == null || !MediaType.APPLICATION_FORM_URLENCODED_TYPE.matches(contentType)) {
            return null;
        }
        if (raw) {
            request.setAttribute(RAW_FORM, Boolean.TRUE);
        }
        Optional<JaxRsEntityForm> parsed = request.getAttribute(JaxRsEntityForm.class.getName(), JaxRsEntityForm.class);
        if (parsed.isPresent()) {
            return parsed.get();
        }
        Charset charset = contentType.getCharset().orElse(StandardCharsets.UTF_8);
        JaxRsEntityForm form = new JaxRsEntityForm(new String(body, charset), charset, raw);
        request.setAttribute(JaxRsEntityForm.class.getName(), form);
        return form;
    }

    /**
     * The form as the entity of a resource method that also reads {@code @FormParam}s.
     *
     * @param request  The request
     * @param form     The form
     * @param argument The type of the entity, a {@link MultivaluedMap} or a {@link Form}
     * @return The entity
     */
    public Object formEntity(HttpRequest<?> request, FormData form, Argument<?> argument) {
        MultivaluedMap<String, String> map = new MultivaluedHashMap<>();
        // the entity has the decoded values, also of a form read raw for @Encoded parameters
        Charset charset = isRawForm(request) ? formCharset(request) : null;
        for (String name : form.names()) {
            List<String> values = form.getValues(name);
            map.put(name, charset == null ? new ArrayList<>(values) : new ArrayList<>(values.stream().map(value -> URLDecoder.decode(value, charset)).toList()));
        }
        return argument.getType() == Form.class ? new Form(map) : map;
    }

    /**
     * The entity of a resource method, read by the JAX-RS readers of the application, else by the
     * Micronaut readers, through the reader interceptors (JAX-RS 4.2.1 and 6.3).
     *
     * @param request  The request
     * @param body     The entity, {@code null} for an empty body
     * @param argument The type of the entity
     * @return The entity
     */
    public @Nullable Object entity(HttpRequest<?> request, byte @Nullable [] body, Argument<?> argument) {
        try {
            return readEntity(request, body, argument);
        } catch (CodecException e) {
            // unmapped, the failure to read the entity is the client's
            JaxRsGlobalExceptionHandler.entityFailed(request, e);
            throw e;
        }
    }

    private @Nullable Object readEntity(HttpRequest<?> request, byte @Nullable [] body, Argument<?> argument) {
        InputStream replaced = request.getAttribute(JaxRsContainerRequestContext.ENTITY_STREAM, InputStream.class).orElse(null);
        if (replaced != null) {
            // the entity stream a request filter set
            return readers().readEntity(argument, request.getContentType().orElse(null), request.getHeaders(), replaced);
        }
        if (body == null || body.length == 0) {
            return emptyEntity(request, argument);
        }
        return readers()
            .readEntity(argument, request.getContentType().orElse(null), request.getHeaders(), new ByteArrayInputStream(body));
    }

    private JaxRsMessageBodyReaders<?> readers() {
        JaxRsMessageBodyReaders<?> readers = this.readers;
        if (readers == null) {
            readers = beanContext.getBean(JaxRsMessageBodyReaders.class);
            this.readers = readers;
        }
        return readers;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private @Nullable Object emptyEntity(HttpRequest<?> request, Argument<?> argument) {
        Class<?> type = argument.getType();
        if (type == String.class) {
            return "";
        }
        if (type.isPrimitive() || type == Boolean.class || type == Character.class || Number.class.isAssignableFrom(type)) {
            // the standard readers of these types cannot read an empty entity (JAX-RS 4.2.4)
            throw new BadRequestException(new NoContentException("An empty entity cannot be read as " + type.getName()));
        }
        // a JAX-RS reader of the application reads an empty entity too
        Optional<MediaType> declared = request.getContentType();
        MediaType contentType = declared.orElse(MediaType.APPLICATION_OCTET_STREAM_TYPE);
        Optional<MessageBodyReader<Object>> reader = beanContext.getBean(JaxRsContainerMessageBodyHandlerRegistry.class)
            .findReader((Argument) argument, List.of(contentType));
        if (reader.isEmpty()) {
            if (type == MultivaluedMap.class) {
                // the standard reader of a form reads an empty one
                return new MultivaluedHashMap<>();
            }
            if (type == Form.class) {
                return new Form();
            }
            if (declared.isPresent() && beanContext.getBean(MessageBodyHandlerRegistry.class).findReader(argument, List.of(contentType)).isEmpty()) {
                // no reader reads the entity as its type (JAX-RS 4.2.1)
                throw new NotSupportedException();
            }
            return null;
        }
        return reader.get().read((Argument) argument, contentType, request.getHeaders(), InputStream.nullInputStream());
    }

    /**
     * The event sink of a request, whose events are the body of its response, see
     * {@link JaxRsServerFilters}.
     */
    private SseEventSink sseEventSink(HttpRequest<?> request) {
        Optional<JaxRsSseEventSink> existing = request.getAttribute(SSE_EVENT_SINK, JaxRsSseEventSink.class);
        if (existing.isPresent()) {
            return existing.get();
        }
        JaxRsSseEventSink sink = new JaxRsSseEventSink(this::sseData);
        request.setAttribute(SSE_EVENT_SINK, sink);
        return sink;
    }

    /**
     * The data of an event as text, written by the message body writer of its type and media type
     * (JAX-RS 9.3).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private String sseData(OutboundSseEvent event) {
        Object data = Objects.requireNonNull(event.getData());
        MediaType mediaType = Objects.requireNonNull(JaxRsUtils.convert(event.getMediaType()));
        Argument<Object> type = (Argument) Argument.of(event.getType());
        List<MediaType> mediaTypes = List.of(mediaType);
        Optional<io.micronaut.http.body.MessageBodyWriter<Object>> writer = beanContext.getBean(JaxRsContainerMessageBodyHandlerRegistry.class)
            .findWriter(type, mediaTypes);
        if (writer.isEmpty()) {
            writer = beanContext.getBean(MessageBodyHandlerRegistry.class).findWriter(type, mediaTypes);
        }
        if (writer.isEmpty()) {
            return data.toString();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.get().writeTo(type, mediaType, data, new SimpleHttpHeaders(), out);
        return out.toString(mediaType.getCharset().orElse(StandardCharsets.UTF_8));
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
        if (type == SseEventSink.class) {
            return sseEventSink(request);
        }
        if (type == SecurityContext.class) {
            Optional<SecurityContext> replaced = request.getAttribute(JaxRsContainerRequestContext.SECURITY_CONTEXT, SecurityContext.class);
            if (replaced.isPresent()) {
                // set by a request filter
                return replaced.get();
            }
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
        if (SERVLET_REQUEST_TYPES.contains(type.getName())) {
            // not a servlet container
            return stubServletRequest(request);
        }
        return beanContext.findBean(type, named == null ? null : Qualifiers.byName(named)).orElse(null);
    }

    /**
     * The stub servlet request of a request a servlet container does not serve, the same for all
     * the {@code @Context} values of the request. Typed as an object, as the servlet API is
     * optional.
     */
    private Object stubServletRequest(HttpRequest<?> request) {
        Object stub = request.getAttribute(STUB_SERVLET_REQUEST).orElse(null);
        if (stub == null) {
            stub = new JaxRsStubHttpServletRequest(request, applicationProvider.getContextPath());
            request.setAttribute(STUB_SERVLET_REQUEST, stub);
        }
        return stub;
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
        return toHttpResponse(response);
    }

    /**
     * Converts a JAX-RS response, e.g. of a resource method or an exception mapper.
     *
     * @param response The JAX-RS response, null for no content
     * @return The response
     */
    static MutableHttpResponse<?> toHttpResponse(@Nullable Response response) {
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
                if (value != null) {
                    httpResponse.getHeaders().add(name, headerValue(value));
                }
            }
        });
        if (response.hasEntity()) {
            httpResponse.body(response.getEntity());
        }
        return httpResponse;
    }

    /**
     * A header value of a response as a string: with the header delegate of its type, e.g. of a
     * subclass of {@code Link} or {@code NewCookie}, a date as an HTTP date, else its
     * {@code toString()} (JAX-RS, {@code Response#getStringHeaders}).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String headerValue(Object value) {
        if (value instanceof String string) {
            return string;
        }
        if (value instanceof java.util.Date date) {
            return HTTP_DATE.format(date.toInstant().atZone(java.time.ZoneOffset.UTC));
        }
        for (Class<?> type : HEADER_TYPES) {
            if (type.isInstance(value)) {
                return ((jakarta.ws.rs.ext.RuntimeDelegate.HeaderDelegate) jakarta.ws.rs.ext.RuntimeDelegate.getInstance()
                    .createHeaderDelegate(type)).toString(value);
            }
        }
        return value.toString();
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
     * @param request    The request
     * @param entity     The entity returned by a resource method
     * @param returnType The declared type, with its type arguments, which selects the message body
     *                   writer
     * @return The response
     */
    @SuppressWarnings("unchecked")
    public HttpResponse<?> genericEntityResponse(HttpRequest<?> request, @Nullable Object entity, Argument<?> returnType) {
        if (entity == null) {
            return HttpResponse.noContent();
        }
        Argument<Object> argument = (Argument<Object>) Argument.of(returnType.getType(), routeAnnotations(request), returnType.getTypeParameters());
        return HttpResponse.ok(new JaxRsGenericEntity<>(entity, argument, null, null));
    }

    /**
     * @param request The request
     * @param entity  The {@link GenericEntity} returned by a resource method: its type selects the
     *                message body writer
     * @return The response
     */
    @SuppressWarnings("unchecked")
    public HttpResponse<?> genericEntityResponse(HttpRequest<?> request, @Nullable GenericEntity<?> entity) {
        if (entity == null) {
            return HttpResponse.noContent();
        }
        Argument<?> type = JaxRsReflection.get().argument(entity.getType());
        Argument<Object> argument = (Argument<Object>) Argument.of(type.getType(), routeAnnotations(request), type.getTypeParameters());
        return HttpResponse.ok(new JaxRsGenericEntity<>(entity.getEntity(), argument, null, null));
    }

    /**
     * The annotations of the resource method of the route of a request: the writers see them.
     */
    private static AnnotationMetadata routeAnnotations(HttpRequest<?> request) {
        RouteInfo<?> route = RouteAttributes.getRouteInfo(request).orElse(null);
        // the Java annotations of the resource method, like JAX-RS passes them: not merged with
        // the ones of its class
        AnnotationMetadata annotations = resourceMethodAnnotations(route);
        if (annotations != null) {
            return annotations;
        }
        return route == null ? AnnotationMetadata.EMPTY_METADATA : route.getAnnotationMetadata();
    }

    /**
     * The element of the resource method a route implements: the bean method given to the route,
     * or the {@link RouteMetadata} of a sub-resource that is not a bean. A handler route has no
     * Java method of its own.
     *
     * @param route The route
     * @return The element, or {@code null} for a route that is not a JAX-RS resource method
     */
    static @Nullable Object resourceMethodKey(@Nullable RouteInfo<?> route) {
        AnnotationMetadataProvider provider = provider(route);
        if (provider instanceof ExecutableMethod<?, ?> method) {
            return method;
        }
        return provider instanceof SubResourceMethod subResource ? subResource.metadata() : null;
    }

    /**
     * @param route The route
     * @return The resource method it implements, or {@code null} for a route that is not a JAX-RS resource method
     */
    static @Nullable Method resourceMethod(@Nullable RouteInfo<?> route) {
        AnnotationMetadataProvider provider = provider(route);
        if (provider instanceof ExecutableMethod<?, ?> method) {
            return JaxRsReflection.get().targetMethod(method);
        }
        if (provider instanceof SubResourceMethod(RouteMetadata metadata, AnnotationMetadata ignored)) {
            return JaxRsReflection.get().targetMethod(metadata.resourceClass, metadata.methodName, metadata.parameterTypes);
        }
        return null;
    }

    /**
     * @param route The route
     * @return The annotations of the resource method it implements, not merged with the ones of
     * its class, or {@code null} for a route that is not a JAX-RS resource method
     */
    static @Nullable AnnotationMetadata resourceMethodAnnotations(@Nullable RouteInfo<?> route) {
        AnnotationMetadataProvider provider = provider(route);
        if (provider instanceof ExecutableMethod<?, ?> method) {
            return JaxRsReflection.get().methodAnnotations(method);
        }
        if (provider instanceof SubResourceMethod(RouteMetadata metadata, AnnotationMetadata ignored)) {
            return JaxRsReflection.get().methodAnnotations(metadata.resourceClass, metadata.methodName, metadata.parameterTypes);
        }
        return null;
    }

    /**
     * @param route The route
     * @return The annotations of the resource method it implements as objects, as JAX-RS hands
     * them to the providers, or {@code null} for a route that is not a JAX-RS resource method
     */
    static Annotation @Nullable [] resourceMethodAnnotationObjects(@Nullable RouteInfo<?> route) {
        AnnotationMetadataProvider provider = provider(route);
        if (provider instanceof ExecutableMethod<?, ?> method) {
            return JaxRsReflection.get().annotations(method);
        }
        if (provider instanceof SubResourceMethod(RouteMetadata metadata, AnnotationMetadata ignored)) {
            AnnotationMetadata annotations = JaxRsReflection.get().methodAnnotations(metadata.resourceClass, metadata.methodName, metadata.parameterTypes);
            return JaxRsReflection.get().annotations(annotations, metadata.resourceClass.getClassLoader());
        }
        return null;
    }

    /**
     * @param route The route
     * @return The resource class of the resource method it implements
     */
    static @Nullable Class<?> resourceClass(@Nullable RouteInfo<?> route) {
        if (provider(route) instanceof SubResourceMethod subResource) {
            return subResource.metadata().resourceClass;
        }
        return route == null ? null : route.getDeclaringType();
    }

    private static @Nullable AnnotationMetadataProvider provider(@Nullable RouteInfo<?> route) {
        return route instanceof MethodBasedRouteInfo<?, ?> methodRoute ? methodRoute.getAnnotationMetadataProvider().orElse(null) : null;
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
     * The type of the response of a resource method that does not declare the types it produces,
     * when the response has none: negotiated from the types the JAX-RS writers of the application
     * write its entity as (JAX-RS 3.8). Without such writers, the response is left to Micronaut.
     *
     * @param request    The request
     * @param response   The response
     * @param returnType The declared return type of the resource method
     * @return The response
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public HttpResponse<?> negotiate(HttpRequest<?> request, HttpResponse<?> response, Argument<?> returnType) {
        if (response.getContentType().isPresent() || !(response instanceof MutableHttpResponse<?> mutable)) {
            return response;
        }
        Object body = response.getBody().orElse(null);
        if (body == null) {
            return response;
        }
        // the writers see the annotations of the resource method, like when they write
        AnnotationMetadata annotations = routeAnnotations(request);
        Argument<?> type;
        if (body instanceof GenericEntity<?> entity) {
            Argument<?> generic = JaxRsArgumentUtil.from(entity);
            type = Argument.of(generic.getType(), annotations, generic.getTypeParameters());
        } else if (returnType.getType() != Object.class && returnType.getType().isInstance(body)) {
            // a declared type that is not known only at runtime
            type = Argument.of(returnType.getType(), annotations, returnType.getTypeParameters());
        } else {
            type = Argument.of(body.getClass(), annotations);
        }
        List<MediaType> producible = beanContext.getBean(JaxRsContainerMessageBodyHandlerRegistry.class).producibleTypes((Argument) type);
        if (producible.isEmpty()) {
            // no application writer writes the entity: the response is left to Micronaut
            return response;
        }
        MediaType mediaType = JaxRsRouteTemplateEngine.responseType(request.getHeaders().accept(), producible);
        if (mediaType == null) {
            throw new NotAcceptableException();
        }
        if (mediaType.equals(MediaType.APPLICATION_OCTET_STREAM_TYPE)
            && producible.size() == 1 && producible.get(0).equals(MediaType.ALL_TYPE)
            && request.getHeaders().accept().stream().noneMatch(MediaType.APPLICATION_OCTET_STREAM_TYPE::equals)) {
            // any type is acceptable and any type is written: the response is left to Micronaut
            return response;
        }
        return mutable.contentType(mediaType);
    }

    /**
     * Reject a request whose negotiated response type is not concrete, e.g. {@code text/*}: a
     * resource method that declares the types it produces answers {@code 406} (JAX-RS 3.8).
     *
     * @param pathVariables The path variables, with the selected media type
     */
    public void acceptable(PathVariables pathVariables) {
        MediaType selected = RouteMatchSelector.selectedMediaType(pathVariables);
        if (selected != null && "*".equals(selected.getSubtype())) {
            throw new NotAcceptableException();
        }
    }

    /**
     * Give the entity of a response the negotiated type of a resource method that declares the
     * types it produces, unless the method chose one (JAX-RS 3.8): the response has it before
     * the entity is written, so a {@code HEAD} response has it too.
     *
     * @param pathVariables The path variables, with the selected media type
     * @param response      The response of the resource method
     * @return The response
     */
    public HttpResponse<?> produced(PathVariables pathVariables, HttpResponse<?> response) {
        MediaType selected = RouteMatchSelector.selectedMediaType(pathVariables);
        if (selected == null || "*".equals(selected.getType()) || "*".equals(selected.getSubtype())
            || response.getBody().isEmpty() || response.getContentType().isPresent()
            || !(response instanceof MutableHttpResponse<?> mutable)) {
            return response;
        }
        // by the name as it is written, like when the entity is written
        return mutable.header(HttpHeaders.CONTENT_TYPE, selected.toString());
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
        BeanIntrospection<Object> introspection = (BeanIntrospection<Object>) Optional.ofNullable(JaxRsReflection.get().introspection(type))
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
    @Nullable ValueReader reader(AnnotationMetadata metadata, Argument<?> argument, boolean encodedType) {
        String defaultValue = metadata.stringValue(DefaultValue.class).orElse(null);
        boolean encoded = encodedType || metadata.hasAnnotation(Encoded.class);
        String pathParam = metadata.stringValue(PathParam.class).orElse(null);
        String queryParam = metadata.stringValue(QueryParam.class).orElse(null);
        String matrixParam = metadata.stringValue(MatrixParam.class).orElse(null);
        String headerParam = metadata.stringValue(HeaderParam.class).orElse(null);
        String cookieParam = metadata.stringValue(CookieParam.class).orElse(null);
        String formParam = metadata.stringValue(FormParam.class).orElse(null);
        if (pathParam != null) {
            return (request, pathVariables, form) -> pathParam(request, pathVariables, pathParam, argument, defaultValue, encoded);
        } else if (queryParam != null) {
            return (request, pathVariables, form) -> queryParam(request, queryParam, argument, defaultValue, encoded);
        } else if (matrixParam != null) {
            return (request, pathVariables, form) -> matrixParam(request, matrixParam, argument, defaultValue, encoded);
        } else if (headerParam != null) {
            return (request, pathVariables, form) -> headerParam(request, headerParam, argument, defaultValue);
        } else if (cookieParam != null) {
            return (request, pathVariables, form) -> cookieParam(request, cookieParam, argument, defaultValue);
        } else if (formParam != null) {
            return (request, pathVariables, form) -> formParam(request, form, formParam, argument, defaultValue, encoded);
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
            return JaxRsReflection.get().instantiate(type);
        }
        Map<String, Object> arguments = new java.util.HashMap<>(names.length * 2);
        for (int i = 0; i < names.length; i++) {
            arguments.put(names[i], values[i]);
        }
        return beanContext.createBean(type, arguments);
    }

    private static boolean isUrlEncoded(HttpRequest<?> request) {
        return request.getContentType().map(MediaType.APPLICATION_FORM_URLENCODED_TYPE::matches).orElse(false);
    }

    private static boolean isRawForm(HttpRequest<?> request) {
        return request.getAttribute(RAW_FORM).isPresent();
    }

    private static Charset formCharset(HttpRequest<?> request) {
        return request.getContentType().flatMap(MediaType::getCharset).orElse(StandardCharsets.UTF_8);
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
            Argument<?> elementType = element(argument.getFirstTypeVariable().orElse(Argument.STRING), argument);
            List<String> elements = values.isEmpty() && defaultValue != null ? List.of(defaultValue) : values;
            Collection<Object> result = type == Set.class ? new LinkedHashSet<>() : type == SortedSet.class ? new TreeSet<>() : new ArrayList<>();
            for (String element : elements) {
                result.add(convertOne(element, elementType, notFound));
            }
            return type == List.class || type == Collection.class ? List.copyOf(result) : result;
        }
        if (type.isArray()) {
            Argument<?> componentType = element(Argument.of(type.getComponentType()), argument);
            List<String> elements = values.isEmpty() && defaultValue != null ? List.of(defaultValue) : values;
            List<Object> converted = new ArrayList<>(elements.size());
            for (String element : elements) {
                converted.add(convertOne(element, componentType, notFound));
            }
            return conversionService.convertRequired(converted, type);
        }
        String value = values.isEmpty() ? defaultValue : values.get(0);
        if (value == null) {
            return missing(argument);
        }
        return convertOne(value, argument, notFound);
    }

    /**
     * The type of the elements of a collection or array parameter, carrying the annotations of the
     * parameter: a {@link ParamConverterProvider} converting an element sees them.
     */
    private static Argument<?> element(Argument<?> element, Argument<?> parameter) {
        return Argument.of(element.getType(), parameter.getName(), parameter.getAnnotationMetadata(), element.getTypeParameters());
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
        // the annotations are part of the key: a ParamConverterProvider sees them, and arguments
        // are equal without them
        StringConverter converter = converters.computeIfAbsent(new ConverterKey(argument, argument.getAnnotationMetadata()),
            key -> converter(key.argument()));
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
        for (ParamConverterProvider provider : paramConverterProviders.get()) {
            ParamConverter paramConverter = provider.getConverter(type, argument.asType(), annotations);
            if (paramConverter != null) {
                return paramConverter::fromString;
            }
        }
        if (type == String.class) {
            return value -> value;
        }
        // a static valueOf or fromString method, or a constructor with a string (JAX-RS 3.2)
        java.util.function.Function<String, Object> conversion = JaxRsReflection.get().stringConversion(type);
        if (conversion != null) {
            return conversion::apply;
        }
        return value -> conversionService.convert(value, argument).orElse(null);
    }

    private static MediaType[] mediaTypes(String[] values) {
        MediaType[] mediaTypes = new MediaType[values.length];
        for (int i = 0; i < values.length; i++) {
            mediaTypes[i] = MediaType.of(values[i]);
        }
        return mediaTypes;
    }

    /**
     * The content of a field of a multipart form.
     *
     * @param fileName  The file name, {@code null} for a text field
     * @param mediaType The media type
     * @param content   The content
     */
    private record FormPart(@Nullable String fileName, jakarta.ws.rs.core.MediaType mediaType, byte[] content) {
    }

    /**
     * Reads a value of the request.
     */
    @FunctionalInterface
    interface ValueReader {
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
     * Converts the string value of a parameter.
     */
    @FunctionalInterface
    private interface StringConverter {
        @Nullable Object convert(String value) throws Exception;
    }

    /**
     * What the runtime routes know about a resource method.
     */
    public static final class RouteMetadata {
        private final Class<?> resourceClass;
        private final String methodName;
        private final Class<?>[] parameterTypes;
        private final String[] produces;
        private final String[] consumes;
        private final Class<?> rootClass;

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

    }

    /**
     * The resource method of a sub-resource that is not a bean, given to its route: with the
     * annotations of the method and its class.
     *
     * @param metadata           The metadata of the resource method
     * @param annotationMetadata The annotations of the method and its class
     */
    private record SubResourceMethod(RouteMetadata metadata, AnnotationMetadata annotationMetadata) implements AnnotationMetadataProvider {
        @Override
        public AnnotationMetadata getAnnotationMetadata() {
            return annotationMetadata;
        }
    }

    /**
     * The key of the converter of a parameter: its type, and its annotations.
     *
     * @param argument    The parameter
     * @param annotations The annotations of the parameter
     */
    private record ConverterKey(Argument<?> argument, AnnotationMetadata annotations) {
    }
}
