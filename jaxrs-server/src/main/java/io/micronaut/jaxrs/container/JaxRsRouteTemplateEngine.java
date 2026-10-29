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
import io.micronaut.http.uri.RouteCaptures;
import io.micronaut.http.uri.RouteTemplate;
import io.micronaut.http.uri.RouteTemplateParser;
import io.micronaut.http.uri.RouteTemplateVariable;
import io.micronaut.http.uri.UriTemplateMatcher;
import io.micronaut.web.router.RouteLocator;
import io.micronaut.web.router.UriRouteInfo;
import io.micronaut.web.router.UriRouteMatch;
import io.micronaut.web.router.spi.RouteMatchSelector;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The route template language of JAX-RS: the {@code @Path} values of a resource and its methods.
 * A {@code {name}} variable matches one path segment, and a {@code {name: regex}} variable matches
 * the regular expression, which may span segments. Literal text is matched in its encoded form,
 * and the captured values are not decoded here. It is also the route selector of the routes it
 * parses: the resource method selection of JAX-RS.
 *
 * <p>One instance per application context: it records the root resource classes of the routes it
 * parses, see {@link JaxRsRouteSelector}.</p>
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
public final class JaxRsRouteTemplateEngine implements RouteTemplateParser, RouteMatchSelector {

    /**
     * Marks the end of the {@code @Path} of the root resource class in a template: JAX-RS selects
     * the root resource class first (section 3.7.2, step 1).
     */
    public static final char ROOT_MARK = '\u001E';

    /**
     * Marks the end of the {@code @Path} of a sub-resource locator in a template: a resource method
     * is selected before a locator that matches as specifically (section 3.7.2, step 2).
     */
    public static final char LOCATOR_MARK = '\u001F';

    /**
     * The order of specificity of JAX-RS (section 3.7.2): more literal characters, then more
     * template variables, then more variables with a regular expression.
     */
    static final Comparator<RouteTemplate> SPECIFICITY = Comparator
        .comparingInt(RouteTemplate::rawLength).reversed()
        .thenComparing(Comparator.comparingInt(RouteTemplate::pathVariableCount).reversed())
        .thenComparing(Comparator.comparingInt(RouteTemplate::patternVariableCount).reversed());

    /**
     * The order of JAX-RS routes: the root resource class, by the specificity of its {@code @Path},
     * then the whole template, then fewer sub-resource locators.
     */
    static final Comparator<RouteTemplate> ORDER = (a, b) -> {
        if (a instanceof Parsed pa && b instanceof Parsed pb) {
            // the templates of this engine know their root resource class and their locators
            int result = SPECIFICITY.compare(pa.root(), pb.root());
            if (result == 0) {
                result = SPECIFICITY.compare(a, b);
            }
            return result == 0 ? Integer.compare(pa.locators, pb.locators) : result;
        }
        return SPECIFICITY.compare(a, b);
    };

    private static final String DEFAULT_REGEX = "[^/]+?";

    /**
     * The order of the routes: a locator route by the template of its prefix, which has the mark
     * of the locator, so that a resource method is selected before a locator with the same
     * template (section 3.7.2, step 2).
     */
    private static final Comparator<UriRouteInfo<?, ?>> ROUTE_ORDER = (a, b) -> ORDER.compare(template(a), template(b));

    private static final Parsed EMPTY = new Parsed(null, "", List.of(), null, 0);

    // the selection of the routes, which knows the root resource classes of the templates this engine parses
    private final JaxRsRouteSelector selector = new JaxRsRouteSelector();

    // the templates of the root resource classes of the prefixes of the locator routes, parsed once
    private final Map<String, Optional<Parsed>> locatorRoots = new ConcurrentHashMap<>();

    private static RouteTemplate template(UriRouteInfo<?, ?> route) {
        RouteTemplate prefix = RouteLocator.locatorPrefix(route);
        return prefix == null ? route.getRouteTemplate() : prefix;
    }

    @Override
    public RouteTemplate parse(String expression) {
        Parsed parsed = parsed(this, expression, "");
        if (parsed.rootPart != null) {
            addRoot(parsed.rootPart, null, hasSubResource(expression));
        }
        return parsed;
    }

    @Override
    public Optional<Comparator<UriRouteInfo<?, ?>>> order() {
        return Optional.of(ROUTE_ORDER);
    }

