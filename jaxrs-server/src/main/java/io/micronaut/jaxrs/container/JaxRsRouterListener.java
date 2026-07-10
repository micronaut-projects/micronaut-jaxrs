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
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.bind.ArgumentBinder;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.exceptions.ConversionErrorException;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.ReturnType;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.uri.UriMatchTemplate;
import io.micronaut.http.bind.RequestBinderRegistry;
import io.micronaut.http.uri.UriMatchVariable;
import io.micronaut.jaxrs.common.JaxRsRouteScore;
import io.micronaut.jaxrs.common.JaxRsResourceTemplateMetadata;
import io.micronaut.jaxrs.common.JaxRsSubResourceLocatorMetadata;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.web.router.Router;
import io.micronaut.web.router.UriRouteInfo;
import io.micronaut.web.router.UriRouteMatch;
import io.micronaut.web.router.exceptions.UnsatisfiedRouteException;
import io.micronaut.web.router.filter.FilteredRouter;
import io.micronaut.web.router.filter.RouteMatchFilter;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Applies Jakarta REST route matching tie-breakers to JAX-RS routes.
 */
@Internal
@Singleton
final class JaxRsRouterListener implements BeanCreatedEventListener<Router> {

    private static final RouteMatchFilter NO_ROUTE_MATCH_FILTER = new RouteMatchFilter() {
        @Override
        public <T, R> Predicate<UriRouteMatch<T, R>> filter(HttpRequest<?> request) {
            return ignored -> true;
        }
    };

    private final BeanContext beanContext;
    private final RequestBinderRegistry requestBinderRegistry;
    private final ApplicationProvider applicationProvider;

    JaxRsRouterListener(BeanContext beanContext,
                        RequestBinderRegistry requestBinderRegistry,
                        ApplicationProvider applicationProvider) {
        this.beanContext = beanContext;
        this.requestBinderRegistry = requestBinderRegistry;
        this.applicationProvider = applicationProvider;
    }

    @Override
    public Router onCreated(BeanCreatedEvent<Router> event) {
        Router router = event.getBean();
        return new JaxRsFilteredRouter(router, beanContext, requestBinderRegistry, applicationProvider);
    }

    private static final class JaxRsFilteredRouter extends FilteredRouter {
        private final Router router;
        private final BeanContext beanContext;
        private final RequestBinderRegistry requestBinderRegistry;
        private final ApplicationProvider applicationProvider;
        private final List<UriRouteInfo<?, ?>> jaxRsRoutes;
        private final List<UriRouteInfo<?, ?>> applicationJaxRsRoutes;
        private final Map<UriRouteInfo<?, ?>, JaxRsRouteMetadata> routeMetadataByRoute;
        private final Map<UriRouteInfo<?, ?>, RootResourceTemplate> rootTemplateByRoute;
        private final List<RootResourceTemplate> rootResourceTemplates;

        private JaxRsFilteredRouter(Router router,
                                    BeanContext beanContext,
                                    RequestBinderRegistry requestBinderRegistry,
                                    ApplicationProvider applicationProvider) {
            super(router, NO_ROUTE_MATCH_FILTER);
            this.router = router;
            this.beanContext = beanContext;
            this.requestBinderRegistry = requestBinderRegistry;
            this.applicationProvider = applicationProvider;
            this.jaxRsRoutes = router.uriRoutes()
                .filter(JaxRsFilteredRouter::hasJaxRsRouteAnnotation)
                .toList();
            // Keep the request hot path metadata-only: the visitor precomputes the
            // route scores, root templates, media types and subresource flags that
            // are needed for Jakarta REST tie-breakers.
            this.routeMetadataByRoute = routeMetadata(this.jaxRsRoutes);
            RouteRootTemplates routeRootTemplates = routeRootTemplates(this.jaxRsRoutes);
            this.rootTemplateByRoute = routeRootTemplates.byRoute();
            this.rootResourceTemplates = routeRootTemplates.roots();
            this.applicationJaxRsRoutes = List.copyOf(this.rootTemplateByRoute.keySet());
        }

        private static Map<UriRouteInfo<?, ?>, JaxRsRouteMetadata> routeMetadata(List<UriRouteInfo<?, ?>> routes) {
            Map<UriRouteInfo<?, ?>, JaxRsRouteMetadata> metadata = new LinkedHashMap<>();
            for (UriRouteInfo<?, ?> route : routes) {
                metadata.put(route, routeMetadata(route));
            }
            return Map.copyOf(metadata);
        }

        private static JaxRsRouteMetadata routeMetadata(UriRouteInfo<?, ?> route) {
            AnnotationMetadata annotationMetadata = route.getAnnotationMetadata();
            boolean subResourceLocator = annotationMetadata.hasAnnotation(JaxRsSubResourceLocator.class);
            boolean dynamicSubResourceLocator = annotationMetadata
                .booleanValue(JaxRsSubResourceLocator.class, JaxRsSubResourceLocatorMetadata.MEMBER_DYNAMIC)
                .orElse(false);
            String[] targetHttpMethods = annotationMetadata
                .stringValues(JaxRsSubResourceLocator.class, JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_HTTP_METHODS);
            Set<String> targetHttpMethodSet = Set.copyOf(List.of(targetHttpMethods));
            RouteSelectionScore routeSelectionScore = selectionScore(route, annotationMetadata);
            return new JaxRsRouteMetadata(
                subResourceLocator,
                dynamicSubResourceLocator,
                route.getHttpMethodName(),
                targetHttpMethodSet,
                route.getHttpMethod() == HttpMethod.HEAD && subResourceLocator && targetHttpMethodSet.contains(HttpMethod.GET.name()),
                MediaType.of(annotationMetadata.stringValues(Consumes.class)),
                MediaType.of(annotationMetadata.stringValues(Produces.class)),
                routeSelectionScore
            );
        }

        private RouteRootTemplates routeRootTemplates(List<UriRouteInfo<?, ?>> routes) {
            Map<UriRouteInfo<?, ?>, RootResourceTemplate> byRoute = new LinkedHashMap<>();
            Map<String, RootResourceTemplate> roots = new LinkedHashMap<>();
            for (UriRouteInfo<?, ?> route : routes) {
                RootResourceTemplate rootResourceTemplate = rootResourceTemplate(route);
                if (rootResourceTemplate == null) {
                    continue;
                }
                byRoute.put(route, rootResourceTemplate);
                roots.putIfAbsent(rootResourceTemplate.key(), rootResourceTemplate);
            }
            return new RouteRootTemplates(Map.copyOf(byRoute), List.copyOf(roots.values()));
        }

