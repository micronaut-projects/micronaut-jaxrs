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
import io.micronaut.web.router.UriRouteMatch;
import io.micronaut.web.router.spi.RouteMatchSelector;
import io.micronaut.http.uri.ParsedRouteTemplate;
import io.micronaut.http.uri.RouteCaptures;
import io.micronaut.http.uri.RoutePattern;
import io.micronaut.http.uri.RouteTemplate;
import io.micronaut.http.uri.RouteTemplateSegment;
import io.micronaut.http.uri.RouteTemplateVariable;
import io.micronaut.http.uri.UriTemplateMatcher;
import io.micronaut.http.uri.spi.RouteTemplateEngine;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The route template language of JAX-RS: the {@code @Path} values of a resource and its methods.
 * A {@code {name}} variable matches one path segment, and a {@code {name: regex}} variable matches
 * the regular expression, which may span segments. Literal text is matched in its encoded form,
 * and the captured values are not decoded here.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
public final class JaxRsRouteTemplateEngine implements RouteTemplateEngine, RouteMatchSelector {

    /**
     * The identifier of the language.
     */
    public static final String ID = "jakarta.ws.rs";
    private static final String VERSION = "1";
    private static final String DEFAULT_REGEX = "[^/]+";
    private static final String QS = "qs";
    private static final String WILDCARD = "*";
    /**
     * The order of specificity of JAX-RS (section 3.7.2): more literal characters, then more
     * template variables, then more variables with a regular expression.
     */
    private static final Comparator<ParsedRouteTemplate> ORDER = Comparator
        .comparingInt(ParsedRouteTemplate::rawLength).reversed()
        .thenComparing(Comparator.comparingInt(ParsedRouteTemplate::pathVariableCount).reversed())
        .thenComparing(Comparator.comparingInt(ParsedRouteTemplate::patternVariableCount).reversed());

    private final Map<RouteTemplate, ParsedRouteTemplate> parsedTemplates = new ConcurrentHashMap<>();