    /**
     * JAX-RS selects by the path first, then by the media types (section 3.7.2): the selector is
     * given the matches of every media type, and answers {@code 415} or {@code 406} with the
     * routes of the most specific path, not with the ones of a less specific path whose types
     * are compatible.
     */
    @Override
    public boolean selectsMediaTypes() {
        return true;
    }

    /**
     * Select the resource method of JAX-RS (section 3.7.2), see {@link JaxRsRouteSelector#select}.
     */
    @Override
    public List<Selection> select(HttpRequest<?> request, List<UriRouteMatch<?, ?>> matches) {
        return selector.select(request, matches);
    }

    /**
     * Record the root resource class of a route.
     *
     * @param root          The template of the root resource class
     * @param replaced      The template it replaces, the unmounted one of a mounted template, or {@code null}
     * @param subResources  Whether the route is one of a sub-resource method or locator of the class
     */
    private void addRoot(Parsed root, @Nullable Parsed replaced, boolean subResources) {
        // the path of the class, followed by anything (JAX-RS 3.7.3)
        selector.addRoot(root, () -> Pattern.compile(regex(withoutTrailingSlash(root.parts), new ArrayList<>()).append("(/.*)?").toString()),
            replaced == null ? null : replaced.expression, subResources);
    }

    /**
     * @param template A template of a route
     * @return The template of the root resource class of a template of this engine, else {@code null}
     */
    static @Nullable RouteTemplate rootOf(RouteTemplate template) {
        return template instanceof Parsed parsed && parsed.rootPart != null ? parsed.rootPart : null;
    }

    /**
     * Whether JAX-RS commits to the root resource class of a sub-resource locator route, see
     * {@link JaxRsRouteSelector#commitsToLocator}.
     *
     * @param request The request
     * @param prefix  The prefix the locator route was declared with, with the mark of its root resource class
     * @return Whether the root resource class of the locator is the one JAX-RS commits to
     */
    boolean commitsToLocator(HttpRequest<?> request, String prefix) {
        Parsed root = locatorRoots.computeIfAbsent(prefix, p -> Optional.ofNullable(parsed(null, p, "").rootPart)).orElse(null);
        return root == null || selector.commitsToLocator(request, root);
    }

    /**
     * @return Whether a template is the one of a sub-resource method or locator: it has a path
     * after the {@code @Path} of its root resource class
     */
    private static boolean hasSubResource(String expression) {
        int rootMark = expression.indexOf(ROOT_MARK);
        if (rootMark < 0) {
            return false;
        }
        String rest = strip(withoutMarks(expression.substring(rootMark + 1)));
        return !rest.isEmpty() && !rest.equals("/");
    }

    /**
     * A template, with the root resource class and the locators its marks record.
     *
     * @param engine     The engine that parsed it, which records its root resource class
     * @param expression The expression
     * @param prefix     The literal prefix the template starts with, mounted by the router, or empty
     */
    private static Parsed parsed(@Nullable JaxRsRouteTemplateEngine engine, String expression, String prefix) {
        int rootMark = expression.indexOf(ROOT_MARK);
        int locators = 0;
        for (int i = 0; i < expression.length(); i++) {
            if (expression.charAt(i) == LOCATOR_MARK) {
                locators++;
            }
        }
        String clean = withoutMarks(expression);
        @Nullable Parsed root = null;
        if (rootMark >= 0) {
            // the root resource class of a root route, also one with an empty @Path: a template
            // without the mark is one of a located target, matched with the rest of the path
            String rootExpression = strip(expression.substring(0, rootMark));
            root = new Parsed(null, rootExpression, parts(rootExpression), null, 0);
        }
        List<Part> parts = parts(clean);
        if (!prefix.isEmpty()) {
            // the prefix is literal: a brace in it is not a variable
            List<Part> literalPrefix = new ArrayList<>();
            literalPrefix.add(Part.literal(prefix));
            literalPrefix.addAll(parts(clean.substring(prefix.length())));
            parts = List.copyOf(literalPrefix);
        }
        return new Parsed(engine, expression, parts, root, locators);
    }

    /**
     * The name of an earlier occurrence of a variable that repeats in a template, e.g. the first
     * {@code id} of {@code {id}/{id}}: the last occurrence has the name itself.
     *
     * @param name     The name of the variable
     * @param fromLast How many occurrences before the last one, 1 for the one before it
     * @return The name of the occurrence
     */
    public static String repeated(String name, int fromLast) {
        return name + LOCATOR_MARK + fromLast;
    }