        private @Nullable RootResourceTemplate rootResourceTemplate(UriRouteInfo<?, ?> route) {
            AnnotationMetadata annotationMetadata = route.getAnnotationMetadata();
            String rootClassName = annotationMetadata
                .stringValue(JaxRsResourceTemplate.class, JaxRsResourceTemplateMetadata.MEMBER_ROOT_CLASS_NAME)
                .filter(name -> !name.isEmpty())
                .orElseGet(() -> route.getDeclaringType().getName());
            if (!applicationProvider.isApplicationResource(rootClassName)) {
                return null;
            }
            String rootTemplate = annotationMetadata
                .stringValue(JaxRsResourceTemplate.class, JaxRsResourceTemplateMetadata.MEMBER_ROOT_TEMPLATE)
                .filter(template -> !template.isEmpty())
                .orElseGet(() -> rootTemplate(annotationMetadata, route));
            JaxRsRouteScore rootScore = score(
                annotationMetadata,
                JaxRsResourceTemplateMetadata.MEMBER_ROOT_LITERAL_CHARACTERS,
                JaxRsResourceTemplateMetadata.MEMBER_ROOT_CAPTURING_GROUPS,
                JaxRsResourceTemplateMetadata.MEMBER_ROOT_NON_DEFAULT_CAPTURING_GROUPS
            ).orElseGet(() -> JaxRsRouteScore.of(rootTemplate));
            return new RootResourceTemplate(rootClassName, normalizeRoutePath(rootTemplate), rootScore);
        }

        @Override
        public <T, R> Stream<UriRouteMatch<T, R>> findAny(CharSequence uri, @Nullable HttpRequest<?> context) {
            Stream<UriRouteMatch<T, R>> matches = router.findAny(uri, context);
            if (context == null) {
                return unfilteredMatrixAwareMatches(uri, matches, strippedUri -> router.<T, R>findAny(strippedUri, null));
            }
            JaxRsRequestMethod requestMethod = JaxRsRequestMethod.forRequest(context);
            return wrap(
                filteredMatrixAwareMatches(
                    uri,
                    matches,
                    strippedUri -> router.<T, R>findAny(strippedUri, context),
                    context,
                    requestMethod,
                    false,
                    false
                ).stream(),
                context
            );
        }

        @Override
        public <T, R> List<UriRouteMatch<T, R>> findAny(HttpRequest<?> request) {
            CharSequence uri = routeUri(request);
            JaxRsRequestMethod requestMethod = JaxRsRequestMethod.forRequest(request);
            return wrap(
                filteredMatrixAwareMatches(
                    uri,
                    findAny(request, uri),
                    strippedUri -> router.<T, R>findAny(strippedUri, request),
                    request,
                    requestMethod,
                    false,
                    false
                ).stream(),
                request
            ).toList();
        }

        @Override
        public <T, R> Stream<UriRouteMatch<T, R>> find(HttpMethod httpMethod, CharSequence uri, @Nullable HttpRequest<?> context) {
            Stream<UriRouteMatch<T, R>> matches = router.find(httpMethod, uri, context);
            if (context == null) {
                return unfilteredMatrixAwareMatches(uri, matches, strippedUri -> router.<T, R>find(httpMethod, strippedUri, null));
            }
            JaxRsRequestMethod requestMethod = JaxRsRequestMethod.forRequest(context);
            return wrap(
                withSubResourceLocatorFallback(
                    context,
                    requestMethod,
                    uri,
                    filteredMatrixAwareMatches(
                        uri,
                        matches,
                        strippedUri -> router.<T, R>find(httpMethod, strippedUri, context),
                        context,
                        requestMethod,
                        true,
                        true
                    )
                ).stream(),
                context
            );
        }

        @Override
        public <T, R> List<UriRouteMatch<T, R>> findAllClosest(HttpRequest<?> request) {
            CharSequence uri = routeUri(request);
            JaxRsRequestMethod requestMethod = JaxRsRequestMethod.forRequest(request);
            List<UriRouteMatch<T, R>> matches = filteredMatrixAwareMatches(
                uri,
                findRequestMethod(request, uri, requestMethod),
                strippedUri -> router.<T, R>find(requestMethod.method(), strippedUri, request),
                request,
                requestMethod,
                true,
                true
            );
            return wrap(
                withSubResourceLocatorFallback(
                    request,
                    requestMethod,
                    uri,
                    matches
                ).stream(),
                request
            ).toList();
        }

        @Override
        public <T, R> Stream<UriRouteMatch<T, R>> find(HttpRequest<?> request, CharSequence uri) {
            JaxRsRequestMethod requestMethod = JaxRsRequestMethod.forRequest(request);
            return wrap(
                withSubResourceLocatorFallback(
                    request,
                    requestMethod,
                    uri,
                    filteredMatrixAwareMatches(
                        uri,
                        findRequestMethod(request, uri, requestMethod),
                        strippedUri -> router.<T, R>find(requestMethod.method(), strippedUri, request),
                        request,
                        requestMethod,
                        true,
                        true
                    )
                ).stream(),
                request
            );
        }

        @Override
        public <T, R> Stream<UriRouteMatch<T, R>> find(HttpRequest<?> request) {
            CharSequence uri = routeUri(request);
            JaxRsRequestMethod requestMethod = JaxRsRequestMethod.forRequest(request);
            return wrap(
                withSubResourceLocatorFallback(
                    request,
                    requestMethod,
                    uri,
                    filteredMatrixAwareMatches(
                        uri,
                        findRequestMethod(request, uri, requestMethod),
                        strippedUri -> router.<T, R>find(requestMethod.method(), strippedUri, request),
                        request,
                        requestMethod,
                        true,
                        true
                    )
                ).stream(),
                request
            );
        }

        @Override
        public <T, R> Optional<UriRouteMatch<T, R>> route(HttpMethod httpMethod, CharSequence uri) {
            HttpRequest<?> request = HttpRequest.create(httpMethod, uri.toString());
            JaxRsRequestMethod requestMethod = JaxRsRequestMethod.forRequest(request);
            return wrap(
                withSubResourceLocatorFallback(
                    request,
                    requestMethod,
                    uri,
                    filteredMatrixAwareMatches(
                        uri,
                        router.<T, R>find(httpMethod, uri, request),
                        strippedUri -> router.<T, R>find(httpMethod, strippedUri, request),
                        request,
                        requestMethod,
                        true,
                        true
                    )
                ).stream(),
                request
            )
                .findFirst();
        }

