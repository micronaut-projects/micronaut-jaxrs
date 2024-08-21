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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpAttributes;
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
import io.micronaut.web.router.RouteInfo;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.util.List;

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

    private final ApplicationPathProvider applicationPathProvider;
    private final List<ContainerRequestFilter> requestFilters;
    private final List<ContainerResponseFilter> containerResponseFilters;

    JaxRsFilters(ApplicationPathProvider applicationPathProvider,
                 List<ContainerRequestFilter> requestFilters,
                 List<ContainerResponseFilter> containerResponseFilters) {
        this.applicationPathProvider = applicationPathProvider;
        this.requestFilters = requestFilters;
        JaxRsUtils.sortByPriority(requestFilters);
        this.containerResponseFilters = containerResponseFilters;
        JaxRsUtils.sortByPriorityReversed(containerResponseFilters);
    }

    @Nullable
    @RequestFilter
    HttpResponse<?> filterRequest(MutableHttpRequest<?> request) throws IOException {
        if (requestFilters.isEmpty()) {
            return null;
        }
        JaxRsContainerRequestContext requestContext = new JaxRsContainerRequestContext(request, applicationPathProvider);
        if (!containerResponseFilters.isEmpty()) {
            request.setAttribute(REQUEST_CONTEXT_KEY, requestContext);
        }
        for (ContainerRequestFilter requestFilter : requestFilters) {
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

    @ResponseFilter
    MutableHttpResponse<?> filterResponse(HttpRequest<?> request,
                                          MutableHttpResponse<?> mutableHttpResponse) throws IOException {
        // Intercept only JaxRs routes
        Object body;
        if (request.getMethod() == HttpMethod.HEAD) {
            body = mutableHttpResponse.getAttribute(HttpAttributes.HEAD_BODY).orElse(null);
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
        if (!containerResponseFilters.isEmpty()) {
            Argument<?> bodyArgument = null;
            if (body instanceof JaxRsGenericEntity<?> genericEntity) {
                bodyArgument = genericEntity.asArgument();
                mutableHttpResponse.body(genericEntity.getEntity());
            } else if (body instanceof GenericEntity<?> genericEntity) {
                bodyArgument = JaxRsArgumentUtil.from(genericEntity);
                mutableHttpResponse.body(genericEntity.getEntity());
            } else if (body != null) {
                bodyArgument = Argument.of(body.getClass());
            }
            RouteInfo<?> routeInfo = request.getAttribute(HttpAttributes.ROUTE_INFO, RouteInfo.class).orElse(null);
            if (routeInfo != null) {
                Argument<?> returnType = routeInfo.getReturnType().asArgument();
                if (bodyArgument == null) {
                    bodyArgument = returnType;
                } else {
                    MutableAnnotationMetadata mutableAnnotationMetadata = new MutableAnnotationMetadata();
                    mutableAnnotationMetadata.addAnnotationMetadata(MutableAnnotationMetadata.of(returnType.getAnnotationMetadata()));
                    mutableAnnotationMetadata.addAnnotationMetadata(MutableAnnotationMetadata.of(bodyArgument.getAnnotationMetadata()));
                    bodyArgument = Argument.of(bodyArgument.getType(), mutableAnnotationMetadata, bodyArgument.getTypeParameters());
                }
            }
            JaxRsContainerRequestContext requestContext = request.getAttribute(REQUEST_CONTEXT_KEY, JaxRsContainerRequestContext.class)
                .orElseGet(() -> new JaxRsContainerRequestContext(request.mutate(), applicationPathProvider));
            requestContext.finished();
            JaxRsContainerResponseContext responseContext = new JaxRsContainerResponseContext(mutableHttpResponse, bodyArgument);
            for (ContainerResponseFilter responseFilter : containerResponseFilters) {
                responseFilter.filter(requestContext, responseContext);
            }
            if (body instanceof GenericEntity<?> genericEntity &&
                genericEntity.getEntity() == mutableHttpResponse.getBody() &&
                bodyArgument == responseContext.getBodyArgument()) {
                // Put back the generic entity if the body and the argument didn't change
                mutableHttpResponse.body(body);
            }
        }
        return mutableHttpResponse;
    }
}