    /**
     * The description of the structure of a route template: the one a template of this engine
     * parsed once, else one parsed now.
     *
     * @param template The template of a route
     * @return Its description
     */
    static JaxRsTemplateDescription description(RouteTemplate template) {
        return template instanceof Parsed parsed ? parsed.description() : JaxRsTemplateDescription.of(template.expression());
    }

    /**
     * @param expression A template, possibly with the marks of its root resource class and locators
     * @return The template as it was written, without the marks
     */
    public static String withoutMarks(String expression) {
        if (expression.indexOf(ROOT_MARK) < 0 && expression.indexOf(LOCATOR_MARK) < 0) {
            return expression;
        }
        StringBuilder clean = new StringBuilder(expression.length());
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == ROOT_MARK) {
                // the mark of an empty root @Path is a segment of its own: /{mark}/path is /path
                boolean ownSegment = i + 1 == expression.length() || expression.charAt(i + 1) == '/';
                if (ownSegment && !clean.isEmpty() && clean.charAt(clean.length() - 1) == '/') {
                    clean.setLength(clean.length() - 1);
                }
            } else if (c != LOCATOR_MARK) {
                clean.append(c);
            }
        }
        return clean.isEmpty() ? "/" : clean.toString();
    }

    /**
     * The regular expression of the parts of a template.
     *
     * @param parts  The parts
     * @param groups Receives the group of each variable
     * @return The regular expression
     */
    private static StringBuilder regex(List<Part> parts, List<Integer> groups) {
        StringBuilder regex = new StringBuilder();
        int group = 1;
        for (Part part : parts) {
            if (part.text != null) {
                regex.append(Pattern.quote(encode(part.text)));
            } else {
                String variableRegex = part.regex == null ? DEFAULT_REGEX : part.regex;
                groups.add(group);
                regex.append('(').append(variableRegex).append(')');
                group += 1 + Pattern.compile(variableRegex).matcher("").groupCount();
            }
        }
        return regex;
    }

    /**
     * A path without the matrix parameters of its segments, {@code /cars;color=red/details}: they
     * are not part of the path of a template.
     *
     * @param path The raw path
     * @return The path, the same instance if it has no matrix parameters
     */
    static String withoutMatrixParameters(String path) {
        return path.indexOf(';') < 0 ? path : withoutMatrixParameters(path, null);
    }

    /**
     * A path without the matrix parameters of its segments.
     *
     * @param path    The raw path
     * @param indexes Receives the index in the raw path of each character of the result, and of its end, or {@code null}
     * @return The path
     */
    private static String withoutMatrixParameters(String path, int @Nullable [] indexes) {
        StringBuilder stripped = new StringBuilder(path.length());
        int i = 0;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == ';') {
                // skip to the end of the segment
                int slash = path.indexOf('/', i);
                if (slash < 0) {
                    break;
                }
                i = slash;
                continue;
            }
            if (indexes != null) {
                indexes[stripped.length()] = i;
            }
            stripped.append(c);
            i++;
        }
        if (indexes != null) {
            indexes[stripped.length()] = path.length();
        }
        return stripped.toString();
    }

    /**
     * The literal and variable parts of an expression.
     */
    private static List<Part> parts(String expression) {
        List<Part> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < expression.length()) {
            char c = expression.charAt(i);
            if (c != '{') {
                literal.append(c);
                i++;
                continue;
            }
            int depth = 1;
            int j = i + 1;
            while (j < expression.length() && depth > 0) {
                char d = expression.charAt(j);
                if (d == '{') {
                    depth++;
                } else if (d == '}') {
                    depth--;
                }
                j++;
            }
            if (depth != 0) {
                throw new IllegalArgumentException("A variable is not closed: " + expression);
            }
            if (!literal.isEmpty()) {
                parts.add(Part.literal(literal.toString()));
                literal.setLength(0);
            }
            String variable = expression.substring(i + 1, j - 1);
            int colon = variable.indexOf(':');
            String name = (colon < 0 ? variable : variable.substring(0, colon)).trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("A variable without a name: " + expression);
            }
            String regex = colon < 0 ? null : variable.substring(colon + 1).trim();
            parts.add(new Part(null, name, regex == null || regex.isEmpty() ? null : regex));
            i = j;
        }
        if (!literal.isEmpty()) {
            parts.add(Part.literal(literal.toString()));
        }
        return List.copyOf(parts);
    }

    /**
     * The literal text of a template in the encoded form of a request path.
     */
    private static String encode(String text) {
        StringBuilder encoded = new StringBuilder(text.length());
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                || "-._~!$&'()*+,;=:@/%".indexOf(c) >= 0) {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                    .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return encoded.toString();
    }

    private static String strip(String expression) {
        return expression.endsWith("/") ? expression.substring(0, expression.length() - 1) : expression;
    }

    /**
     * The path is matched without a trailing slash, see
     * {@link UriTemplateMatcher#normalizeForMatching(String)}: neither is the template.
     */
    private static List<Part> withoutTrailingSlash(List<Part> parts) {
        if (parts.isEmpty()) {
            return parts;
        }
        Part last = parts.get(parts.size() - 1);
        if (last.text == null || !last.text.endsWith("/") || parts.size() == 1 && last.text.equals("/")) {
            return parts;
        }
        List<Part> trimmed = new ArrayList<>(parts.subList(0, parts.size() - 1));
        String text = last.text.substring(0, last.text.length() - 1);
        if (!text.isEmpty()) {
            trimmed.add(Part.literal(text));
        }
        return trimmed;
    }

    /**
     * A literal part, or a variable.
     *
     * @param text     The literal text
     * @param variable The name of the variable
     * @param regex    The regular expression of the variable, {@code null} for one segment
     */
    private record Part(@Nullable String text, @Nullable String variable, @Nullable String regex) {
        static Part literal(String text) {
            return new Part(text, null, null);
        }
    }

    /**
     * The compiled pattern of a template, published as a whole.
     *
     * @param pattern The pattern
     * @param groups  The groups of the variables in the pattern, in the order of the variables
     */
    private record Compiled(Pattern pattern, List<Integer> groups) {

        RouteCaptures captures(String path, Matcher matcher, List<RouteTemplateVariable> variables) {
            List<@Nullable String> values = new ArrayList<>(groups.size());
            for (int group : groups) {
                values.add(matcher.group(group));
            }
            return RouteCaptures.of(path, variables, values);
        }
    }

    /**
     * A parsed template: it matches the paths of its language.
     */
    private static final class Parsed implements RouteTemplate {

        private final @Nullable JaxRsRouteTemplateEngine engine;
        private final String expression;
        private final List<Part> parts;
        private final @Nullable Parsed rootPart;
        private final int locators;
        private final List<RouteTemplateVariable> variables;
        private final int rawLength;
        private final int patternVariableCount;
        private volatile @Nullable Compiled compiled;
        private volatile @Nullable Pattern prefixPattern;
        private volatile @Nullable JaxRsTemplateDescription description;

        /**
         * @param engine     The engine that parsed it, or {@code null} for the template of a root resource class
         * @param expression The expression, with the marks
         * @param parts      Its parts, without the marks
         * @param rootPart   The template of the {@code @Path} of its root resource class, or {@code null}
         * @param locators   The number of sub-resource locators leading to the resource method
         */
        Parsed(@Nullable JaxRsRouteTemplateEngine engine, String expression, List<Part> parts, @Nullable Parsed rootPart, int locators) {
            this.engine = engine;
            this.expression = expression;
            this.parts = parts;
            this.rootPart = rootPart;
            this.locators = locators;
            this.variables = variables(parts);
            int length = 0;
            int regexVariables = 0;
            for (Part part : parts) {
                length += part.text != null ? part.text.length() : 0;
                if (part.regex != null) {
                    regexVariables++;
                }
            }
            this.rawLength = length;
            this.patternVariableCount = regexVariables;
        }

        /**
         * @return The template of the root resource class, empty if unknown
         */
        RouteTemplate root() {
            return rootPart == null ? EMPTY : rootPart;
        }

        @Override
        public String expression() {
            return expression;
        }

        /**
         * @return The description of its structure, parsed when first used
         */
        JaxRsTemplateDescription description() {
            JaxRsTemplateDescription d = description;
            if (d == null) {
                d = JaxRsTemplateDescription.of(expression);
                description = d;
            }
            return d;
        }

        @Override
        public List<RouteTemplateVariable> variables() {
            return variables;
        }

        @Override
        public @Nullable RouteCaptures match(String rawPath) {
            String normalized = UriTemplateMatcher.normalizeForMatching(withoutMatrixParameters(rawPath));
            Compiled c = compiled();
            Matcher matcher = c.pattern.matcher(normalized);
            return matcher.matches() ? c.captures(normalized, matcher, variables) : null;
        }

        /**
         * The prefix of a sub-resource locator: the template, then the rest of the path. The
         * matched prefix is the one of the raw path, with the matrix parameters of its segments.
         */
        @Override
        public @Nullable RouteCaptures matchPrefix(String rawPath) {
            int[] indexes = new int[rawPath.length() + 1];
            String path = withoutMatrixParameters(rawPath, indexes);
            Pattern prefix = prefixPattern;
            if (prefix == null) {
                List<Part> trimmed = withoutTrailingSlash(parts);
                prefix = Pattern.compile(regex(trimmed, new ArrayList<>()).append("(/.*)?").toString());
                prefixPattern = prefix;
            }
            Matcher matcher = prefix.matcher(path);
            if (!matcher.matches()) {
                return null;
            }
            int rest = matcher.groupCount();
            int end = matcher.start(rest) < 0 ? path.length() : matcher.start(rest);
            int rawEnd = indexes[end];
            String matched = rawEnd == 0 ? "/" : rawPath.substring(0, rawEnd);
            Compiled c = compiled();
            Matcher variableMatcher = c.pattern.matcher(UriTemplateMatcher.normalizeForMatching(path.substring(0, end)));
            if (!variableMatcher.matches()) {
                return null;
            }
            return c.captures(matched, variableMatcher, variables);
        }

        /**
         * The compiled pattern, when first used: compiling it validates the regular expressions
         * of the template.
         */
        private Compiled compiled() {
            Compiled c = compiled;
            if (c == null) {
                List<Integer> groups = new ArrayList<>();
                Pattern pattern = Pattern.compile(regex(withoutTrailingSlash(parts), groups).toString());
                c = new Compiled(pattern, List.copyOf(groups));
                compiled = c;
            }
            return c;
        }

        @Override
        public RouteTemplate mount(String prefix) {
            Parsed mounted = parsed(engine, prefix + expression, prefix);
            if (engine != null && mounted.rootPart != null) {
                // the root resource class is matched under the prefix
                engine.addRoot(mounted.rootPart, rootPart, hasSubResource(mounted.expression));
                if (rootPart != null && !rootPart.expression.equals(mounted.rootPart.expression)) {
                    engine.selector.mount(rootPart.expression, mounted.rootPart);
                }
            }
            return mounted;
        }

        /**
         * The variables: a variable that repeats has the name at its last occurrence, the value
         * of a single-valued parameter; the earlier ones are named by occurrence, see
         * {@link #repeated}.
         */
        private static List<RouteTemplateVariable> variables(List<Part> parts) {
            Map<String, Integer> occurrences = new HashMap<>();
            for (Part part : parts) {
                if (part.variable != null) {
                    occurrences.merge(part.variable, 1, Integer::sum);
                }
            }
            List<RouteTemplateVariable> variables = new ArrayList<>();
            Map<String, Integer> seen = new HashMap<>();
            for (Part part : parts) {
                if (part.variable != null) {
                    int occurrence = seen.merge(part.variable, 1, Integer::sum);
                    int fromLast = Objects.requireNonNull(occurrences.get(part.variable)) - occurrence;
                    variables.add(RouteTemplateVariable.of(fromLast == 0 ? part.variable : repeated(part.variable, fromLast),
                        false, RouteTemplateVariable.Location.PATH, false, part.regex != null));
                }
            }
            return List.copyOf(variables);
        }

        @Override
        public String requiredPrefix() {
            StringBuilder prefix = new StringBuilder();
            for (Part part : withoutTrailingSlash(parts)) {
                if (part.text == null) {
                    break;
                }
                prefix.append(encode(part.text));
            }
            return prefix.toString();
        }

        @Override
        public int rawLength() {
            return rawLength;
        }

        @Override
        public int pathVariableCount() {
            return variables.size();
        }

        @Override
        public int patternVariableCount() {
            return patternVariableCount;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Parsed other && expression.equals(other.expression) && parts.equals(other.parts);
        }

        @Override
        public int hashCode() {
            return expression.hashCode();
        }

        @Override
        public String toString() {
            return withoutMarks(expression);
        }
    }
}
