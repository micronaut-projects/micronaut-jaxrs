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

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.uri.RouteTemplate;
import io.micronaut.web.router.UriRouteMatch;
import io.micronaut.web.router.spi.RouteMatchSelector.Selection;
import jakarta.ws.rs.Produces;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The resource method selection of JAX-RS (section 3.7.2) for the routes of a
 * {@link JaxRsRouteTemplateEngine}: it records the root resource classes of the templates the
 * engine parses, and commits to the most specific one that matches the path.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
final class JaxRsRouteSelector {

    /**
     * The root resource classes of the routes, most specific first, see {@link #committed}.
     */
    private volatile List<Root> roots = List.of();

    /**
     * The templates of the root resource classes mounted by the router, by the expression of the
     * template declared for the class.
     */
    private final Map<String, RouteTemplate> mountedRoots = new ConcurrentHashMap<>();

    /**
     * The expressions declared for the classes of the mounted templates, by the mounted template
     * itself: a mounted expression can be the declared one of another class.
     */
    private final Map<RouteTemplate, String> mountOrigins = new IdentityHashMap<>();

    /**
     * Select the resource method of JAX-RS (section 3.7.2): the most specific templates, then the
     * content type of the request against the consumed types, then the accepted types, with their
     * quality, against the produced types, with their {@code qs}. The response type is the
     * combined type of the selected method, when the method declares the types it produces.
     * When no method of the most specific templates consumes the content type of the request, the
     * request is answered with {@code 415}, and when none produces an accepted type, with
     * {@code 406}.
     *
     * @param request The request
     * @param matches The matches of the request, of every media type
     * @return The selection
     */
    List<Selection> select(HttpRequest<?> request, List<UriRouteMatch<?, ?>> matches) {
        RouteTemplate best = null;
        for (UriRouteMatch<?, ?> match : matches) {
            RouteTemplate parsed = match.getRouteInfo().getRouteTemplate();
            if (best == null || JaxRsRouteTemplateEngine.ORDER.compare(parsed, best) < 0) {
                best = parsed;
            }
        }
        RouteTemplate root = best == null ? null : JaxRsRouteTemplateEngine.rootOf(best);
        if (root != null && !committed(request, root)) {
            return List.of();
        }
        // every method of the root resource classes with the regular expression of the most specific
        // template, up to the names of the variables (JAX-RS 3.7.2, step 1(f)), whose template matches
        // the path is a candidate, its media types select among them (steps 2(h) and 3); the most
        // specific template is preferred among equally negotiated ones
        List<UriRouteMatch<?, ?>> candidates = new ArrayList<>(matches.size());
        for (UriRouteMatch<?, ?> match : matches) {
            RouteTemplate matchRoot = JaxRsRouteTemplateEngine.rootOf(match.getRouteInfo().getRouteTemplate());
            if (root == null ? matchRoot == null : matchRoot != null && JaxRsRouteTemplateEngine.sameRegex(matchRoot, root)) {
                candidates.add(match);
            }
        }
        if (candidates.stream().anyMatch(c -> !JaxRsRouteTemplateEngine.isSubResource(c.getRouteInfo().getRouteTemplate()))) {
            // a resource method of the class matches: the rest of the path is empty or "/", only the
            // resource methods of the class are candidates, not its sub-resource methods (step 2(a))
            candidates.removeIf(c -> JaxRsRouteTemplateEngine.isSubResource(c.getRouteInfo().getRouteTemplate()));
        } else if (root != null && hasResourceMethods(request, root)) {
            // the same, before the request method selects (step 2(a), then 3(a)): the resource methods
            // of the class are of other request methods, the router answers 405
            return List.of();
        }
        candidates.sort((a, b) -> JaxRsRouteTemplateEngine.ORDER.compare(a.getRouteInfo().getRouteTemplate(), b.getRouteInfo().getRouteTemplate()));
        if (candidates.size() > 1 && candidates.stream().anyMatch(c -> !c.getRouteInfo().isImplicitHead())) {
            // a HEAD request selects among the HEAD methods, and only without one among the GET
            // methods (JAX-RS 3.3.5): the implicit HEAD routes of the GET methods are not candidates
            // beside an explicit HEAD method, whatever the media types they produce
            candidates.removeIf(c -> c.getRouteInfo().isImplicitHead());
        }
        MediaType contentType = request.getContentType().orElse(null);
        List<MediaType> accepted = request.getHeaders().accept();
        if (accepted.isEmpty()) {
            accepted = List.of(MediaType.ALL_TYPE);
        }
        UriRouteMatch<?, ?> selected = null;
        JaxRsMediaNegotiation.Negotiated selectedType = null;
        int selectedConsumes = -1;
        UriRouteMatch<?, ?> unsupported = null;
        UriRouteMatch<?, ?> notAcceptable = null;
        for (UriRouteMatch<?, ?> candidate : candidates) {
            // a request without a content type is */*: the most specific consumed type is the
            // best match (JAX-RS 3.7.2, step 3b)
            int consumes = JaxRsMediaNegotiation.consumes(contentType == null ? MediaType.ALL_TYPE : contentType, candidate.getRouteInfo().getConsumes());
            if (consumes < 0) {
                if (candidate.getRouteInfo().getHttpMethod().permitsRequestBody()) {
                    unsupported = unsupported == null ? candidate : unsupported;
                    continue;
                }
                // the content type of a request without a body does not select the method
                consumes = 0;
            }
            JaxRsMediaNegotiation.Negotiated negotiated = JaxRsMediaNegotiation.negotiate(accepted, candidate.getRouteInfo().getProduces());
            if (negotiated == null) {
                notAcceptable = notAcceptable == null ? candidate : notAcceptable;
                continue;
            }
            if (selectedType == null || consumes > selectedConsumes
                || consumes == selectedConsumes && negotiated.compareTo(selectedType) > 0) {
                selected = candidate;
                selectedType = negotiated;
                selectedConsumes = consumes;
            }
        }
        if (selected == null || selectedType == null) {
            if (notAcceptable != null) {
                return List.of(Selection.notAcceptable(notAcceptable));
            }
            return unsupported == null ? List.of() : List.of(Selection.unsupportedMediaType(unsupported));
        }
        List<MediaType> produces = selected.getRouteInfo().getProduces();
        MediaType type = selectedType.type();
        boolean declared = !(produces.size() == 1 && produces.get(0).equals(MediaType.ALL_TYPE))
            // a method that declares it produces any type has the concrete type it negotiated (JAX-RS 3.8)
            || JaxRsMediaNegotiation.specificity(type) == 2 && selected.getRouteInfo().getAnnotationMetadata().hasAnnotation(Produces.class);
        if (declared && JaxRsMediaNegotiation.specificity(type) < 2 && JaxRsMediaNegotiation.octetStreamFallback(accepted, produces)) {
            // not concrete: JAX-RS answers with application/octet-stream
            type = MediaType.APPLICATION_OCTET_STREAM_TYPE;
        }
        return List.of(Selection.of(selected, declared ? type : null));
    }