    /**
     * A template in the language of JAX-RS.
     *
     * @param expression The joined {@code @Path} values
     * @return The template
     */
    public static RouteTemplate template(String expression) {
        return RouteTemplate.of(ID, expression);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Optional<Comparator<ParsedRouteTemplate>> comparator() {
        return Optional.of(ORDER);
    }

    /**
     * Select the resource method of JAX-RS (section 3.7.2): the most specific templates, then the
     * content type of the request against the consumed types, then the accepted types, with their
     * quality, against the produced types, with their {@code qs}. The response type is the
     * combined type of the selected method, when the method declares the types it produces.
     */
    @Override
    public List<Selection> select(HttpRequest<?> request, List<UriRouteMatch<?, ?>> matches) {
        ParsedRouteTemplate best = null;
        List<UriRouteMatch<?, ?>> candidates = new ArrayList<>(matches.size());
        for (UriRouteMatch<?, ?> match : matches) {
            ParsedRouteTemplate parsed = parsedTemplates.computeIfAbsent(match.getRouteInfo().getRouteTemplate(), this::parse);
            int compare = best == null ? -1 : ORDER.compare(parsed, best);
            if (compare < 0) {
                best = parsed;
                candidates.clear();
                candidates.add(match);
            } else if (compare == 0) {
                candidates.add(match);
            }
        }
        MediaType contentType = request.getContentType().orElse(null);
        List<MediaType> accepted = request.getHeaders().accept();
        if (accepted.isEmpty()) {
            accepted = List.of(MediaType.ALL_TYPE);
        }
        UriRouteMatch<?, ?> selected = null;
        Negotiated selectedType = null;
        int selectedConsumes = -1;
        for (UriRouteMatch<?, ?> candidate : candidates) {
            int consumes = contentType == null ? 0 : consumes(contentType, candidate.getRouteInfo().getConsumes());
            Negotiated negotiated = negotiate(accepted, candidate.getRouteInfo().getProduces());
            if (negotiated == null) {
                continue;
            }
            if (selected == null || consumes > selectedConsumes
                || consumes == selectedConsumes && negotiated.compareTo(selectedType) > 0) {
                selected = candidate;
                selectedType = negotiated;
                selectedConsumes = consumes;
            }
        }
        if (selected == null) {
            return List.of();
        }
        List<MediaType> produces = selected.getRouteInfo().getProduces();
        boolean declared = !(produces.size() == 1 && produces.get(0).equals(MediaType.ALL_TYPE));
        MediaType type = selectedType.type;
        if (declared && (specificity(type) == 0 || specificity(type) == 1 && "application".equals(type.getType()))) {
            // not concrete: JAX-RS answers with application/octet-stream
            type = MediaType.APPLICATION_OCTET_STREAM_TYPE;
        }
        return List.of(Selection.of(selected, declared ? type : null));
    }

    /**
     * The response type of JAX-RS (section 3.8) for accepted and produced types: the most
     * acceptable combined type, {@code application/octet-stream} for a wildcard one.
     *
     * @param accepted The accepted types, empty for any
     * @param produces The produced types
     * @return The type, or {@code null} if none is acceptable, or the combined type has a wildcard
     * subtype other than {@code application/*}
     */
    static @Nullable MediaType responseType(List<MediaType> accepted, List<MediaType> produces) {
        Negotiated negotiated = negotiate(accepted.isEmpty() ? List.of(MediaType.ALL_TYPE) : accepted, produces);
        if (negotiated == null) {
            return null;
        }
        MediaType type = negotiated.type;
        if (specificity(type) == 0 || specificity(type) == 1 && "application".equals(type.getType())) {
            return MediaType.APPLICATION_OCTET_STREAM_TYPE;
        }
        return specificity(type) == 2 ? type : null;
    }

    /**
     * How closely a content type matches the consumed types: 2 for a type, 1 for a type with a
     * wildcard subtype, 0 for any type.
     */
    private static int consumes(MediaType contentType, List<MediaType> consumes) {
        int best = 0;
        for (MediaType consumed : consumes) {
            if (consumed.matches(contentType)) {
                best = Math.max(best, specificity(consumed));
            }
        }
        return best;
    }

    private static @Nullable Negotiated negotiate(List<MediaType> accepted, List<MediaType> produces) {
        Negotiated best = null;
        for (MediaType accept : accepted) {
            for (MediaType produce : produces) {
                if (!accept.matches(produce) && !produce.matches(accept)) {
                    continue;
                }
                // the more specific of the two, without the q and qs parameters
                MediaType type = specificity(produce) >= specificity(accept) ? produce : accept;
                int distance = (specificity(produce) == specificity(accept) ? 0 : 1);
                Negotiated negotiated = new Negotiated(new MediaType(type.getType() + "/" + type.getSubtype()),
                    accept.getQualityAsNumber().doubleValue(), qs(produce), distance);
                if (best == null || negotiated.compareTo(best) > 0) {
                    best = negotiated;
                }
            }
        }
        return best;
    }

    private static int specificity(MediaType type) {
        if (WILDCARD.equals(type.getType())) {
            return 0;
        }
        return WILDCARD.equals(type.getSubtype()) ? 1 : 2;
    }

    private static double qs(MediaType type) {
        String qs = type.getParameters().get(QS).orElse(null);
        return qs == null ? 1 : Double.parseDouble(qs);
    }

    /**
     * A combined media type of JAX-RS: compared by specificity, then the quality of the accepted
     * type, then the {@code qs} of the produced type, then the distance, the smaller the better.
     */
    private record Negotiated(MediaType type, double q, double qs, int distance) implements Comparable<Negotiated> {
        @Override
        public int compareTo(Negotiated other) {
            int result = Integer.compare(specificity(type), specificity(other.type));
            if (result == 0) {
                result = Double.compare(q, other.q);
            }
            if (result == 0) {
                result = Double.compare(qs, other.qs);
            }
            if (result == 0) {
                result = Integer.compare(other.distance, distance);
            }
            return result;
        }
    }

    @Override
    public ParsedRouteTemplate parse(RouteTemplate template) {
        if (!ID.equals(template.engineId())) {
            throw new IllegalArgumentException("Not a JAX-RS template: " + template);
        }
        return new Parsed(template, parts(template.expression()));
    }

    @Override
    public ParsedRouteTemplate nest(ParsedRouteTemplate parent, ParsedRouteTemplate child) {
        Parsed p = (Parsed) parent;
        Parsed c = (Parsed) child;
        String childExpression = c.template.expression();
        String expression = strip(p.template.expression()) + (childExpression.startsWith("/") ? childExpression : '/' + childExpression);
        return parse(template(expression));
    }

    @Override
    public ParsedRouteTemplate mount(String prefix, ParsedRouteTemplate template) {
        // the prefix is literal: a brace in it is not a variable
        Parsed parsed = (Parsed) template;
        List<Part> parts = new ArrayList<>();
        parts.add(Part.literal(prefix));
        parts.addAll(parsed.parts);
        return new Parsed(template(prefix + parsed.template.expression()), List.copyOf(parts));
    }

    @Override
    public RoutePattern matcher(ParsedRouteTemplate template) {
        Parsed parsed = (Parsed) template;
        StringBuilder regex = new StringBuilder();
        List<Integer> groups = new ArrayList<>();
        int group = 1;
        for (Part part : withoutTrailingSlash(parsed.parts)) {
            if (part.variable == null) {
                regex.append(Pattern.quote(encode(part.text)));
            } else {
                String variableRegex = part.regex == null ? DEFAULT_REGEX : part.regex;
                groups.add(group);
                regex.append('(').append(variableRegex).append(')');
                group += 1 + Pattern.compile(variableRegex).matcher("").groupCount();
            }
        }
        Pattern pattern = Pattern.compile(regex.toString());
        List<RouteTemplateVariable> variables = parsed.variables();
        return new RoutePattern() {
            @Override
            public ParsedRouteTemplate template() {
                return parsed;
            }

            @Override
            public @Nullable RouteCaptures match(String path) {
                String normalized = UriTemplateMatcher.normalizeForMatching(path);
                Matcher matcher = pattern.matcher(normalized);
                if (!matcher.matches()) {
                    return null;
                }
                List<@Nullable String> values = new ArrayList<>(groups.size());
                for (int g : groups) {
                    values.add(matcher.group(g));
                }
                return new RouteCaptures(normalized, variables, values);
            }
        };
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
        if (last.variable != null || !last.text.endsWith("/") || parts.size() == 1 && last.text.equals("/")) {
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
     * A parsed template.
     *
     * @param template The template
     * @param parts    Its parts
     */
    private record Parsed(RouteTemplate template, List<Part> parts) implements ParsedRouteTemplate {

        @Override
        public String engineVersion() {
            return VERSION;
        }

        @Override
        public List<RouteTemplateVariable> variables() {
            List<RouteTemplateVariable> variables = new ArrayList<>();
            for (Part part : parts) {
                if (part.variable != null) {
                    variables.add(RouteTemplateVariable.path(part.variable));
                }
            }
            return variables;
        }

        @Override
        public String requiredPrefix() {
            StringBuilder prefix = new StringBuilder();
            for (Part part : withoutTrailingSlash(parts)) {
                if (part.variable != null) {
                    break;
                }
                prefix.append(encode(part.text));
            }
            return prefix.toString();
        }

        @Override
        public int rawLength() {
            int length = 0;
            for (Part part : parts) {
                length += part.variable == null ? part.text.length() : 0;
            }
            return length;
        }

        @Override
        public int pathVariableCount() {
            int count = 0;
            for (Part part : parts) {
                if (part.variable != null) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public int patternVariableCount() {
            int count = 0;
            for (Part part : parts) {
                if (part.regex != null) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public @Nullable List<RouteTemplateSegment> pathSegments() {
            // split at the slashes outside of the variables
            List<RouteTemplateSegment> segments = new ArrayList<>();
            List<Part> current = new ArrayList<>();
            for (Part part : parts) {
                if (part.variable != null) {
                    if (part.regex != null) {
                        // a regular expression may match any number of segments
                        return null;
                    }
                    current.add(part);
                    continue;
                }
                String[] pieces = part.text.split("/", -1);
                for (int i = 0; i < pieces.length; i++) {
                    if (i > 0) {
                        addSegment(segments, current);
                        current = new ArrayList<>();
                    }
                    if (!pieces[i].isEmpty()) {
                        current.add(Part.literal(pieces[i]));
                    }
                }
            }
            addSegment(segments, current);
            return segments;
        }

        private static void addSegment(List<RouteTemplateSegment> segments, List<Part> parts) {
            if (parts.isEmpty()) {
                return;
            }
            if (parts.size() == 1 && parts.get(0).variable != null) {
                segments.add(RouteTemplateSegment.VARIABLE);
            } else if (parts.stream().allMatch(p -> p.variable == null)) {
                StringBuilder literal = new StringBuilder();
                parts.forEach(p -> literal.append(encode(p.text)));
                segments.add(RouteTemplateSegment.literal(literal.toString()));
            } else {
                // literal text and a variable in one segment
                segments.add(RouteTemplateSegment.ANY);
            }
        }
    }
}