        private <T, R> List<UriRouteMatch<T, R>> withSubResourceLocatorFallback(HttpRequest<?> request,
                                                                                JaxRsRequestMethod requestMethod,
                                                                                CharSequence uri,
                                                                                List<UriRouteMatch<T, R>> matches) {
            if (!matches.isEmpty()) {
                return matches;
            }
            // A subresource locator route may intentionally match the root prefix
            // instead of the final resource method URI. Re-run selection over the
            // application routes before falling back to Micronaut's normal miss.
            List<UriRouteMatch<T, R>> fallbackMatches = subResourceLocatorFallbackMatches(request, requestMethod, uri);
            if (!fallbackMatches.isEmpty()) {
                return fallbackMatches;
            }
            String strippedUri = stripMatrixParameters(uri);
            if (strippedUri.contentEquals(uri)) {
                return matches;
            }
            return subResourceLocatorFallbackMatches(request, requestMethod, strippedUri);
        }

        private <T, R> List<UriRouteMatch<T, R>> subResourceLocatorFallbackMatches(HttpRequest<?> request,
                                                                                   JaxRsRequestMethod requestMethod,
                                                                                   CharSequence uri) {
            return selectJaxRsMatches(
                this.<T, R>findAnyRouteLookupMatches(uri, request, requestMethod).stream()
                    .filter(match -> requestMethod.isHead() || match.getRouteInfo().getHttpMethod() != HttpMethod.HEAD)
                    .filter(match -> isJaxRsSubResourceLocatorRoute(match) && supportsTargetHttpMethod(match, requestMethod))
                    .toList(),
                false,
                uri,
                request
            );
        }

        private <T, R> Stream<UriRouteMatch<T, R>> findAny(HttpRequest<?> request, CharSequence uri) {
            if (hasRequestUriOverride(request)) {
                return router.<T, R>findAny(uri, request);
            }
            return router.<T, R>findAny(request).stream();
        }

        private <T, R> Stream<UriRouteMatch<T, R>> findRequestMethod(HttpRequest<?> request, CharSequence uri, JaxRsRequestMethod requestMethod) {
            if (hasRequestRouteOverride(request)) {
                return router.<T, R>find(requestMethod.method(), uri, request);
            }
            return router.<T, R>find(request);
        }

        private static CharSequence routeUri(HttpRequest<?> request) {
            return request.getAttribute(JaxRsContainerRequestContext.REQUEST_URI_ATTRIBUTE, URI.class)
                .map(URI::getRawPath)
                .orElseGet(request::getPath);
        }

        private static boolean hasRequestUriOverride(HttpRequest<?> request) {
            return request.getAttribute(JaxRsContainerRequestContext.REQUEST_URI_ATTRIBUTE, URI.class).isPresent();
        }

        private static boolean hasRequestRouteOverride(HttpRequest<?> request) {
            return hasRequestUriOverride(request)
                || request.getAttribute(JaxRsContainerRequestContext.REQUEST_METHOD_ATTRIBUTE, String.class).isPresent();
        }

        private <T, R> List<UriRouteMatch<T, R>> filteredMatrixAwareMatches(CharSequence uri,
                                                                            Stream<UriRouteMatch<T, R>> matches,
                                                                            Function<CharSequence, Stream<UriRouteMatch<T, R>>> fallbackFinder,
                                                                            @Nullable HttpRequest<?> request,
                                                                            @Nullable JaxRsRequestMethod requestMethod,
                                                                            boolean requireMediaMatch,
                                                                            boolean limitDirectResourceMethod) {
            List<UriRouteMatch<T, R>> selectedMatches = selectJaxRsMatches(
                expandJaxRsMatches(uri, matches.toList(), request, requestMethod),
                true,
                uri,
                request,
                requireMediaMatch,
                limitDirectResourceMethod
            );
            if (!selectedMatches.isEmpty()) {
                return selectedMatches;
            }
            // Micronaut route templates do not include matrix parameters; Jakarta
            // REST still exposes them through MatrixParam, so strip them only after
            // giving exact route matches a chance to win.
            String strippedUri = stripMatrixParameters(uri);
            if (strippedUri.contentEquals(uri)) {
                return selectedMatches;
            }
            return selectJaxRsMatches(
                expandJaxRsMatches(strippedUri, fallbackFinder.apply(strippedUri).toList(), request, requestMethod),
                false,
                strippedUri,
                request,
                requireMediaMatch,
                limitDirectResourceMethod
            );
        }

        private <T, R> List<UriRouteMatch<T, R>> expandJaxRsMatches(CharSequence uri,
                                                                    List<UriRouteMatch<T, R>> matches,
                                                                    @Nullable HttpRequest<?> request,
                                                                    @Nullable JaxRsRequestMethod requestMethod) {
            if (request == null || matches.stream().noneMatch(this::isJaxRsRoute)) {
                return matches;
            }
            List<UriRouteMatch<T, R>> expandedMatches = new ArrayList<>(matches);
            Set<UriRouteInfo<?, ?>> seenRoutes = new HashSet<>();
            for (UriRouteMatch<T, R> match : matches) {
                seenRoutes.add(match.getRouteInfo());
            }
            JaxRsRequestMethod resolvedRequestMethod = requestMethod == null ? JaxRsRequestMethod.forRequest(request) : requestMethod;
            this.<T, R>findAnyRouteLookupMatches(uri, request, resolvedRequestMethod).stream()
                .filter(match -> !seenRoutes.contains(match.getRouteInfo()))
                .filter(this::isJaxRsRoute)
                .filter(match -> supportsRouteRequest(match, request, resolvedRequestMethod))
                .forEach(match -> {
                    seenRoutes.add(match.getRouteInfo());
                    expandedMatches.add(match);
                });
            return expandedMatches;
        }