    /**
     * Whether the root resource class of a route is the one JAX-RS commits to (section 3.7.2,
     * step 1): no root resource class with a more specific {@code @Path} matches the path. When
     * one does, the request is not found, even if a route of a less specific class matches.
     */
    private boolean committed(HttpRequest<?> request, RouteTemplate root) {
        String path = null;
        for (Root candidate : roots) {
            if (JaxRsRouteTemplateEngine.SPECIFICITY.compare(candidate.template, root) >= 0) {
                return true;
            }
            if (path == null) {
                path = path(request);
            }
            Matcher matcher = candidate.pattern.matcher(path);
            if (matcher.matches() && (candidate.subResources || emptyRest(matcher))) {
                // a class with no sub-resource methods or locators matches its path only (step 1(c))
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the root resource class of a route has resource methods, of any request method,
     * and the rest of the path after its {@code @Path} is empty or {@code /}: only its resource
     * methods are then candidates (JAX-RS 3.7.2, step 2(a)).
     */
    private boolean hasResourceMethods(HttpRequest<?> request, RouteTemplate root) {
        for (Root candidate : roots) {
            // also one of the classes with the same regular expression (step 1(f))
            if (candidate.resourceMethods && JaxRsRouteTemplateEngine.sameRegex(candidate.template, root)) {
                Matcher matcher = candidate.pattern.matcher(path(request));
                return matcher.matches() && emptyRest(matcher);
            }
        }
        return false;
    }

    /**
     * The path the root resource classes match, with its trailing slash: it can be a part of
     * a regular expression of the {@code @Path} of a class, e.g. an empty capture of {@code {x:.*}}.
     */
    private static String path(HttpRequest<?> request) {
        return JaxRsRouteTemplateEngine.withoutMatrixParameters(request.getUri().getRawPath());
    }

    /**
     * @param matcher A match of the pattern of a root resource class
     * @return Whether the rest of the path is empty or {@code /} (JAX-RS 3.7.2, step 2(a))
     */
    private static boolean emptyRest(Matcher matcher) {
        String rest = matcher.group(matcher.groupCount());
        return rest == null || rest.equals("/");
    }

    /**
     * Whether JAX-RS commits to the root resource class of a sub-resource locator route, see
     * {@link #committed}: the router never gives a locator route to {@link #select}, it locates
     * the target of the most specific one first.
     *
     * @param request The request
     * @param root    The template of the root resource class of the locator, as parsed
     * @return Whether the root resource class of the locator is the one JAX-RS commits to
     */
    boolean commitsToLocator(HttpRequest<?> request, RouteTemplate root) {
        // the template the router matches, mounted under the context path
        RouteTemplate mounted = mountedRoots.get(root.expression());
        return committed(request, mounted == null ? root : mounted);
    }

    /**
     * Record the root resource class of a route.
     *
     * @param root         The template of the root resource class
     * @param pattern      The pattern of the paths it matches, compiled when it is not recorded yet
     * @param replaced     The expression of the template it replaces, the unmounted one of a mounted template, or {@code null}
     * @param subResources Whether the route is one of a sub-resource method or locator of the class
     * @param resourceMethod Whether the route is one of a resource method of the class
     */
    synchronized void addRoot(RouteTemplate root, Supplier<Pattern> pattern, @Nullable String replaced, boolean subResources,
                              boolean resourceMethod) {
        List<Root> added = new ArrayList<>(roots.size() + 1);
        boolean present = false;
        for (Root existing : roots) {
            if (replaced != null && existing.template.expression().equals(replaced)) {
                subResources |= existing.subResources;
                resourceMethod |= existing.resourceMethods;
                continue;
            }
            if (existing.template.expression().equals(root.expression())) {
                present = true;
                added.add(new Root(existing.template, existing.pattern, existing.subResources || subResources,
                    existing.resourceMethods || resourceMethod));
                continue;
            }
            added.add(existing);
        }
        if (!present) {
            added.add(new Root(root, pattern.get(), subResources, resourceMethod));
        }
        added.sort(Comparator.comparing(Root::template, JaxRsRouteTemplateEngine.SPECIFICITY));
        roots = List.copyOf(added);
    }

    /**
     * Record the template the router mounted the template of a root resource class as.
     *
     * @param root    The template of the root resource class, the declared one or one mounted before
     * @param mounted   The mounted template
     */
    synchronized void mount(RouteTemplate root, RouteTemplate mounted) {
        String declared = mountOrigins.getOrDefault(root, root.expression());
        mountOrigins.put(mounted, declared);
        mountedRoots.put(declared, mounted);
    }

    /**
     * A root resource class: its template, and the pattern of the paths it matches.
     *
     * @param template     The template
     * @param pattern      The pattern of the paths it matches
     * @param subResources    Whether the class has sub-resource methods or locators
     * @param resourceMethods Whether the class has resource methods, of any request method
     */
    private record Root(RouteTemplate template, Pattern pattern, boolean subResources, boolean resourceMethods) {
    }
}
