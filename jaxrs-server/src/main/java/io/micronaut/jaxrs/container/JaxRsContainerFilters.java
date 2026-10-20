/*
 * Copyright 2017-2020 original authors
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
import io.micronaut.context.BeanRegistration;
import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpMessage;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.jaxrs.common.JaxRsArgumentUtil;
import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.http.ServerHttpRequest;
import io.micronaut.http.body.ByteBody;
import io.micronaut.jaxrs.common.JaxRsContainerMessageBodyHandlerRegistry;
import io.micronaut.jaxrs.common.JaxRsGenericEntity;
import io.micronaut.jaxrs.common.JaxRsMutableResponse;
import io.micronaut.jaxrs.common.JaxRsUtils;
import io.micronaut.jaxrs.common.NameBindingPredicate;
import io.micronaut.jaxrs.runtime.ext.bind.HttpHeadersBinder;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.RouteInfo;
import jakarta.inject.Singleton;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.Response;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Comparator;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import java.util.function.ToIntFunction;

/**
 * The container filters of JAX-RS (section 6): the pre-matching and post-matching request
 * filters, and the response filters, with the ones the dynamic features registered for a resource
 * method. Independent of how they are plugged into the request processing, see {@link JaxRsServerFilters} and {@link JaxRsRouteSupport#configure}.
 *
 * @author graemerocher
 * @author Denis Stepanov
 * @since 1.0
 */
@Singleton
@Internal
final class JaxRsContainerFilters {

    private static final String REQUEST_CONTEXT_KEY = ContainerRequestFilter.class.getName();

    private final ApplicationProvider applicationProvider;
    private final BeanContext beanContext;
    private final List<ContainerRequestFilter> preMatchingRequestFilters;
    private final List<BeanRegistration<ContainerRequestFilter>> requestFilters;
    private final List<BeanRegistration<ContainerResponseFilter>> containerResponseFilters;
    private final NameBindingPredicate nameBindingPredicate;
    private final JaxRsFeatures features;

    JaxRsContainerFilters(ApplicationProvider applicationProvider,
                 BeanContext beanContext,
                 NameBindingPredicate nameBindingPredicate,
                 JaxRsFeatures features,
                 JaxRsContainerMessageBodyHandlerRegistry registry) {
        this.applicationProvider = applicationProvider;
        this.beanContext = beanContext;
        this.features = features;
        // the filters the features register are beans too
        features.configure();
        // the ones registered with the application, like the other providers (JAX-RS 2.3)
        // for the contracts, with the priorities, a feature registered them with
        List<BeanRegistration<ContainerRequestFilter>> requestFilters = new ArrayList<>(
            features.registered(beanContext.getBeanRegistrations(ContainerRequestFilter.class).stream().toList(), ContainerRequestFilter.class));
        requestFilters.removeIf(br -> !registry.isRegistered(br.getBeanDefinition().getBeanType()));
        List<BeanRegistration<ContainerResponseFilter>> containerResponseFilters = new ArrayList<>(
            features.registered(beanContext.getBeanRegistrations(ContainerResponseFilter.class).stream().toList(), ContainerResponseFilter.class));
        containerResponseFilters.removeIf(br -> !registry.isRegistered(br.getBeanDefinition().getBeanType()));
        this.nameBindingPredicate = nameBindingPredicate;
        Map<Boolean, List<BeanRegistration<ContainerRequestFilter>>> matching = requestFilters.stream().collect(Collectors.groupingBy(br -> br.getBeanDefinition().hasAnnotation(PreMatching.class)));
        List<BeanRegistration<ContainerRequestFilter>> preMatching = new ArrayList<>(matching.getOrDefault(true, List.of()));
        JaxRsUtils.sortRegistrationsByPriority(preMatching);
        this.preMatchingRequestFilters = preMatching.stream().map(BeanRegistration::getBean).toList();
        this.requestFilters = new ArrayList<>(matching.getOrDefault(false, List.of()));
        JaxRsUtils.sortRegistrationsByPriority(this.requestFilters);
        this.containerResponseFilters = containerResponseFilters;
        JaxRsUtils.sortRegistrationsByPriorityReversed(this.containerResponseFilters);
    }

    /**
     * The context of the filters of a request, with the security context and the JAX-RS request of
     * the request.
     */
    private JaxRsContainerRequestContext newContext(MutableHttpRequest<?> request, boolean preMatching) {
        return new JaxRsContainerRequestContext(request, applicationProvider, preMatching)
            .withSecurityContext(() -> beanContext.getBean(JaxRsContextSecurityContext.class).resolve(request))
            .withRequest(() -> beanContext.getBean(JaxRsContextRequest.class));
    }