        private <T, R> List<UriRouteMatch<T, R>> findAnyRouteLookupMatches(CharSequence uri,
                                                                           HttpRequest<?> request,
                                                                           JaxRsRequestMethod requestMethod) {
            List<UriRouteMatch<T, R>> matches = new ArrayList<>();
            Set<UriRouteInfo<?, ?>> seenRoutes = new HashSet<>();
            for (String lookupUri : routeLookupUris(uri)) {
                for (UriRouteInfo<?, ?> route : applicationJaxRsRoutes) {
                    if (seenRoutes.contains(route) || isSyntheticHeadSubResourceLocatorRoute(route) || !matchesRouteRequestMethod(route, requestMethod)) {
                        continue;
                    }
                    // tryMatch avoids invoking the router again and lets us test
                    // only the pre-filtered JAX-RS application routes.
                    UriRouteMatch<T, R> match = tryMatch(route, lookupUri);
                    if (match != null && seenRoutes.add(route)) {
                        matches.add(match);
                    }
                }
            }
            return matches;
        }

        @SuppressWarnings("unchecked")
        private static <T, R> @Nullable UriRouteMatch<T, R> tryMatch(UriRouteInfo<?, ?> route, String lookupUri) {
            return (UriRouteMatch<T, R>) route.tryMatch(lookupUri);
        }

        private List<String> routeLookupUris(CharSequence uri) {
            List<String> lookupUris = new ArrayList<>(3);
            String value = uri.toString();
            addLookupUri(lookupUris, value);
            addLookupUri(lookupUris, stripApplicationPath(value, applicationProvider.getPath()));
            addLookupUri(lookupUris, stripApplicationPath(value, applicationProvider.getApplicationPath()));
            return lookupUris;
        }

