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
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
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
import io.micronaut.http.simple.SimpleHttpHeaders;
import io.micronaut.http.server.exceptions.NotAcceptableException;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.jaxrs.common.JaxRsArgumentUtil;
import io.micronaut.jaxrs.common.JaxRsGenericEntity;
import io.micronaut.jaxrs.common.JaxRsHttpHeaders;
import io.micronaut.jaxrs.common.JaxRsMutableResponse;
import io.micronaut.jaxrs.common.JaxRsResponse;
import io.micronaut.jaxrs.common.JaxRsRouteScore;
import io.micronaut.jaxrs.common.JaxRsResourceTemplateMetadata;
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
import jakarta.ws.rs.ext.WriterInterceptor;
import org.jspecify.annotations.Nullable;

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
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    private final ApplicationProvider applicationProvider;
    private final List<UriRouteInfo<?, ?>> jaxRsRoutes;
    private final Map<HttpMethod, List<UriRouteInfo<?, ?>>> jaxRsRoutesByMethod;
    private final Map<UriRouteInfo<?, ?>, JaxRsRouteScore> routeScores;
    private final List<ContainerRequestFilter> preMatchingRequestFilters;
    private final List<BeanRegistration<ContainerRequestFilter>> requestFilters;
    private final List<BeanRegistration<ContainerResponseFilter>> containerResponseFilters;
    private final NameBindingPredicate nameBindingPredicate;
    private final JaxRsDynamicFeatureRegistry dynamicFeatureRegistry;

    JaxRsFilters(Router router,
                 ApplicationProvider applicationProvider,
                 List<BeanRegistration<ContainerRequestFilter>> requestFilters,
                 List<BeanRegistration<ContainerResponseFilter>> containerResponseFilters,
                 NameBindingPredicate nameBindingPredicate,
                 JaxRsDynamicFeatureRegistry dynamicFeatureRegistry) {
        this.applicationProvider = applicationProvider;
        this.nameBindingPredicate = nameBindingPredicate;
        this.dynamicFeatureRegistry = dynamicFeatureRegistry;
        this.jaxRsRoutes = router.uriRoutes()
            .filter(JaxRsFilters::isJaxRsRoute)
            .filter(this::isApplicationRoute)
            .toList();
        // Build method and score indexes once. Request and response filters ask
        // these questions frequently, so avoid route scans in the hot path.
        Map<HttpMethod, List<UriRouteInfo<?, ?>>> routesByMethod = new LinkedHashMap<>();
        Map<UriRouteInfo<?, ?>, JaxRsRouteScore> scoresByRoute = new LinkedHashMap<>();
        for (UriRouteInfo<?, ?> route : this.jaxRsRoutes) {
            routesByMethod.computeIfAbsent(route.getHttpMethod(), ignored -> new ArrayList<>()).add(route);
            scoresByRoute.put(route, computeScore(route));
        }
        Map<HttpMethod, List<UriRouteInfo<?, ?>>> immutableRoutesByMethod = new LinkedHashMap<>();
        routesByMethod.forEach((method, routes) -> immutableRoutesByMethod.put(method, List.copyOf(routes)));
        this.jaxRsRoutesByMethod = Map.copyOf(immutableRoutesByMethod);
        this.routeScores = Map.copyOf(scoresByRoute);
        Map<Boolean, List<BeanRegistration<ContainerRequestFilter>>> matching = requestFilters.stream().collect(Collectors.groupingBy(br -> br.getBeanDefinition().hasAnnotation(PreMatching.class)));
        this.preMatchingRequestFilters = new ArrayList<>(matching.getOrDefault(true, List.of()).stream().map(BeanRegistration::getBean).toList());
        this.preMatchingRequestFilters.addAll(dynamicFeatureRegistry.globalComponents().preMatchingRequestFilters());
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
        if (request.getAttribute(JaxRsEntityArgumentBinder.NO_CONTENT_BAD_REQUEST, Boolean.class).orElse(false)) {
            return HttpResponse.badRequest();
        }
        if (routeInfo != null && !isJaxRsRoute(routeInfo)) {
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
        } else if (body instanceof Response jaxRsResponseBody) {
            jaxRsResponse = true;
            mutableHttpResponse = unwrapResponse(jaxRsResponseBody, mutableHttpResponse);
            body = mutableHttpResponse.getBody().orElse(null);
        }
        if (jaxRsResponse) {
            resolveRelativeLocation(request, mutableHttpResponse);
        }
        applySelectedVariantVary(request, mutableHttpResponse);
        Argument<?> bodyArgument;
        List<WriterInterceptor> writerInterceptors = List.of();
        if (body instanceof JaxRsGenericEntity<?> genericEntity) {
            bodyArgument = genericEntity.asArgument();
            writerInterceptors = genericEntity.getWriterInterceptors();
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
        JaxRsDynamicFeatureRegistry.DynamicComponents dynamicComponents =
            routeInfo == null && applicationProvider.isApplicationRequest(request)
                ? dynamicFeatureRegistry.globalComponents()
                : routeInfo == null ? JaxRsDynamicFeatureRegistry.EMPTY : dynamicFeatureRegistry.components(routeInfo);
        if (!containerResponseFilters.isEmpty() || !dynamicComponents.responseFilters().isEmpty()) {
            JaxRsContainerRequestContext requestContext = request.getAttribute(REQUEST_CONTEXT_KEY, JaxRsContainerRequestContext.class)
                .orElseGet(() -> new JaxRsContainerRequestContext(request.mutate(), applicationProvider));
            requestContext.finished();
            JaxRsContainerResponseContext responseContext = new JaxRsContainerResponseContext(mutableHttpResponse, bodyArgument);
            List<ContainerResponseFilter> filters = new ArrayList<>(containerResponseFilters.stream()
                .filter(br -> nameBindingPredicate.test(br.getBeanDefinition()))
                .map(BeanRegistration::getBean)
                .toList());
            filters.addAll(dynamicComponents.responseFilters());
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
        Optional<JaxRsSseEventSink> sseEventSink = sseEventSink(request, mutableHttpResponse);
        if (sseEventSink.isPresent()) {
            // SSE responses are streamed by the sink itself; remove any buffered
            // entity metadata that Micronaut may have inferred before this filter.
            mutableHttpResponse.contentType(io.micronaut.http.MediaType.TEXT_EVENT_STREAM_TYPE);
            mutableHttpResponse.getHeaders().remove(HttpHeaders.CONTENT_LENGTH);
            mutableHttpResponse.body(sseEventSink.get());
            sanitizeResponseContentType(mutableHttpResponse);
            return mutableHttpResponse;
        }
        if (body != null) {
            applyDefaultStringContentType(routeInfo, request, mutableHttpResponse, body);
            if (!dynamicComponents.writerInterceptors().isEmpty()) {
                writerInterceptors = merge(writerInterceptors, dynamicComponents.writerInterceptors());
            }
            mutableHttpResponse.body(new JaxRsGenericEntity<>(
                body,
                (Argument<? super Object>) bodyArgument,
                delegateEntityStream,
                customEntityStream,
                writerInterceptors)
            );
        }
        sanitizeResponseContentType(mutableHttpResponse);
        return mutableHttpResponse;
    }

    private static Optional<JaxRsSseEventSink> sseEventSink(HttpRequest<?> request,
                                                            MutableHttpResponse<?> response) {
        if (response.code() < 200 || response.code() > 299) {
            return Optional.empty();
        }
        return request.getAttribute(JaxRsSseEventSink.ATTRIBUTE, JaxRsSseEventSink.class);
    }

    private static List<WriterInterceptor> merge(List<WriterInterceptor> existing, List<WriterInterceptor> additional) {
        if (existing.isEmpty()) {
            return additional;
        }
        List<WriterInterceptor> merged = new ArrayList<>(existing);
        merged.addAll(additional);
        JaxRsUtils.sortByPriority(merged);
        return merged;
    }

    private static MutableHttpResponse<?> unwrapResponse(Response response, MutableHttpResponse<?> outerResponse) {
        Response.StatusType statusInfo = response.getStatusInfo();
        MutableHttpResponse<Object> unwrappedResponse = HttpResponse.status(statusInfo.getStatusCode(), statusInfo.getReasonPhrase());
        outerResponse.getAttributes().forEach(unwrappedResponse::setAttribute);
        outerResponse.getHeaders().forEach((name, values) -> {
            for (String value : values) {
                unwrappedResponse.header(name, value);
            }
        });
        response.getHeaders().forEach((name, values) -> {
            for (Object value : values) {
                unwrappedResponse.header(name, value.toString());
            }
        });
        if (response.hasEntity()) {
            unwrappedResponse.body(response.getEntity());
        }
        return unwrappedResponse;
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
        if (preMatchingRequestFilters.isEmpty() || !applicationProvider.isApplicationRequest(request)) {
            // Intercept only JaxRs routes
            return null;
        }
        JaxRsContainerRequestContext requestContext = new JaxRsContainerRequestContext(request, applicationProvider, true);
        for (ContainerRequestFilter preMatchingRequestFilter : preMatchingRequestFilters) {
            preMatchingRequestFilter.filter(requestContext);
            Response response = requestContext.getResponse();
            if (response != null) {
                if (response instanceof JaxRsResponse jaxRsResponse) {
                    return jaxRsResponse.getResponse();
                }
                return unwrapResponse(response, HttpResponse.ok());
            }
        }
        requestContext.finished();
        return null;
    }

    private void applyJaxRsAcceptNegotiation(MutableHttpRequest<?> request) {
        if (request.getHeaders().getAll(HttpHeaders.ACCEPT).isEmpty()) {
            return;
        }
        List<UriRouteInfo<?, ?>> candidates = jaxRsRouteCandidates(request);
        if (candidates.isEmpty()) {
            return;
        }
        List<MediaType> acceptableMediaTypes = JaxRsHttpHeaders.forRequest(request.getHeaders()).getAcceptableMediaTypes();
        List<AcceptCandidate> compatibleCandidates = candidates.stream()
            .flatMap(route -> producedMediaTypes(route)
                .map(JaxRsUtils::convert)
                .flatMap(produced -> acceptableMediaTypes.stream()
                    .filter(produced::isCompatible)
                    .map(accepted -> new AcceptCandidate(produced, accepted))))
            .toList();
        if (compatibleCandidates.isEmpty()) {
            throw new NotAcceptableException(
                acceptableMediaTypes.stream().map(MediaType::toString).toList(),
                candidates.stream()
                    .flatMap(JaxRsFilters::producedMediaTypes)
                    .map(io.micronaut.http.MediaType::toString)
                    .toList()
            );
        }
        if (compatibleCandidates.stream().anyMatch(AcceptCandidate::hasTypedWildcardProduced) &&
            compatibleCandidates.stream().noneMatch(AcceptCandidate::hasConcreteResponseMediaType)) {
            throw new NotAcceptableException(
                acceptableMediaTypes.stream().map(MediaType::toString).toList(),
                candidates.stream()
                    .flatMap(JaxRsFilters::producedMediaTypes)
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
                preserveOriginalHeaders(request);
                request.getHeaders().remove(HttpHeaders.ACCEPT);
                request.accept(mediaType);
            });
    }

    private static void preserveOriginalHeaders(MutableHttpRequest<?> request) {
        if (request.getAttribute(HttpHeadersBinder.HEADERS_KEY).isPresent()) {
            return;
        }
        SimpleHttpHeaders headers = new SimpleHttpHeaders();
        request.getHeaders().forEach((name, values) -> {
            for (String value : values) {
                headers.add(name, value);
            }
        });
        request.setAttribute(HttpHeadersBinder.HEADERS_KEY, headers);
    }

    private List<UriRouteInfo<?, ?>> jaxRsRouteCandidates(MutableHttpRequest<?> request) {
        if (jaxRsRoutes.isEmpty() || !applicationProvider.isApplicationRequest(request)) {
            return List.of();
        }
        List<UriRouteInfo<?, ?>> candidates = jaxRsRouteCandidates(request, request.getPath());
        if (candidates.isEmpty()) {
            candidates = jaxRsRouteCandidates(request, stripApplicationPath(request.getPath(), applicationProvider.getPath()));
        }
        if (candidates.isEmpty()) {
            candidates = jaxRsRouteCandidates(request, stripApplicationPath(request.getPath(), applicationProvider.getApplicationPath()));
        }
        if (candidates.size() < 2) {
            return candidates;
        }
        JaxRsRouteScore bestScore = candidates.stream()
            .map(this::score)
            .max(JaxRsRouteScore.COMPARATOR)
            .orElse(null);
        if (bestScore == null) {
            return candidates;
        }
        return candidates.stream()
            .filter(route -> score(route).equals(bestScore))
            .toList();
    }

    private List<UriRouteInfo<?, ?>> jaxRsRouteCandidates(MutableHttpRequest<?> request, String path) {
        List<UriRouteInfo<?, ?>> methodRoutes = jaxRsRoutesByMethod.get(request.getMethod());
        if (methodRoutes == null) {
            return List.of();
        }
        return methodRoutes.stream()
            .filter(route -> route.tryMatch(path) != null)
            .filter(route -> route.consumesAll() || route.doesConsume(request.getContentType().orElse(null)))
            .toList();
    }

    private JaxRsRouteScore score(UriRouteInfo<?, ?> route) {
        return routeScores.getOrDefault(route, computeScore(route));
    }

    private static JaxRsRouteScore computeScore(UriRouteInfo<?, ?> route) {
        AnnotationMetadata annotationMetadata = route.getAnnotationMetadata();
        OptionalInt literalCharacters = annotationMetadata.intValue(JaxRsResourceTemplate.class, JaxRsResourceTemplateMetadata.MEMBER_LITERAL_CHARACTERS);
        OptionalInt capturingGroups = annotationMetadata.intValue(JaxRsResourceTemplate.class, JaxRsResourceTemplateMetadata.MEMBER_CAPTURING_GROUPS);
        OptionalInt nonDefaultCapturingGroups = annotationMetadata.intValue(JaxRsResourceTemplate.class, JaxRsResourceTemplateMetadata.MEMBER_NON_DEFAULT_CAPTURING_GROUPS);
        if (literalCharacters.isPresent() && capturingGroups.isPresent() && nonDefaultCapturingGroups.isPresent() && literalCharacters.getAsInt() > -1) {
            return new JaxRsRouteScore(
                literalCharacters.getAsInt(),
                capturingGroups.getAsInt(),
                nonDefaultCapturingGroups.getAsInt()
            );
        }
        return JaxRsRouteScore.of(route.getUriMatchTemplate().toString());
    }

    private static String stripApplicationPath(String uri, String applicationPath) {
        if ("/".equals(applicationPath) || !uri.startsWith(applicationPath)) {
            return uri;
        }
        int prefixLength = applicationPath.length();
        if (uri.length() == prefixLength) {
            return "/";
        }
        if (uri.charAt(prefixLength) == '/') {
            return uri.substring(prefixLength);
        }
        return uri;
    }

    private static Stream<io.micronaut.http.MediaType> producedMediaTypes(RouteInfo<?> routeInfo) {
        List<io.micronaut.http.MediaType> producedMediaTypes = routeInfo.getProduces();
        if (producedMediaTypes.isEmpty()) {
            return Stream.of(io.micronaut.http.MediaType.ALL_TYPE);
        }
        return producedMediaTypes.stream();
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
        if (!isJaxRsRoute(routeInfo)) {
            // Intercept only JaxRs routes
            return null;
        }
        if (!isApplicationRoute(routeInfo)) {
            return HttpResponse.notFound();
        }
        if (request.getAttribute(JaxRsEntityArgumentBinder.NO_CONTENT_BAD_REQUEST, Boolean.class).orElse(false)) {
            return HttpResponse.badRequest();
        }
        // DynamicFeature registrations are route-scoped. They are cached by the
        // registry, so this lookup is cheap after the first request for a route.
        JaxRsDynamicFeatureRegistry.DynamicComponents dynamicComponents = dynamicFeatureRegistry.components(routeInfo);
        if (requestFilters.isEmpty() && dynamicComponents.requestFilters().isEmpty()) {
            return null;
        }
        JaxRsContainerRequestContext requestContext = new JaxRsContainerRequestContext(request, applicationProvider);
        if (!containerResponseFilters.isEmpty() || !dynamicComponents.responseFilters().isEmpty()) {
            request.setAttribute(REQUEST_CONTEXT_KEY, requestContext);
        }
        if (request.getAttribute(HttpHeadersBinder.HEADERS_KEY).isEmpty()) {
            request.setAttribute(HttpHeadersBinder.HEADERS_KEY, request.getHeaders());
        }
        List<ContainerRequestFilter> filters = new ArrayList<>(requestFilters.stream()
            .filter(br -> nameBindingPredicate.test(br.getBeanDefinition()))
            .map(BeanRegistration::getBean)
            .toList());
        filters.addAll(dynamicComponents.requestFilters());
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

    private static boolean isJaxRsRoute(RouteInfo<?> routeInfo) {
        return routeInfo.getAnnotationMetadata().hasAnnotation(Path.class);
    }

    private boolean isApplicationRoute(RouteInfo<?> routeInfo) {
        String resourceClassName = routeInfo.getAnnotationMetadata()
            .stringValue(JaxRsResourceTemplate.class, JaxRsResourceTemplateMetadata.MEMBER_ROOT_CLASS_NAME)
            .orElseGet(() -> routeInfo.getDeclaringType().getName());
        return applicationProvider.isApplicationResource(resourceClassName);
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

}