    /**
     * Run the response filters.
     *
     * @param routeInfo           The route of a resource method, {@code null} if no route matched
     * @param request             The request
     * @param mutableHttpResponse The response
     * @return The response
     * @throws IOException If a filter fails
     */
    MutableHttpResponse<?> filterResponse(@Nullable RouteInfo<?> routeInfo,
                                          HttpRequest<?> request,
                                          MutableHttpResponse<?> mutableHttpResponse) throws IOException {
        String vary = request.getAttribute(JaxRsContextRequest.VARY, String.class).orElse(null);
        if (vary != null && !mutableHttpResponse.getHeaders().contains(io.micronaut.http.HttpHeaders.VARY)) {
            // the headers Request#selectVariant negotiated with
            mutableHttpResponse.header(io.micronaut.http.HttpHeaders.VARY, vary);
        }
        Object body;
        if (request.getMethod() == HttpMethod.HEAD) {
            body = RouteAttributes.getHeadBody(mutableHttpResponse).orElse(null);
        } else {
            body = mutableHttpResponse.getBody().orElse(null);
        }
        if (body instanceof JaxRsMutableResponse jrs) {
            final MutableHttpResponse<?> jaxRsResponse = jrs.getResponse();
            mutableHttpResponse.getAttributes().forEach(jaxRsResponse::setAttribute);
            mutableHttpResponse.getHeaders().forEach((name, value) -> {
                for (String val : value) {
                    jaxRsResponse.header(name, val);
                }
            });
            mutableHttpResponse = jaxRsResponse;
            body = mutableHttpResponse.getBody().orElse(null);
        }
        Argument<?> bodyArgument;
        // the annotations given with the entity
        Annotation[] entityAnnotations = null;
        // the type of a GenericEntity of the application is the one the writers see (JAX-RS 4.2.2)
        boolean declaredType = false;
        if (body instanceof JaxRsGenericEntity<?> genericEntity) {
            bodyArgument = genericEntity.asArgument();
            entityAnnotations = genericEntity.getAnnotations();
            mutableHttpResponse.body(genericEntity.getEntity());
            body = genericEntity.getEntity();
            // e.g. the GenericEntity a resource method returns, with its type arguments
            declaredType = bodyArgument.getType().isInstance(body);
        } else if (body instanceof GenericEntity<?> genericEntity) {
            body = genericEntity.getEntity();
            bodyArgument = JaxRsArgumentUtil.from(genericEntity);
            mutableHttpResponse.body(genericEntity.getEntity());
            declaredType = true;
        } else if (body != null && routeInfo != null) {
            bodyArgument = routeInfo.getResponseBodyType();
            // the declared type of the route, with its type arguments, e.g. List<Item> for an
            // ArrayList, is the generic type the writers see (JAX-RS 4.2.2)
            declaredType = bodyArgument.getTypeParameters().length > 0 && bodyArgument.getType().isInstance(body);
        } else {
            bodyArgument = Argument.OBJECT_ARGUMENT;
        }
        if (body != null && !declaredType && !bodyArgument.getType().equals(body.getClass())) {
            bodyArgument = Argument.of(body.getClass(), bodyArgument.getAnnotationMetadata());
        }

        Argument<?> returnType = routeInfo == null ? Argument.VOID : routeInfo.getReturnType().asArgument();
        Annotation[] methodAnnotations = JaxRsRouteSupport.resourceMethodAnnotationObjects(routeInfo);
        if (methodAnnotations != null) {
            // the writers see the annotations given with the entity, then the Java annotations of
            // the resource method, like JAX-RS passes them
            if (entityAnnotations == null) {
                entityAnnotations = methodAnnotations;
            } else {
                Annotation[] all = Arrays.copyOf(entityAnnotations, entityAnnotations.length + methodAnnotations.length);
                System.arraycopy(methodAnnotations, 0, all, entityAnnotations.length, methodAnnotations.length);
                entityAnnotations = all;
            }
            Argument<?> type = bodyArgument == null ? returnType : bodyArgument;
            bodyArgument = Argument.of(type.getType(), JaxRsArgumentUtil.createAnnotationMetadata(entityAnnotations), type.getTypeParameters());
        } else if (bodyArgument == null) {
            bodyArgument = returnType;
        } else {
            MutableAnnotationMetadata mutableAnnotationMetadata = new MutableAnnotationMetadata();
            mutableAnnotationMetadata.addAnnotationMetadata(MutableAnnotationMetadata.of(returnType.getAnnotationMetadata()));
            mutableAnnotationMetadata.addAnnotationMetadata(MutableAnnotationMetadata.of(bodyArgument.getAnnotationMetadata()));
            bodyArgument = Argument.of(bodyArgument.getType(), mutableAnnotationMetadata, bodyArgument.getTypeParameters());
        }
        ByteArrayOutputStream delegateEntityStream = null;
        OutputStream customEntityStream = null;
        JaxRsFeatures.Components dynamic = dynamicComponents(routeInfo);
        if (!containerResponseFilters.isEmpty() || !dynamic.responseFilters().isEmpty()) {
            JaxRsContainerRequestContext requestContext = request.getAttribute(REQUEST_CONTEXT_KEY, JaxRsContainerRequestContext.class)
                .orElseGet(() -> newContext(request.mutate(), false));
            requestContext.finished();
            JaxRsContainerResponseContext responseContext = new JaxRsContainerResponseContext(mutableHttpResponse, bodyArgument, entityAnnotations);
            // with the ones the dynamic features registered for the resource method, in the
            // reverse order of their priorities (JAX-RS 6.9)
            List<ContainerResponseFilter> filters = filters(containerResponseFilters, dynamic.responseFilters(),
                f -> dynamic.priority(f, ContainerResponseFilter.class), true);
            for (ContainerResponseFilter responseFilter : filters) {
                responseFilter.filter(requestContext, responseContext);
            }
            bodyArgument = responseContext.getBodyArgument();
            body = responseContext.getEntity();
            delegateEntityStream = responseContext.getDelegateEntityStream();
            customEntityStream = responseContext.getCustomEntityStream();
        }
        if (body != null) {
            mutableHttpResponse.body(new JaxRsGenericEntity<>(
                body,
                (Argument<? super Object>) bodyArgument,
                delegateEntityStream,
                customEntityStream)
            );
        }
        return mutableHttpResponse;
    }