        private static void addLookupUri(List<String> lookupUris, String uri) {
            if (!lookupUris.contains(uri)) {
                lookupUris.add(uri);
            }
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

        private static String rootTemplate(AnnotationMetadata annotationMetadata, UriRouteInfo<?, ?> route) {
            OptionalInt segmentCount = annotationMetadata.intValue(JaxRsResourceTemplate.class, JaxRsResourceTemplateMetadata.MEMBER_ROOT_PATH_SEGMENT_COUNT);
            if (segmentCount.isEmpty()) {
                return "/";
            }
            String resourceTemplate = annotationMetadata.stringValue(JaxRsResourceTemplate.class)
                .orElseGet(() -> route.getUriMatchTemplate().toString());
            return firstPathSegments(resourceTemplate, segmentCount.getAsInt());
        }

        private static String firstPathSegments(String path, int count) {
            if (count <= 0) {
                return "/";
            }
            String normalizedPath = normalizeRoutePath(path);
            int segmentStart = 0;
            int braceDepth = 0;
            int segments = 0;
            for (int i = 0; i <= normalizedPath.length(); i++) {
                boolean end = i == normalizedPath.length();
                char c = end ? '\0' : normalizedPath.charAt(i);
                if (!end) {
                    if (c == '{') {
                        braceDepth++;
                    } else if (c == '}' && braceDepth > 0) {
                        braceDepth--;
                    }
                }
                if (end || (c == '/' && braceDepth == 0)) {
                    if (i > segmentStart && ++segments == count) {
                        return normalizedPath.substring(0, i);
                    }
                    segmentStart = i + 1;
                }
            }
            return normalizedPath;
        }

        private <T, R> Stream<UriRouteMatch<T, R>> unfilteredMatrixAwareMatches(CharSequence uri,
                                                                                Stream<UriRouteMatch<T, R>> matches,
                                                                                Function<CharSequence, Stream<UriRouteMatch<T, R>>> fallbackFinder) {
            List<UriRouteMatch<T, R>> matchList = matches.toList();
            if (!matchList.isEmpty()) {
                return selectJaxRsMatches(matchList, true, uri, null).stream();
            }
            String strippedUri = stripMatrixParameters(uri);
            if (strippedUri.contentEquals(uri)) {
                return matchList.stream();
            }
            return selectJaxRsMatches(fallbackFinder.apply(strippedUri).toList(), false, strippedUri, null).stream();
        }

        private <T, R> List<UriRouteMatch<T, R>> selectJaxRsMatches(List<UriRouteMatch<T, R>> matches,
                                                                    boolean includeNonJaxRs,
                                                                    @Nullable CharSequence uri,
                                                                    @Nullable HttpRequest<?> request) {
            return selectJaxRsMatches(matches, includeNonJaxRs, uri, request, true, true);
        }

        private <T, R> List<UriRouteMatch<T, R>> selectJaxRsMatches(List<UriRouteMatch<T, R>> matches,
                                                                    boolean includeNonJaxRs,
                                                                    @Nullable CharSequence uri,
                                                                    @Nullable HttpRequest<?> request,
                                                                    boolean requireMediaMatch,
                                                                    boolean limitDirectResourceMethod) {
            if (matches.isEmpty()) {
                return matches;
            }
            // Jakarta REST selection is root-resource first, then resource method,
            // then media type. The visitor stores the scoring inputs as annotation
            // metadata so this listener does not need to split templates per request.
            @Nullable List<String> rootLookupUris = uri == null ? null : routeLookupUris(stripMatrixParameters(uri));
            @Nullable JaxRsRouteScore bestRootScore = rootLookupUris == null ? null : bestMatchingRootScore(rootLookupUris).orElse(null);
            List<ScoredRouteMatch<T, R>> jaxRsMatches = new ArrayList<>();
            for (UriRouteMatch<T, R> match : matches) {
                if (isJaxRsRoute(match) && isApplicationJaxRsRoute(match) &&
                    (!requireMediaMatch || request == null || matchesRouteMedia(match, request))) {
                    RouteSelectionScore selectionScore = selectionScore(match);
                    if (bestRootScore == null || matchesSelectedRoot(match.getRouteInfo(), rootLookupUris, selectionScore.rootScore(), bestRootScore)) {
                        jaxRsMatches.add(new ScoredRouteMatch<>(match, selectionScore, mediaSelectionScore(match, request)));
                    }
                }
            }
            if (jaxRsMatches.isEmpty()) {
                return includeNonJaxRs
                    ? matches.stream().filter(match -> !isJaxRsRoute(match)).toList()
                    : List.of();
            }
            RouteSelectionScore bestScore = jaxRsMatches.get(0).score();
            for (int i = 1; i < jaxRsMatches.size(); i++) {
                RouteSelectionScore score = jaxRsMatches.get(i).score();
                if (RouteSelectionScore.COMPARATOR.compare(score, bestScore) > 0) {
                    bestScore = score;
                }
            }
            MediaSelectionScore bestMediaScore = null;
            for (ScoredRouteMatch<T, R> jaxRsMatch : jaxRsMatches) {
                if (jaxRsMatch.score().equals(bestScore)
                    && (bestMediaScore == null || MediaSelectionScore.COMPARATOR.compare(jaxRsMatch.mediaScore(), bestMediaScore) > 0)) {
                    bestMediaScore = jaxRsMatch.mediaScore();
                }
            }
            List<ScoredRouteMatch<T, R>> selectedJaxRsMatches = new ArrayList<>();
            for (ScoredRouteMatch<T, R> jaxRsMatch : jaxRsMatches) {
                if (jaxRsMatch.score().equals(bestScore) && jaxRsMatch.mediaScore().equals(bestMediaScore)) {
                    selectedJaxRsMatches.add(jaxRsMatch);
                }
            }
            boolean hasDirectResourceMethod = selectedJaxRsMatches.stream()
                .anyMatch(jaxRsMatch -> !isJaxRsSubResourceLocatorRoute(jaxRsMatch.match()));
            Set<UriRouteInfo<?, ?>> selectedJaxRsRoutes = new HashSet<>();
            for (ScoredRouteMatch<T, R> selectedJaxRsMatch : selectedJaxRsMatches) {
                if (hasDirectResourceMethod && isJaxRsSubResourceLocatorRoute(selectedJaxRsMatch.match())) {
                    continue;
                }
                selectedJaxRsRoutes.add(selectedJaxRsMatch.match().getRouteInfo());
                if (hasDirectResourceMethod && limitDirectResourceMethod) {
                    break;
                }
            }
            List<UriRouteMatch<T, R>> selectedMatches = new ArrayList<>(matches.size());
            for (UriRouteMatch<T, R> match : matches) {
                boolean selectedJaxRsRoute = selectedJaxRsRoutes.contains(match.getRouteInfo());
                if (selectedJaxRsRoute || includeNonJaxRs && !isJaxRsRoute(match)) {
                    selectedMatches.add(match);
                }
            }
            return selectedMatches;
        }

        private boolean matchesSelectedRoot(UriRouteInfo<?, ?> route,
                                            @Nullable List<String> rootLookupUris,
                                            JaxRsRouteScore routeRootScore,
                                            JaxRsRouteScore bestRootScore) {
            return JaxRsRouteScore.COMPARATOR.compare(routeRootScore, bestRootScore) == 0
                && (rootLookupUris == null || matchesRootTemplate(route, rootLookupUris));
        }

        private Optional<JaxRsRouteScore> bestMatchingRootScore(List<String> rootLookupUris) {
            JaxRsRouteScore bestScore = null;
            for (RootResourceTemplate rootResourceTemplate : rootResourceTemplates) {
                if (matchesRootTemplate(rootResourceTemplate, rootLookupUris)
                    && (bestScore == null || JaxRsRouteScore.COMPARATOR.compare(rootResourceTemplate.score(), bestScore) > 0)) {
                    bestScore = rootResourceTemplate.score();
                }
            }
            return Optional.ofNullable(bestScore);
        }

        private boolean matchesRootTemplate(UriRouteInfo<?, ?> route, List<String> rootLookupUris) {
            RootResourceTemplate rootResourceTemplate = rootTemplateByRoute.get(route);
            return rootResourceTemplate == null || matchesRootTemplate(rootResourceTemplate, rootLookupUris);
        }

        private static boolean matchesRootTemplate(RootResourceTemplate rootResourceTemplate, List<String> rootLookupUris) {
            for (String lookupUri : rootLookupUris) {
                if (rootResourceTemplate.matches(lookupUri)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isApplicationJaxRsRoute(UriRouteMatch<?, ?> match) {
            return rootTemplateByRoute.containsKey(match.getRouteInfo());
        }

        private static String stripMatrixParameters(CharSequence uri) {
            String value = uri.toString();
            int semicolon = value.indexOf(';');
            if (semicolon < 0) {
                return value;
            }
            StringBuilder stripped = new StringBuilder(value.length());
            boolean inMatrixParameter = false;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (inMatrixParameter) {
                    if (c == '/') {
                        inMatrixParameter = false;
                        stripped.append(c);
                    } else if (c == '?' || c == '#') {
                        stripped.append(value, i, value.length());
                        break;
                    }
                } else if (c == ';') {
                    inMatrixParameter = true;
                } else {
                    stripped.append(c);
                    if (c == '?' || c == '#') {
                        stripped.append(value, i + 1, value.length());
                        break;
                    }
                }
            }
            return stripped.toString();
        }

        private static String normalizeRoutePath(String path) {
            if (path == null || path.isEmpty() || "/".equals(path)) {
                return "/";
            }
            String normalizedPath = path.charAt(0) == '/' ? path : '/' + path;
            if (normalizedPath.length() > 1 && normalizedPath.endsWith("/")) {
                return normalizedPath.substring(0, normalizedPath.length() - 1);
            }
            return normalizedPath;
        }

        private boolean isJaxRsRoute(UriRouteMatch<?, ?> match) {
            return isJaxRsRoute(match.getRouteInfo());
        }

        private static boolean hasJaxRsRouteAnnotation(UriRouteInfo<?, ?> route) {
            return route.getAnnotationMetadata().hasAnnotation(JaxRsResourceTemplate.class);
        }

        private boolean isJaxRsRoute(UriRouteInfo<?, ?> route) {
            return routeMetadataByRoute.containsKey(route);
        }

        private boolean isJaxRsSubResourceLocatorRoute(UriRouteMatch<?, ?> match) {
            return isJaxRsSubResourceLocatorRoute(match.getRouteInfo());
        }

        private boolean isJaxRsSubResourceLocatorRoute(UriRouteInfo<?, ?> route) {
            JaxRsRouteMetadata metadata = routeMetadataByRoute.get(route);
            return metadata != null && metadata.subResourceLocator();
        }

        private boolean supportsTargetHttpMethod(UriRouteMatch<?, ?> match, JaxRsRequestMethod requestMethod) {
            return supportsTargetHttpMethod(match.getRouteInfo(), requestMethod);
        }

        private boolean supportsTargetHttpMethod(UriRouteInfo<?, ?> route, JaxRsRequestMethod requestMethod) {
            JaxRsRouteMetadata metadata = routeMetadataByRoute.get(route);
            if (metadata == null) {
                return false;
            }
            if (metadata.dynamicSubResourceLocator()) {
                return true;
            }
            Set<String> targetHttpMethods = metadata.targetHttpMethods();
            return targetHttpMethods.contains(requestMethod.name())
                || requestMethod.isHead() && targetHttpMethods.contains(HttpMethod.GET.name());
        }

        private boolean isSyntheticHeadSubResourceLocatorRoute(UriRouteInfo<?, ?> route) {
            JaxRsRouteMetadata metadata = routeMetadataByRoute.get(route);
            return metadata != null && metadata.syntheticHeadSubResourceLocator();
        }

        private boolean supportsRouteRequest(UriRouteMatch<?, ?> match, HttpRequest<?> request, JaxRsRequestMethod requestMethod) {
            return matchesRouteRequestMethod(match.getRouteInfo(), requestMethod) && matchesRouteMedia(match, request);
        }

        private boolean matchesRouteRequestMethod(UriRouteInfo<?, ?> route, JaxRsRequestMethod requestMethod) {
            JaxRsRouteMetadata metadata = routeMetadataByRoute.get(route);
            if (metadata != null && metadata.subResourceLocator()) {
                if (metadata.dynamicSubResourceLocator()) {
                    return true;
                }
                if (!metadata.targetHttpMethods().isEmpty()) {
                    return supportsTargetHttpMethod(route, requestMethod);
                }
            }
            String httpMethodName = metadata == null ? route.getHttpMethodName() : metadata.httpMethodName();
            return requestMethod.matches(httpMethodName);
        }

        private boolean matchesRouteMedia(UriRouteMatch<?, ?> match, HttpRequest<?> request) {
            JaxRsRouteMetadata metadata = routeMetadataByRoute.get(match.getRouteInfo());
            if (metadata == null) {
                return true;
            }
            Optional<MediaType> contentType = request.getContentType();
            if (contentType.isPresent()) {
                MediaType[] consumes = metadata.consumes();
                if (consumes.length > 0 && !anyConsumedMediaTypeMatches(contentType.get(), consumes)) {
                    return false;
                }
            }
            Collection<MediaType> acceptedMediaTypes = request.accept();
            if (!acceptedMediaTypes.isEmpty()) {
                MediaType[] produces = metadata.produces();
                return produces.length == 0 || anyProducedMediaTypeMatches(acceptedMediaTypes, produces);
            }
            return true;
        }

        private static boolean anyConsumedMediaTypeMatches(MediaType contentType, MediaType[] consumedMediaTypes) {
            for (MediaType consumedMediaType : consumedMediaTypes) {
                if (consumedMediaType.matches(contentType)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean anyProducedMediaTypeMatches(Collection<MediaType> acceptedMediaTypes, MediaType[] producedMediaTypes) {
            for (MediaType acceptedMediaType : acceptedMediaTypes) {
                for (MediaType producedMediaType : producedMediaTypes) {
                    if (acceptedMediaType.matches(producedMediaType) || producedMediaType.matches(acceptedMediaType)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private RouteSelectionScore selectionScore(UriRouteMatch<?, ?> match) {
            JaxRsRouteMetadata metadata = routeMetadataByRoute.get(match.getRouteInfo());
            return metadata == null ? selectionScore(match.getRouteInfo(), match.getRouteInfo().getAnnotationMetadata()) : metadata.selectionScore();
        }

        private static RouteSelectionScore selectionScore(UriRouteInfo<?, ?> route, AnnotationMetadata annotationMetadata) {
            JaxRsRouteScore routeScore = score(
                annotationMetadata,
                JaxRsResourceTemplateMetadata.MEMBER_LITERAL_CHARACTERS,
                JaxRsResourceTemplateMetadata.MEMBER_CAPTURING_GROUPS,
                JaxRsResourceTemplateMetadata.MEMBER_NON_DEFAULT_CAPTURING_GROUPS
            ).orElseGet(() -> JaxRsRouteScore.of(route.getUriMatchTemplate().toString()));
            JaxRsRouteScore rootScore = score(
                annotationMetadata,
                JaxRsResourceTemplateMetadata.MEMBER_ROOT_LITERAL_CHARACTERS,
                JaxRsResourceTemplateMetadata.MEMBER_ROOT_CAPTURING_GROUPS,
                JaxRsResourceTemplateMetadata.MEMBER_ROOT_NON_DEFAULT_CAPTURING_GROUPS
            ).orElse(routeScore);
            return new RouteSelectionScore(rootScore, routeScore);
        }

        private MediaSelectionScore mediaSelectionScore(UriRouteMatch<?, ?> match, @Nullable HttpRequest<?> request) {
            if (request == null) {
                return MediaSelectionScore.ZERO;
            }
            JaxRsRouteMetadata metadata = routeMetadataByRoute.get(match.getRouteInfo());
            if (metadata == null) {
                return MediaSelectionScore.ZERO;
            }
            int consumesScore = request.getContentType()
                .map(contentType -> bestMediaTypeSpecificity(contentType, metadata.consumes()))
                .orElse(0);
            Collection<MediaType> acceptedMediaTypes = request.accept();
            ProducedMediaSelectionScore producesScore = acceptedMediaTypes.isEmpty()
                ? ProducedMediaSelectionScore.ZERO
                : bestProducedMediaSelectionScore(acceptedMediaTypes, metadata.produces(), hasWildcardAccept(request));
            return new MediaSelectionScore(consumesScore, producesScore);
        }

        private static int bestMediaTypeSpecificity(MediaType actualMediaType, MediaType[] candidateMediaTypes) {
            int best = 0;
            for (MediaType candidateMediaType : candidateMediaTypes) {
                if (candidateMediaType.matches(actualMediaType) || actualMediaType.matches(candidateMediaType)) {
                    best = Math.max(best, mediaTypeSpecificity(candidateMediaType));
                }
            }
            return best;
        }

        private static ProducedMediaSelectionScore bestProducedMediaSelectionScore(Collection<MediaType> acceptedMediaTypes,
                                                                                  MediaType[] producedMediaTypes,
                                                                                  boolean preferProducedSpecificity) {
            ProducedMediaSelectionScore best = ProducedMediaSelectionScore.ZERO;
            for (MediaType acceptedMediaType : acceptedMediaTypes) {
                for (MediaType producedMediaType : producedMediaTypes) {
                    if (acceptedMediaType.matches(producedMediaType) || producedMediaType.matches(acceptedMediaType)) {
                        int producedSpecificity = mediaTypeSpecificity(producedMediaType);
                        Optional<String> serverQuality = producedMediaType.getParameters().get("qs");
                        ProducedMediaSelectionScore score = new ProducedMediaSelectionScore(
                            preferProducedSpecificity
                                ? producedSpecificity
                                : Math.max(mediaTypeSpecificity(acceptedMediaType), producedSpecificity),
                            quality(acceptedMediaType, "q"),
                            serverQuality.isEmpty(),
                            producedSpecificity,
                            serverQuality.map(Double::parseDouble).orElse(1.0)
                        );
                        if (ProducedMediaSelectionScore.COMPARATOR.compare(score, best) > 0) {
                            best = score;
                        }
                    }
                }
            }
            return best;
        }

        private static boolean hasWildcardAccept(HttpRequest<?> request) {
            for (String accept : request.getHeaders().getAll(HttpHeaders.ACCEPT)) {
                if (accept.indexOf('*') > -1) {
                    return true;
                }
            }
            return false;
        }

        private static int mediaTypeSpecificity(MediaType mediaType) {
            if (MediaType.ALL_TYPE.equals(mediaType)) {
                return 0;
            }
            if ("*".equals(mediaType.getSubtype())) {
                return 1;
            }
            return 2;
        }

        private static double quality(MediaType mediaType, String parameterName) {
            String value = mediaType.getParameters().get(parameterName).orElse(null);
            if (value == null) {
                return 1.0;
            }
            return Double.parseDouble(value);
        }

        private static Optional<JaxRsRouteScore> score(AnnotationMetadata annotationMetadata,
                                                       String literalCharactersMember,
                                                       String capturingGroupsMember,
                                                       String nonDefaultCapturingGroupsMember) {
            OptionalInt literalCharacters = annotationMetadata.intValue(JaxRsResourceTemplate.class, literalCharactersMember);
            OptionalInt capturingGroups = annotationMetadata.intValue(JaxRsResourceTemplate.class, capturingGroupsMember);
            OptionalInt nonDefaultCapturingGroups = annotationMetadata.intValue(JaxRsResourceTemplate.class, nonDefaultCapturingGroupsMember);
            if (literalCharacters.isPresent() && capturingGroups.isPresent() && nonDefaultCapturingGroups.isPresent() && literalCharacters.getAsInt() > -1) {
                return Optional.of(new JaxRsRouteScore(
                    literalCharacters.getAsInt(),
                    capturingGroups.getAsInt(),
                    nonDefaultCapturingGroups.getAsInt()
                ));
            }
            return Optional.empty();
        }

        private <T, R> Stream<UriRouteMatch<T, R>> wrap(Stream<UriRouteMatch<T, R>> matches, HttpRequest<?> request) {
            return matches.map(match -> wrap(match, request));
        }

        private <T, R> UriRouteMatch<T, R> wrap(UriRouteMatch<T, R> match, HttpRequest<?> request) {
            suppressMatrixRouteVariables(match);
            if (match.getArguments().length != 0) {
                return match;
            }
            Optional<BeanDefinition<T>> beanDefinition = beanContext.findBeanDefinition(match.getDeclaringType());
            if (beanDefinition.isPresent() && beanDefinition.get().hasAnnotation(JaxRsConstructorInjection.class)) {
                return new JaxRsConstructorInjectionRouteMatch<>(match, request, beanContext, requestBinderRegistry, beanDefinition.get());
            }
            return match;
        }

        private static void suppressMatrixRouteVariables(UriRouteMatch<?, ?> match) {
            String[] matrixRouteVariableNames = match.getAnnotationMetadata()
                .stringValues(JaxRsResourceTemplate.class, JaxRsResourceTemplateMetadata.MEMBER_MATRIX_ROUTE_VARIABLE_NAMES);
            if (matrixRouteVariableNames.length == 0) {
                return;
            }
            Map<String, Object> variableValues = match.getVariableValues();
            if (variableValues.isEmpty()) {
                return;
            }
            for (String matrixRouteVariableName : matrixRouteVariableNames) {
                variableValues.remove(matrixRouteVariableName);
            }
        }

        private record RouteSelectionScore(JaxRsRouteScore rootScore,
                                           JaxRsRouteScore routeScore) {
            private static final Comparator<RouteSelectionScore> COMPARATOR = Comparator
                .comparing(RouteSelectionScore::rootScore, JaxRsRouteScore.COMPARATOR)
                .thenComparing(RouteSelectionScore::routeScore, JaxRsRouteScore.COMPARATOR);
        }

        private record MediaSelectionScore(int consumesScore,
                                           ProducedMediaSelectionScore producesScore) {
            private static final MediaSelectionScore ZERO = new MediaSelectionScore(0, ProducedMediaSelectionScore.ZERO);
            private static final Comparator<MediaSelectionScore> COMPARATOR = Comparator
                .comparingInt(MediaSelectionScore::consumesScore)
                .thenComparing(MediaSelectionScore::producesScore, ProducedMediaSelectionScore.COMPARATOR);
        }

        private record ProducedMediaSelectionScore(int specificity,
                                                   double clientQuality,
                                                   boolean implicitServerQuality,
                                                   int producedSpecificity,
                                                   double serverQuality) {
            private static final ProducedMediaSelectionScore ZERO = new ProducedMediaSelectionScore(0, 0, false, 0, 0);
            private static final Comparator<ProducedMediaSelectionScore> COMPARATOR = Comparator
                .comparingInt(ProducedMediaSelectionScore::specificity)
                .thenComparingDouble(ProducedMediaSelectionScore::clientQuality)
                .thenComparing(ProducedMediaSelectionScore::implicitServerQuality)
                .thenComparingInt(ProducedMediaSelectionScore::producedSpecificity)
                .thenComparingDouble(ProducedMediaSelectionScore::serverQuality);
        }

        private record ScoredRouteMatch<T, R>(UriRouteMatch<T, R> match,
                                              RouteSelectionScore score,
                                              MediaSelectionScore mediaScore) {
        }

        private record RouteRootTemplates(Map<UriRouteInfo<?, ?>, RootResourceTemplate> byRoute,
                                          List<RootResourceTemplate> roots) {
        }

        private record JaxRsRouteMetadata(boolean subResourceLocator,
                                          boolean dynamicSubResourceLocator,
                                          String httpMethodName,
                                          Set<String> targetHttpMethods,
                                          boolean syntheticHeadSubResourceLocator,
                                          MediaType[] consumes,
                                          MediaType[] produces,
                                          RouteSelectionScore selectionScore) {
        }

        private record RootResourceTemplate(String rootClassName,
                                            String template,
                                            UriMatchTemplate prefixTemplate,
                                            JaxRsRouteScore score) {

            private RootResourceTemplate(String rootClassName, String template, JaxRsRouteScore score) {
                this(rootClassName, template, prefixTemplate(template), score);
            }

            private boolean matches(String uri) {
                return "/".equals(template) || prefixTemplate.match(normalizeRoutePath(uri)).isPresent();
            }

            private String key() {
                return rootClassName + '\n' + template;
            }

            private static UriMatchTemplate prefixTemplate(String template) {
                if ("/".equals(template)) {
                    return UriMatchTemplate.of(template);
                }
                return UriMatchTemplate.of(template + "{/jaxrsRootRemaining:.*}");
            }
        }
    }

    private static final class JaxRsConstructorInjectionRouteMatch<T, R> implements UriRouteMatch<T, R> {
        private final UriRouteMatch<T, R> delegate;
        private final HttpRequest<?> request;
        private final BeanContext beanContext;
        private final RequestBinderRegistry requestBinderRegistry;
        private final BeanDefinition<T> beanDefinition;
        private @Nullable T target;

        private JaxRsConstructorInjectionRouteMatch(UriRouteMatch<T, R> delegate,
                                                    HttpRequest<?> request,
                                                    BeanContext beanContext,
                                                    RequestBinderRegistry requestBinderRegistry,
                                                    BeanDefinition<T> beanDefinition) {
            this.delegate = delegate;
            this.request = request;
            this.beanContext = beanContext;
            this.requestBinderRegistry = requestBinderRegistry;
            this.beanDefinition = beanDefinition;
        }

        @Override
        public T getTarget() {
            T target = this.target;
            if (target == null) {
                Map<String, Object> argumentValues = bindConstructorArguments();
                target = beanContext.createBean(beanDefinition.getBeanType(), beanDefinition.getDeclaredQualifier(), argumentValues);
                this.target = target;
            }
            return target;
        }

        private Map<String, Object> bindConstructorArguments() {
            Map<String, Object> argumentValues = new LinkedHashMap<>();
            for (Argument<?> argument : beanDefinition.getConstructor().getArguments()) {
                if (argument.getAnnotationMetadata().hasAnnotation(Parameter.class)) {
                    bindConstructorArgument(argumentValues, argument);
                }
            }
            return argumentValues;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private <E> void bindConstructorArgument(Map<String, Object> argumentValues, Argument<E> argument) {
            ArgumentBinder<E, HttpRequest<?>> binder = (ArgumentBinder<E, HttpRequest<?>>) requestBinderRegistry.findArgumentBinder(argument)
                .orElseThrow(() -> UnsatisfiedRouteException.create(argument));
            ArgumentBinder.BindingResult<E> result = binder.bind(ConversionContext.of(
                argument,
                request.getLocale().orElse(null),
                request.getCharacterEncoding()
            ), request);
            if (!result.getConversionErrors().isEmpty()) {
                throw new ConversionErrorException(argument, result.getConversionErrors().get(0));
            }
            Optional<E> value = result.getValue();
            if (value.isPresent()) {
                argumentValues.put(argument.getName(), value.get());
            } else if (argument.isNullable()) {
                argumentValues.put(argument.getName(), null);
            } else {
                throw UnsatisfiedRouteException.create(argument);
            }
        }

        @Override
        public @Nullable R execute() {
            return getExecutableMethod().invoke(getTarget());
        }

        @Override
        public @Nullable R invoke(@Nullable Object... arguments) {
            return getExecutableMethod().invoke(getTarget(), arguments);
        }

        @Override
        public Map<String, Object> getVariableValues() {
            return delegate.getVariableValues();
        }

        @Override
        @SuppressWarnings("removal")
        public void fulfill(Map<String, Object> argumentValues) {
            delegate.fulfill(argumentValues);
        }

        @Override
        public void fulfillBeforeFilters(RequestBinderRegistry requestBinderRegistry, HttpRequest<?> request) {
            delegate.fulfillBeforeFilters(requestBinderRegistry, request);
        }

        @Override
        public void fulfillAfterFilters(RequestBinderRegistry requestBinderRegistry, HttpRequest<?> request) {
            delegate.fulfillAfterFilters(requestBinderRegistry, request);
        }

        @Override
        public boolean isFulfilled() {
            return delegate.isFulfilled();
        }

        @Override
        public Optional<Argument<?>> getRequiredInput(String name) {
            return delegate.getRequiredInput(name);
        }

        @Override
        public List<Argument<?>> getRequiredArguments() {
            return delegate.getRequiredArguments();
        }

        @Override
        public boolean isSatisfied(String name) {
            return delegate.isSatisfied(name);
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public String getUri() {
            return delegate.getUri();
        }

        @Override
        public List<UriMatchVariable> getVariables() {
            return delegate.getVariables();
        }

        @Override
        public Map<String, UriMatchVariable> getVariableMap() {
            return delegate.getVariableMap();
        }

        @Override
        public UriRouteInfo<T, R> getRouteInfo() {
            return delegate.getRouteInfo();
        }

        @Override
        public HttpMethod getHttpMethod() {
            return delegate.getHttpMethod();
        }

        @Override
        public Class<T> getDeclaringType() {
            return delegate.getDeclaringType();
        }

        @Override
        public Argument<?>[] getArguments() {
            return delegate.getArguments();
        }

        @Override
        public ExecutableMethod<T, R> getExecutableMethod() {
            return delegate.getExecutableMethod();
        }

        @Override
        public Method getTargetMethod() {
            return delegate.getTargetMethod();
        }

        @Override
        public ReturnType<R> getReturnType() {
            return delegate.getReturnType();
        }

        @Override
        public String getMethodName() {
            return delegate.getMethodName();
        }

        @Override
        public AnnotationMetadata getAnnotationMetadata() {
            return delegate.getAnnotationMetadata();
        }
    }

}
