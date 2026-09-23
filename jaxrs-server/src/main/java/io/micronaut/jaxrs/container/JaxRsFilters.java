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
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.jaxrs.common.JaxRsArgumentUtil;
import io.micronaut.jaxrs.common.JaxRsGenericEntity;
import io.micronaut.jaxrs.common.JaxRsMutableResponse;
import io.micronaut.jaxrs.common.JaxRsResponse;
import io.micronaut.jaxrs.common.JaxRsUtils;
import io.micronaut.jaxrs.common.NameBindingPredicate;
import io.micronaut.jaxrs.runtime.ext.bind.HttpHeadersBinder;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.MethodBasedRouteInfo;
import io.micronaut.web.router.RouteInfo;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.Response;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A filter which retrieves the actual response from the returned JAX-RS Response object.
 *
 * @author graemerocher
 * @since 1.0
 */
@ServerFilter("/**")
@Internal
final class JaxRsFilters {

    private static final String REQUEST_CONTEXT_KEY = ContainerRequestFilter.class.getName();

    private final ApplicationProvider applicationProvider;
    private final List<ContainerRequestFilter> preMatchingRequestFilters;
    private final List<BeanRegistration<ContainerRequestFilter>> requestFilters;
    private final List<BeanRegistration<ContainerResponseFilter>> containerResponseFilters;
    private final NameBindingPredicate nameBindingPredicate;
    private final JaxRsFeatures features;

    JaxRsFilters(ApplicationProvider applicationProvider,
                 BeanContext beanContext,
                 NameBindingPredicate nameBindingPredicate,
                 JaxRsFeatures features) {
        this.applicationProvider = applicationProvider;
        this.features = features;
        // the filters the features register are beans too
        features.configure();
        List<BeanRegistration<ContainerRequestFilter>> requestFilters = new ArrayList<>(beanContext.getBeanRegistrations(ContainerRequestFilter.class));
        List<BeanRegistration<ContainerResponseFilter>> containerResponseFilters = new ArrayList<>(beanContext.getBeanRegistrations(ContainerResponseFilter.class));
        this.nameBindingPredicate = nameBindingPredicate;
        Map<Boolean, List<BeanRegistration<ContainerRequestFilter>>> matching = requestFilters.stream().collect(Collectors.groupingBy(br -> br.getBeanDefinition().hasAnnotation(PreMatching.class)));
        this.preMatchingRequestFilters = new ArrayList<>(matching.getOrDefault(true, List.of()).stream().map(BeanRegistration::getBean).toList());
        this.requestFilters = new ArrayList<>(matching.getOrDefault(false, List.of()));
        JaxRsUtils.sortByPriority(this.preMatchingRequestFilters);
        JaxRsUtils.sortRegistrationsByPriority(this.requestFilters);
        this.containerResponseFilters = containerResponseFilters;
        JaxRsUtils.sortRegistrationsByPriorityReversed(this.containerResponseFilters);
    }

    @ResponseFilter
    @io.micronaut.http.server.annotation.PreMatching
    MutableHttpResponse<?> filterResponsePreMatch(HttpRequest<?> request,
                                                  MutableHttpResponse<?> mutableHttpResponse) throws IOException {
        return filterResponse(null, request, mutableHttpResponse);
    }