    /**
     * The filter beans bound to the resource method, merged with the filters the dynamic features
     * registered for it by priority.
     */
    private <F> List<F> filters(List<BeanRegistration<F>> beans, List<F> dynamic, ToIntFunction<F> dynamicPriority, boolean reversed) {
        List<BeanRegistration<F>> bound = beans.stream()
            .filter(br -> nameBindingPredicate.test(br.getBeanDefinition()))
            .toList();
        if (dynamic.isEmpty()) {
            return bound.stream().map(BeanRegistration::getBean).toList();
        }
        record Prioritized<F>(F filter, int priority) {
        }
        List<Prioritized<F>> all = new ArrayList<>();
        for (BeanRegistration<F> registration : bound) {
            all.add(new Prioritized<>(registration.getBean(), JaxRsUtils.getPriorityOrder(registration)));
        }
        for (F filter : dynamic) {
            all.add(new Prioritized<>(filter, dynamicPriority.applyAsInt(filter)));
        }
        Comparator<Prioritized<F>> comparator = Comparator.comparingInt(Prioritized::priority);
        all.sort(reversed ? comparator.reversed() : comparator);
        return all.stream().map(Prioritized::filter).toList();
    }

    /**
     * The filters and interceptors the dynamic features registered for the resource method of a
     * route.
     */
    private JaxRsFeatures.Components dynamicComponents(@Nullable RouteInfo<?> routeInfo) {
        return features.components(routeInfo);
    }

