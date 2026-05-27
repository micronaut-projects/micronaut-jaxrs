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

import io.micronaut.context.BeanRegistration;
import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.http.server.exceptions.NotAcceptableException;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.jaxrs.common.JaxRsArgumentUtil;
import io.micronaut.jaxrs.common.JaxRsGenericEntity;
import io.micronaut.jaxrs.common.JaxRsHttpHeaders;
import io.micronaut.jaxrs.common.JaxRsMutableResponse;
import io.micronaut.jaxrs.common.JaxRsResponse;
import io.micronaut.jaxrs.common.JaxRsUtils;
import io.micronaut.jaxrs.common.NameBindingPredicate;
import io.micronaut.jaxrs.runtime.ext.bind.HttpHeadersBinder;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.RouteInfo;
import io.micronaut.web.router.Router;
import io.micronaut.web.router.UriRouteInfo;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
    private static final String CLIENT_QUALITY_PARAMETER = "q";
    private static final String SERVER_QUALITY_PARAMETER = "qs";

    private final Router router;
    private final ApplicationProvider applicationProvider;
    private final List<ContainerRequestFilter> preMatchingRequestFilters;
    private final List<BeanRegistration<ContainerRequestFilter>> requestFilters;
    private final List<BeanRegistration<ContainerResponseFilter>> containerResponseFilters;
    private final NameBindingPredicate nameBindingPredicate;

    JaxRsFilters(Router router,
                 ApplicationProvider applicationProvider,
                 List<BeanRegistration<ContainerRequestFilter>> requestFilters,
                 List<BeanRegistration<ContainerResponseFilter>> containerResponseFilters,
                 NameBindingPredicate nameBindingPredicate) {
        this.router = router;
        this.applicationProvider = applicationProvider;
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
        if (routeInfo != null && !routeInfo.getAnnotationMetadata().hasAnnotation(Path.class)) {
            // Intercept only JaxRs routes
            return mutableHttpResponse;
        }
        Object body;
        if (request.getMethod() == HttpMethod.HEAD) {
            body = RouteAttributes.getHeadBody(mutableHttpResponse).orElse(null);
        } else {
            body = mutableHttpResponse.getBody().orElse(null);
        }
        boolean jaxRsResponse = false;
        if (body instanceof JaxRsMutableResponse jrs) {
            jaxRsResponse = true;
            final MutableHttpResponse<?> unwrappedResponse = jrs.getResponse();
            mutableHttpResponse.getAttributes().forEach(unwrappedResponse::setAttribute);
            mutableHttpResponse.getHeaders().forEach((name, value) -> {
                for (String val : value) {
                    unwrappedResponse.header(name, val);
                }
            });
            mutableHttpResponse = unwrappedResponse;
            body = mutableHttpResponse.getBody().orElse(null);
        }
        if (jaxRsResponse) {
            resolveRelativeLocation(request, mutableHttpResponse);
        }
        applySelectedVariantVary(request, mutableHttpResponse);
        Argument<?> bodyArgument;
        if (body instanceof JaxRsGenericEntity<?> genericEntity) {
            bodyArgument = genericEntity.asArgument();
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
        if (bodyArgument == null) {
            bodyArgument = returnType;
        } else {
            MutableAnnotationMetadata mutableAnnotationMetadata = new MutableAnnotationMetadata();
            mutableAnnotationMetadata.addAnnotationMetadata(MutableAnnotationMetadata.of(returnType.getAnnotationMetadata()));
            mutableAnnotationMetadata.addAnnotationMetadata(MutableAnnotationMetadata.of(bodyArgument.getAnnotationMetadata()));
            bodyArgument = Argument.of(bodyArgument.getType(), mutableAnnotationMetadata, bodyArgument.getTypeParameters());
        }
        ByteArrayOutputStream delegateEntityStream = null;
        OutputStream customEntityStream = null;
        if (!containerResponseFilters.isEmpty()) {
            JaxRsContainerRequestContext requestContext = request.getAttribute(REQUEST_CONTEXT_KEY, JaxRsContainerRequestContext.class)
                .orElseGet(() -> new JaxRsContainerRequestContext(request.mutate(), applicationProvider));
            requestContext.finished();
            JaxRsContainerResponseContext responseContext = new JaxRsContainerResponseContext(mutableHttpResponse, bodyArgument);
            List<ContainerResponseFilter> filters = containerResponseFilters.stream()
                .filter(br -> nameBindingPredicate.test(br.getBeanDefinition()))
                .map(BeanRegistration::getBean)
                .toList();
            for (ContainerResponseFilter responseFilter : filters) {
                responseFilter.filter(requestContext, responseContext);
            }
            bodyArgument = responseContext.getBodyArgument();
            body = responseContext.getEntity();
            delegateEntityStream = responseContext.getDelegateEntityStream();
            customEntityStream = responseContext.getCustomEntityStream();
        }
        if (jaxRsResponse) {
            resolveRelativeLocation(request, mutableHttpResponse);
        }
        applyHeadContentType(routeInfo, request, mutableHttpResponse);
        if (body != null) {
            applyDefaultStringContentType(routeInfo, request, mutableHttpResponse, body);
            mutableHttpResponse.body(new JaxRsGenericEntity<>(
                body,
                (Argument<? super Object>) bodyArgument,
                delegateEntityStream,
                customEntityStream)
            );
        }
        sanitizeResponseContentType(mutableHttpResponse);
        return mutableHttpResponse;
    }

    @SuppressWarnings("unchecked")
    private void applySelectedVariantVary(HttpRequest<?> request, MutableHttpResponse<?> response) {
        request.getAttribute(JaxRsContextRequest.SELECT_VARIANT_VARY, List.class)
            .ifPresent(varyHeaders -> ((List<String>) varyHeaders).stream()
                .filter(varyHeader -> !containsVaryHeader(response, varyHeader))
                .forEach(varyHeader -> response.getHeaders().add(HttpHeaders.VARY, varyHeader)));
    }

    private static boolean containsVaryHeader(MutableHttpResponse<?> response, String expected) {
        return response.getHeaders().getAll(HttpHeaders.VARY)
            .stream()
            .flatMap(value -> Arrays.stream(value.split(",")))
            .map(String::trim)
            .anyMatch(expected::equalsIgnoreCase);
    }

    private void applyHeadContentType(@Nullable RouteInfo<?> routeInfo,
                                      HttpRequest<?> request,
                                      MutableHttpResponse<?> response) {
        if (request.getMethod() != HttpMethod.HEAD ||
            routeInfo == null ||
            response.getHeaders().getContentType().isPresent()) {
            return;
        }
        io.micronaut.http.MediaType contentType = singleConcreteProducedMediaType(routeInfo);
        if (contentType != null) {
            response.contentType(contentType);
        }
    }

    private void applyDefaultStringContentType(@Nullable RouteInfo<?> routeInfo,
                                               HttpRequest<?> request,
                                               MutableHttpResponse<?> response,
                                               Object body) {
        if (!(body instanceof String) ||
            routeInfo == null ||
            response.getHeaders().getContentType().isPresent() ||
            !producesOnlyWildcard(routeInfo)) {
            return;
        }
        JaxRsHttpHeaders.forRequest(request.getHeaders())
            .getAcceptableMediaTypes()
            .stream()
            .filter(type -> !type.isWildcardType() && !type.isWildcardSubtype())
            .findFirst()
            .map(this::withoutSelectionParameters)
            .map(JaxRsUtils::convert)
            .ifPresent(response::contentType);
    }

    private io.micronaut.http.@Nullable MediaType singleConcreteProducedMediaType(RouteInfo<?> routeInfo) {
        String[] producedMediaTypes = routeInfo.getAnnotationMetadata().stringValues(Produces.class);
        if (producedMediaTypes.length != 1) {
            return null;
        }
        MediaType mediaType = withoutSelectionParameters(MediaType.valueOf(producedMediaTypes[0]));
        if (mediaType.isWildcardType() || mediaType.isWildcardSubtype()) {
            return null;
        }
        return JaxRsUtils.convert(mediaType);
    }

    private void sanitizeResponseContentType(MutableHttpResponse<?> response) {
        response.getHeaders().getContentType()
            .map(io.micronaut.http.MediaType::of)
            .map(this::withoutSelectionParameters)
            .ifPresent(response::contentType);
    }

    private io.micronaut.http.MediaType withoutSelectionParameters(io.micronaut.http.MediaType mediaType) {
        Map<CharSequence, String> source = mediaType.getParametersMap();
        if (source.keySet().stream().noneMatch(JaxRsFilters::isSelectionParameter)) {
            return mediaType;
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        source.forEach((name, value) -> {
            if (!isSelectionParameter(name)) {
                parameters.put(name.toString(), value);
            }
        });
        return new io.micronaut.http.MediaType(mediaType.getName(), parameters);
    }

    private MediaType withoutSelectionParameters(MediaType mediaType) {
        if (mediaType.getParameters().keySet().stream().noneMatch(JaxRsFilters::isSelectionParameter)) {
            return mediaType;
        }
        Map<String, String> parameters = new LinkedHashMap<>(mediaType.getParameters());
        parameters.keySet().removeIf(JaxRsFilters::isSelectionParameter);
        return new MediaType(mediaType.getType(), mediaType.getSubtype(), parameters);
    }

    private static boolean isSelectionParameter(CharSequence name) {
        String parameterName = name.toString();
        return CLIENT_QUALITY_PARAMETER.equalsIgnoreCase(parameterName) ||
            SERVER_QUALITY_PARAMETER.equalsIgnoreCase(parameterName);
    }

    private boolean producesOnlyWildcard(RouteInfo<?> routeInfo) {
        String[] producedMediaTypes = routeInfo.getAnnotationMetadata().stringValues(Produces.class);
        return producedMediaTypes.length > 0 &&
            Arrays.stream(producedMediaTypes).allMatch(io.micronaut.http.MediaType.ALL::equals);
    }

    @Nullable
    @io.micronaut.http.server.annotation.PreMatching
    @RequestFilter
    HttpResponse<?> filterPreMatchingRequest(MutableHttpRequest<?> request) throws IOException {
        applyJaxRsAcceptNegotiation(request);
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

    private void applyJaxRsAcceptNegotiation(MutableHttpRequest<?> request) {
        List<UriRouteInfo<?, ?>> candidates = jaxRsRouteCandidates(request);
        if (candidates.isEmpty()) {
            return;
        }
        List<MediaType> acceptableMediaTypes = JaxRsHttpHeaders.forRequest(request.getHeaders()).getAcceptableMediaTypes();
        List<AcceptCandidate> compatibleCandidates = candidates.stream()
            .flatMap(route -> route.getProduces().stream()
                .map(JaxRsUtils::convert)
                .flatMap(produced -> acceptableMediaTypes.stream()
                    .filter(produced::isCompatible)
                    .map(accepted -> new AcceptCandidate(produced, accepted))))
            .toList();
        if (compatibleCandidates.isEmpty()) {
            return;
        }
        if (compatibleCandidates.stream().anyMatch(AcceptCandidate::hasTypedWildcardProduced) &&
            compatibleCandidates.stream().noneMatch(AcceptCandidate::hasConcreteResponseMediaType)) {
            throw new NotAcceptableException(
                acceptableMediaTypes.stream().map(MediaType::toString).toList(),
                candidates.stream()
                    .flatMap(route -> route.getProduces().stream())
                    .map(io.micronaut.http.MediaType::toString)
                    .toList()
            );
        }
        if (candidates.size() < 2) {
            return;
        }
        compatibleCandidates.stream()
            .filter(AcceptCandidate::hasConcreteProducedMediaType)
            .max(AcceptCandidate.COMPARATOR)
            .map(AcceptCandidate::produced)
            .map(this::withoutSelectionParameters)
            .map(JaxRsUtils::convert)
            .ifPresent(mediaType -> {
                request.getHeaders().remove(HttpHeaders.ACCEPT);
                request.accept(mediaType);
            });
    }

    private List<UriRouteInfo<?, ?>> jaxRsRouteCandidates(MutableHttpRequest<?> request) {
        List<UriRouteInfo<?, ?>> candidates = router.uriRoutes()
            .filter(route -> route.getHttpMethod() == request.getMethod())
            .filter(route -> route.getAnnotationMetadata().hasAnnotation(Path.class))
            .filter(route -> route.tryMatch(request.getPath()) != null)
            .filter(route -> route.consumesAll() || route.doesConsume(request.getContentType().orElse(null)))
            .toList();
        if (candidates.size() < 2) {
            return candidates;
        }
        JaxRsRouteScore bestScore = candidates.stream()
            .map(JaxRsFilters::score)
            .max(JaxRsRouteScore.COMPARATOR)
            .orElse(null);
        if (bestScore == null) {
            return candidates;
        }
        return candidates.stream()
            .filter(route -> score(route).equals(bestScore))
            .toList();
    }

    private static JaxRsRouteScore score(UriRouteInfo<?, ?> route) {
        return JaxRsRouteScore.of(route.getUriMatchTemplate().toString());
    }

    private record AcceptCandidate(MediaType produced, MediaType accepted) {
        private static final Comparator<AcceptCandidate> COMPARATOR = Comparator
            .comparingInt(AcceptCandidate::producedSpecificity)
            .thenComparingDouble(AcceptCandidate::clientQuality)
            .thenComparingDouble(AcceptCandidate::serverQuality);

        private boolean hasConcreteResponseMediaType() {
            return concreteResponseMediaType() != null;
        }

        private boolean hasConcreteProducedMediaType() {
            return isConcrete(produced);
        }

        private boolean hasTypedWildcardProduced() {
            return !produced.isWildcardType() && produced.isWildcardSubtype();
        }

        @Nullable
        private MediaType concreteResponseMediaType() {
            if (isConcrete(produced)) {
                return produced;
            }
            if (isConcrete(accepted)) {
                return accepted;
            }
            return null;
        }

        private static boolean isConcrete(MediaType mediaType) {
            return !mediaType.isWildcardType() && !mediaType.isWildcardSubtype();
        }

        private int producedSpecificity() {
            return specificity(produced);
        }

        private double clientQuality() {
            return quality(accepted, CLIENT_QUALITY_PARAMETER);
        }

        private double serverQuality() {
            return quality(produced, SERVER_QUALITY_PARAMETER);
        }

        private static int specificity(MediaType mediaType) {
            if (mediaType.isWildcardType()) {
                return 0;
            }
            if (mediaType.isWildcardSubtype()) {
                return 1;
            }
            return 2;
        }

        private static double quality(MediaType mediaType, String parameterName) {
            String value = mediaType.getParameters().get(parameterName);
            if (value == null) {
                return 1.0;
            }
            return Double.parseDouble(value);
        }
    }

    private void resolveRelativeLocation(HttpRequest<?> request, MutableHttpResponse<?> response) {
        response.getHeaders().getFirst(HttpHeaders.LOCATION)
            .map(URI::create)
            .filter(location -> !location.isAbsolute())
            .map(location -> applicationBaseUri(request).resolve(location))
            .ifPresent(response.getHeaders()::location);
    }

    private URI applicationBaseUri(HttpRequest<?> request) {
        URI requestUri = request.getUri();
        String basePath = applicationBasePath();
        String scheme = requestUri.getScheme();
        String host = requestUri.getHost();
        int port = requestUri.getPort();
        if (scheme == null || host == null) {
            scheme = request.isSecure() ? HttpRequest.SCHEME_HTTPS : HttpRequest.SCHEME_HTTP;
            host = request.getServerName();
            InetSocketAddress serverAddress = request.getServerAddress();
            if ((host == null || host.isBlank()) && serverAddress != null) {
                host = serverAddress.getHostString();
            }
            if (port < 0 && serverAddress != null) {
                port = serverAddress.getPort();
            }
        }
        if (host == null || host.isBlank()) {
            return URI.create(basePath);
        }
        try {
            return new URI(scheme, requestUri.getUserInfo(), host, port, basePath, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Unexpected URI format: " + requestUri.toASCIIString(), e);
        }
    }

    private String applicationBasePath() {
        String basePath = applicationProvider.getPath();
        if (basePath.isEmpty() || basePath.equals("/")) {
            return "/";
        }
        if (!basePath.startsWith("/")) {
            basePath = "/" + basePath;
        }
        if (!basePath.endsWith("/")) {
            basePath += "/";
        }
        return basePath;
    }

    @Nullable
    @RequestFilter
    HttpResponse<?> filterRequest(RouteInfo<?> routeInfo, MutableHttpRequest<?> request) throws IOException {
        if (requestFilters.isEmpty() || !routeInfo.getAnnotationMetadata().hasAnnotation(Path.class)) {
            // Intercept only JaxRs routes
            return null;
        }
        JaxRsContainerRequestContext requestContext = new JaxRsContainerRequestContext(request, applicationProvider);
        if (!containerResponseFilters.isEmpty()) {
            request.setAttribute(REQUEST_CONTEXT_KEY, requestContext);
        }
        request.setAttribute(HttpHeadersBinder.HEADERS_KEY, request.getHeaders());
        List<ContainerRequestFilter> filters = requestFilters.stream()
            .filter(br -> nameBindingPredicate.test(br.getBeanDefinition()))
            .map(BeanRegistration::getBean)
            .toList();
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