    @ResponseFilter
    MutableHttpResponse<?> filterResponse(@Nullable RouteInfo<?> routeInfo,
                                          HttpRequest<?> request,
                                          MutableHttpResponse<?> mutableHttpResponse) throws IOException {
        if (routeInfo != null && !isJaxRs(routeInfo)) {
            // Intercept only JaxRs routes
            return mutableHttpResponse;
        }
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
        if (body instanceof JaxRsGenericEntity<?> genericEntity) {
            bodyArgument = genericEntity.asArgument();
            entityAnnotations = genericEntity.getAnnotations();
            mutableHttpResponse.body(genericEntity.getEntity());
            body = genericEntity.getEntity();
        } else if (body instanceof GenericEntity<?> genericEntity) {
            body = genericEntity.getEntity();
            bodyArgument = JaxRsArgumentUtil.from(genericEntity);
            mutableHttpResponse.body(genericEntity.getEntity());
        } else if (body != null && routeInfo != null) {
            bodyArgument = routeInfo.getResponseBodyType();
        } else {
            bodyArgument = Argument.OBJECT_ARGUMENT;
        }
        if (body != null && !bodyArgument.getType().equals(body.getClass())) {
            bodyArgument = Argument.of(body.getClass(), bodyArgument.getAnnotationMetadata());
        }

        Argument<?> returnType = routeInfo == null ? Argument.VOID : routeInfo.getReturnType().asArgument();
        if (routeInfo instanceof MethodBasedRouteInfo<?, ?> methodRoute) {
            // the writers see the annotations given with the entity, then the Java annotations of
            // the resource method, like JAX-RS passes them
            Annotation[] methodAnnotations = methodRoute.getTargetMethod().getTargetMethod().getAnnotations();
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
                .orElseGet(() -> new JaxRsContainerRequestContext(request.mutate(), applicationProvider));
            requestContext.finished();
            JaxRsContainerResponseContext responseContext = new JaxRsContainerResponseContext(mutableHttpResponse, bodyArgument, entityAnnotations);
            List<ContainerResponseFilter> filters = new ArrayList<>(containerResponseFilters.stream()
                .filter(br -> nameBindingPredicate.test(br.getBeanDefinition()))
                .map(BeanRegistration::getBean)
                .toList());
            // the ones the dynamic features registered for the resource method
            filters.addAll(dynamic.responseFilters());
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
     * Whether a route is one of a JAX-RS resource method: of a root resource, with its
     * {@code @Path}, or of a sub-resource, whose class may have none, with its request method
     * designator, e.g. {@code @GET}.
     */
    private static boolean isJaxRs(RouteInfo<?> routeInfo) {
        AnnotationMetadata metadata = routeInfo.getAnnotationMetadata();
        return metadata.hasAnnotation(Path.class) || metadata.hasStereotype(jakarta.ws.rs.HttpMethod.class);
    }

    /**
     * The filters and interceptors the dynamic features registered for the resource method of a
     * route.
     */
    private JaxRsFeatures.Components dynamicComponents(@Nullable RouteInfo<?> routeInfo) {
        return features.components(routeInfo);
    }

    @Nullable
    @io.micronaut.http.server.annotation.PreMatching
    @RequestFilter
    HttpResponse<?> filterPreMatchingRequest(MutableHttpRequest<?> request) throws IOException {
        if (preMatchingRequestFilters.isEmpty()) {
            // Intercept only JaxRs routes
            return null;
        }
        JaxRsContainerRequestContext requestContext = new JaxRsContainerRequestContext(request, applicationProvider);
        for (ContainerRequestFilter preMatchingRequestFilter : preMatchingRequestFilters) {
            preMatchingRequestFilter.filter(requestContext);
            Response response = requestContext.getResponse();
            if (response != null) {
                if (response instanceof JaxRsResponse jaxRsResponse) {
                    return jaxRsResponse.getResponse();
                }
            }
        }
        requestContext.finished();
        return null;
    }

    @Nullable
    @RequestFilter
    HttpResponse<?> filterRequest(RouteInfo<?> routeInfo, MutableHttpRequest<?> request) throws IOException {
        if (!isJaxRs(routeInfo)) {
            // Intercept only JaxRs routes
            return null;
        }
        JaxRsFeatures.Components dynamic = dynamicComponents(routeInfo);
        if (requestFilters.isEmpty() && dynamic.requestFilters().isEmpty()) {
            return null;
        }
        JaxRsContainerRequestContext requestContext = new JaxRsContainerRequestContext(request, applicationProvider);
        if (!containerResponseFilters.isEmpty() || !dynamic.responseFilters().isEmpty()) {
            request.setAttribute(REQUEST_CONTEXT_KEY, requestContext);
        }
        request.setAttribute(HttpHeadersBinder.HEADERS_KEY, request.getHeaders());
        List<ContainerRequestFilter> filters = new ArrayList<>(requestFilters.stream()
            .filter(br -> nameBindingPredicate.test(br.getBeanDefinition()))
            .map(BeanRegistration::getBean)
            .toList());
        // the ones the dynamic features registered for the resource method
        filters.addAll(dynamic.requestFilters());
        for (ContainerRequestFilter requestFilter : filters) {
            requestFilter.filter(requestContext);
            Response response = requestContext.getResponse();
            if (response != null) {
                if (response instanceof JaxRsResponse jaxRsResponse) {
                    return jaxRsResponse.getResponse();
                }
            }
        }
        requestContext.finished();
        return null;
    }

}