    /**
     * Run the pre-matching request filters, once the entity is buffered, see
     * {@link #withBufferedEntity}.
     *
     * @param request The request
     * @return The stage of the response a filter aborted with, the request with the method a
     * filter changed, or {@code null}
     */
    CompletionStage<@Nullable HttpMessage<?>> filterPreMatchingRequestAsync(MutableHttpRequest<?> request) {
        if (preMatchingRequestFilters.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return withBufferedEntity(request, () -> filterPreMatchingRequest(request));
    }

    /**
     * Run the post-matching request filters of a resource method, once the entity is buffered,
     * see {@link #withBufferedEntity}.
     *
     * @param routeInfo The route of the resource method
     * @param request   The request
     * @return The stage of the response a filter aborted with, or {@code null}
     */
    CompletionStage<@Nullable HttpResponse<?>> filterRequestAsync(RouteInfo<?> routeInfo, MutableHttpRequest<?> request) {
        if (requestFilters.isEmpty() && dynamicComponents(routeInfo).requestFilters().isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return withBufferedEntity(request, () -> filterRequest(routeInfo, request));
    }

    /**
     * Run request filters once the entity of the request is buffered: a filter reads it with
     * {@link ContainerRequestContext#getEntityStream()}, which must not block, e.g. the event
     * loop, while it streams. A copy of the body is buffered, the resource method still reads
     * the body, and it is consumed here, so nothing is left to release. The copy is split with
     * {@code FASTEST} backpressure: the resource method only reads its body once the filters ran,
     * so demand of the copy alone must pull the body, else a body larger than what the server
     * reads ahead, or one sent after {@code 100 Continue}, never completes. The original body
     * keeps what the copy read, bounded by the maximum request size of the server.
     */
    private <M> CompletionStage<@Nullable M> withBufferedEntity(MutableHttpRequest<?> request, Filtering<M> filtering) {
        if (!(request instanceof ServerHttpRequest<?> serverHttpRequest)
            || request.getAttribute(JaxRsContainerRequestContext.BUFFERED_ENTITY).isPresent()
            || !hasEntity(request)) {
            return run(filtering);
        }
        PropagatedContext propagatedContext = PropagatedContext.getOrEmpty();
        return serverHttpRequest.byteBody().split(ByteBody.SplitBackpressureMode.FASTEST).buffer().thenCompose(body -> {
            request.setAttribute(JaxRsContainerRequestContext.BUFFERED_ENTITY, body.toByteArray());
            // the filters run in the context of the request, also when the body completes later
            try (PropagatedContext.Scope ignore = propagatedContext.propagate()) {
                return run(filtering);
            }
        });
    }

    private static boolean hasEntity(HttpRequest<?> request) {
        return request.getMethod().permitsRequestBody()
            && (request.getContentLength() > 0 || request.getHeaders().contains(io.micronaut.http.HttpHeaders.TRANSFER_ENCODING));
    }

    private static <M> CompletionStage<@Nullable M> run(Filtering<M> filtering) {
        try {
            return CompletableFuture.completedFuture(filtering.filter());
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Run the pre-matching request filters.
     *
     * @param request The request
     * @return The response a filter aborted with, the request with the method a filter changed, or
     * {@code null}
     * @throws IOException If a filter fails
     */
    @Nullable
    HttpMessage<?> filterPreMatchingRequest(MutableHttpRequest<?> request) throws IOException {
        if (preMatchingRequestFilters.isEmpty()) {
            // Intercept only JaxRs routes
            return null;
        }
        JaxRsContainerRequestContext requestContext = newContext(request, true);
        for (ContainerRequestFilter preMatchingRequestFilter : preMatchingRequestFilters) {
            preMatchingRequestFilter.filter(requestContext);
            Response response = requestContext.getResponse();
            if (response != null) {
                // a response of the application too, not only of the runtime delegate
                return JaxRsRouteSupport.toHttpResponse(response);
            }
        }
        requestContext.finished();
        // the request whose method a filter changed: the route is matched with it
        return requestContext.getMethodChangedRequest();
    }

    /**
     * Run the post-matching request filters of a resource method.
     *
     * @param routeInfo The route of the resource method
     * @param request   The request
     * @return The response a filter aborted with, or {@code null}
     * @throws IOException If a filter fails
     */
    @Nullable
    HttpResponse<?> filterRequest(RouteInfo<?> routeInfo, MutableHttpRequest<?> request) throws IOException {
        JaxRsFeatures.Components dynamic = dynamicComponents(routeInfo);
        if (requestFilters.isEmpty() && dynamic.requestFilters().isEmpty()) {
            return null;
        }
        JaxRsContainerRequestContext requestContext = newContext(request, false);
        if (!containerResponseFilters.isEmpty() || !dynamic.responseFilters().isEmpty()) {
            request.setAttribute(REQUEST_CONTEXT_KEY, requestContext);
        }
        request.setAttribute(HttpHeadersBinder.HEADERS_KEY, request.getHeaders());
        // with the ones the dynamic features registered for the resource method, in the order of
        // their priorities (JAX-RS 6.9)
        List<ContainerRequestFilter> filters = filters(requestFilters, dynamic.requestFilters(),
            f -> dynamic.priority(f, ContainerRequestFilter.class), false);
        for (ContainerRequestFilter requestFilter : filters) {
            requestFilter.filter(requestContext);
            Response response = requestContext.getResponse();
            if (response != null) {
                // a response of the application too, not only of the runtime delegate
                return JaxRsRouteSupport.toHttpResponse(response);
            }
        }
        requestContext.finished();
        return null;
    }

    /**
     * Runs request filters.
     *
     * @param <M> The type of message they answer with
     */
    @FunctionalInterface
    private interface Filtering<M> {
        @Nullable M filter() throws IOException;
    }
}
