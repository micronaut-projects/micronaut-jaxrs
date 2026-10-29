/*
 * Copyright 2017-2024 original authors
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
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.context.ServerRequestContext;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Variant;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The JAX-RS {@link Request} of the current request: its method, content negotiation of variants,
 * and the evaluation of the conditional request headers, see RFC 9110, section 13.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
@Singleton
final class JaxRsContextRequest implements Request {

    /**
     * The request attribute with the value of the {@code Vary} header of the response, set by
     * {@link #selectVariant(List)}.
     */
    static final String VARY = JaxRsContextRequest.class.getName() + ".vary";

    @Override
    public String getMethod() {
        return request().getMethodName();
    }

    @Override
    public @Nullable Variant selectVariant(List<Variant> variants) {
        if (variants == null || variants.isEmpty()) {
            throw new IllegalArgumentException("No variants");
        }
        HttpHeaders headers = request().getHeaders();
        List<Weighted> accept = weighted(headers.getAll(HttpHeaders.ACCEPT));
        List<Weighted> languages = weighted(headers.getAll(HttpHeaders.ACCEPT_LANGUAGE));
        List<Weighted> encodings = weighted(headers.getAll(HttpHeaders.ACCEPT_ENCODING));
        if (encodings.isEmpty() && headers.contains(HttpHeaders.ACCEPT_ENCODING)) {
            // an empty field accepts the identity coding only, unlike an absent one (RFC 9110, 12.5.3)
            encodings = List.of(new Weighted("identity", 1));
        }
        // the response varies with the headers of the dimensions the variants specify
        List<String> vary = new ArrayList<>(3);
        if (variants.stream().anyMatch(v -> v.getMediaType() != null)) {
            vary.add(HttpHeaders.ACCEPT);
        }
        if (variants.stream().anyMatch(v -> v.getLanguage() != null)) {
            vary.add(HttpHeaders.ACCEPT_LANGUAGE);
        }
        if (variants.stream().anyMatch(v -> v.getEncoding() != null)) {
            vary.add(HttpHeaders.ACCEPT_ENCODING);
        }
        if (!vary.isEmpty()) {
            request().setAttribute(VARY, String.join(", ", vary));
        }
        Variant best = null;
        double bestScore = 0;
        for (Variant variant : variants) {
            double score = mediaTypeScore(accept, variant.getMediaType())
                * languageScore(languages, variant.getLanguage())
                * encodingScore(encodings, variant.getEncoding());
            if (score > bestScore) {
                best = variant;
                bestScore = score;
            }
        }
        return best;
    }

    @Override
    public Response.@Nullable ResponseBuilder evaluatePreconditions(EntityTag eTag) {
        if (eTag == null) {
            throw new IllegalArgumentException("No entity tag");
        }
        HttpRequest<?> request = request();
        if (!ifMatch(request, eTag)) {
            return Response.status(Response.Status.PRECONDITION_FAILED);
        }
        return ifNoneMatch(request, eTag);
    }

    @Override
    public Response.@Nullable ResponseBuilder evaluatePreconditions(Date lastModified) {
        if (lastModified == null) {
            throw new IllegalArgumentException("No last modification date");
        }
        HttpRequest<?> request = request();
        long modified = lastModified.getTime() / 1000;
        if (!ifUnmodifiedSince(request, modified)) {
            return Response.status(Response.Status.PRECONDITION_FAILED);
        }
        return ifModifiedSince(request, modified) ? null : Response.notModified();
    }

    @Override
    public Response.@Nullable ResponseBuilder evaluatePreconditions(Date lastModified, EntityTag eTag) {
        if (lastModified == null || eTag == null) {
            throw new IllegalArgumentException("No last modification date or entity tag");
        }
        // in the order of RFC 9110, 13.2.2: If-Unmodified-Since is ignored when If-Match is present,
        // and If-Modified-Since when If-None-Match is
        HttpRequest<?> request = request();
        long modified = lastModified.getTime() / 1000;
        boolean unmodified = request.getHeaders().contains(HttpHeaders.IF_MATCH)
            ? ifMatch(request, eTag)
            : ifUnmodifiedSince(request, modified);
        if (!unmodified) {
            return Response.status(Response.Status.PRECONDITION_FAILED);
        }
        if (request.getHeaders().contains(HttpHeaders.IF_NONE_MATCH)) {
            return ifNoneMatch(request, eTag);
        }
        return ifModifiedSince(request, modified) ? null : Response.notModified(eTag);
    }

    @Override
    public Response.@Nullable ResponseBuilder evaluatePreconditions() {
        // the resource does not exist: any If-Match fails
        return request().getHeaders().contains(HttpHeaders.IF_MATCH)
            ? Response.status(Response.Status.PRECONDITION_FAILED)
            : null;
    }

    private static HttpRequest<?> request() {
        return ServerRequestContext.currentRequest()
            .orElseThrow(() -> new IllegalStateException("No request in the current context"));
    }

    /**
     * @return Whether the If-Match condition holds: true without the header, false when it lists no tag
     */
    private static boolean ifMatch(HttpRequest<?> request, EntityTag eTag) {
        if (!request.getHeaders().contains(HttpHeaders.IF_MATCH)) {
            return true;
        }
        List<String> ifMatch = tags(request.getHeaders().getAll(HttpHeaders.IF_MATCH));
        return ifMatch.contains("*") || ifMatch.stream().anyMatch(tag -> strongMatch(tag, eTag));
    }

    /**
     * @return The response when the If-None-Match condition does not hold, else {@code null}
     */
    private static Response.@Nullable ResponseBuilder ifNoneMatch(HttpRequest<?> request, EntityTag eTag) {
        List<String> ifNoneMatch = tags(request.getHeaders().getAll(HttpHeaders.IF_NONE_MATCH));
        if (ifNoneMatch.contains("*") || ifNoneMatch.stream().anyMatch(tag -> weakMatch(tag, eTag))) {
            return readOnly(request)
                ? Response.notModified(eTag)
                : Response.status(Response.Status.PRECONDITION_FAILED);
        }
        return null;
    }

    /**
     * @return Whether the If-Unmodified-Since condition holds: true without a valid date
     */
    private static boolean ifUnmodifiedSince(HttpRequest<?> request, long modified) {
        Long ifUnmodifiedSince = date(request, HttpHeaders.IF_UNMODIFIED_SINCE);
        return ifUnmodifiedSince == null || modified <= ifUnmodifiedSince;
    }

    /**
     * @return Whether the If-Modified-Since condition holds: true without a valid date or for an
     * unsafe method
     */
    private static boolean ifModifiedSince(HttpRequest<?> request, long modified) {
        Long ifModifiedSince = date(request, HttpHeaders.IF_MODIFIED_SINCE);
        return ifModifiedSince == null || !readOnly(request) || modified > ifModifiedSince;
    }

    /**
     * The epoch seconds of a date header, in any of the formats of RFC 9110, 5.6.7.
     *
     * @return The seconds, {@code null} if the header is missing or not a valid date
     */
    private static @Nullable Long date(HttpRequest<?> request, String name) {
        String value = request.getHeaders().get(name);
        if (value == null) {
            return null;
        }
        try {
            return RuntimeDelegate.getInstance().createHeaderDelegate(Date.class).fromString(value).getTime() / 1000;
        } catch (IllegalArgumentException e) {
            // an invalid date is ignored
            return null;
        }
    }

    private static boolean readOnly(HttpRequest<?> request) {
        return request.getMethod() == HttpMethod.GET || request.getMethod() == HttpMethod.HEAD;
    }

    /**
     * The entity tags of conditional headers, as they appear: {@code "x"}, {@code W/"x"} or
     * {@code *}.
     */
    private static List<String> tags(List<String> headers) {
        List<String> tags = new ArrayList<>();
        for (String header : headers) {
            StringBuilder current = new StringBuilder();
            boolean quoted = false;
            for (char c : header.toCharArray()) {
                if (c == '"') {
                    quoted = !quoted;
                }
                if (c == ',' && !quoted) {
                    add(tags, current);
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
            add(tags, current);
        }
        return tags;
    }

    private static void add(List<String> tags, StringBuilder tag) {
        String value = tag.toString().trim();
        if (!value.isEmpty()) {
            tags.add(value);
        }
    }

    private static boolean weak(String tag) {
        return tag.startsWith("W/");
    }

    private static String opaque(String tag) {
        String value = weak(tag) ? tag.substring(2) : tag;
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"") ? value.substring(1, value.length() - 1) : value;
    }

    private static boolean strongMatch(String tag, EntityTag eTag) {
        return !weak(tag) && !eTag.isWeak() && opaque(tag).equals(eTag.getValue());
    }

    private static boolean weakMatch(String tag, EntityTag eTag) {
        return opaque(tag).equals(eTag.getValue());
    }

    private static double mediaTypeScore(List<Weighted> accept, @Nullable MediaType mediaType) {
        if (mediaType == null || accept.isEmpty()) {
            return 1;
        }
        // the most specific accepted range determines the quality (RFC 9110, 12.5.1); a range with
        // parameters is more specific than the same range without them, and determines the
        // quality of a type with the same parameters
        double score = mediaTypeScore(accept, mediaType, true);
        // else, as by the route negotiation, the parameters of the ranges are not matched
        return score >= 0 ? score : Math.max(mediaTypeScore(accept, mediaType, false), 0);
    }

    /**
     * @return The score of the most specific matching range, -1 if none matches
     */
    private static double mediaTypeScore(List<Weighted> accept, MediaType mediaType, boolean withParameters) {
        double best = -1;
        int bestSpecificity = -1;
        for (Weighted weighted : accept) {
            MediaType accepted;
            try {
                accepted = MediaType.valueOf(weighted.value);
            } catch (IllegalArgumentException e) {
                // an invalid media range is ignored, as by the route negotiation
                continue;
            }
            if (accepted.isCompatible(mediaType) && (!withParameters || parametersMatch(accepted, mediaType))) {
                int level = accepted.isWildcardType() ? 0 : accepted.isWildcardSubtype() ? 1 : 2;
                int specificity = withParameters ? level * 1000 + parameterCount(accepted) : level;
                // a more specific accepted type counts more
                double score = weighted.quality * (level == 0 ? 0.98 : level == 1 ? 0.99 : 1);
                if (specificity > bestSpecificity) {
                    bestSpecificity = specificity;
                    best = score;
                } else if (specificity == bestSpecificity) {
                    best = Math.max(best, score);
                }
            }
        }
        return best;
    }

    private static double languageScore(List<Weighted> languages, @Nullable Locale language) {
        if (language == null || languages.isEmpty()) {
            return 1;
        }
        String tag = language.toLanguageTag().toLowerCase(Locale.ENGLISH);
        // the most specific matching range determines the quality
        double best = 0;
        int bestSpecificity = -1;
        for (Weighted weighted : languages) {
            String accepted = weighted.value.toLowerCase(Locale.ENGLISH);
            if (accepted.equals("*") || accepted.equals(tag) || tag.startsWith(accepted + "-") || accepted.startsWith(tag + "-")) {
                int specificity = accepted.equals("*") ? 0 : accepted.length();
                if (specificity > bestSpecificity) {
                    bestSpecificity = specificity;
                    best = weighted.quality;
                } else if (specificity == bestSpecificity) {
                    best = Math.max(best, weighted.quality);
                }
            }
        }
        return best;
    }

    /**
     * The quality of a content coding, where no coding is the identity coding, acceptable unless
     * excluded explicitly or by a zero-weighted wildcard (RFC 9110, 12.5.3).
     */
    private static double encodingScore(List<Weighted> accepted, @Nullable String encoding) {
        if (accepted.isEmpty()) {
            return 1;
        }
        String value = encoding == null ? "identity" : encoding;
        double wildcard = -1;
        double exact = -1;
        for (Weighted weighted : accepted) {
            if (weighted.value.equalsIgnoreCase(value)) {
                exact = Math.max(exact, weighted.quality);
            } else if (weighted.value.equals("*")) {
                wildcard = Math.max(wildcard, weighted.quality);
            }
        }
        if (exact >= 0) {
            return exact;
        }
        if (wildcard >= 0) {
            return wildcard;
        }
        return value.equalsIgnoreCase("identity") ? 1 : 0;
    }

    /**
     * Whether a type has every parameter of an accepted range, but the quality.
     */
    private static boolean parametersMatch(MediaType range, MediaType type) {
        for (Map.Entry<String, String> parameter : range.getParameters().entrySet()) {
            if ("q".equalsIgnoreCase(parameter.getKey())) {
                continue;
            }
            String value = type.getParameters().get(parameter.getKey());
            if (value == null || !value.equalsIgnoreCase(parameter.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static int parameterCount(MediaType range) {
        int count = 0;
        for (String name : range.getParameters().keySet()) {
            if (!"q".equalsIgnoreCase(name)) {
                count++;
            }
        }
        return Math.min(count, 999);
    }

    private static List<Weighted> weighted(List<String> headers) {
        List<Weighted> values = new ArrayList<>();
        for (String header : headers) {
            for (String part : header.split(",")) {
                String[] parameters = part.trim().split(";");
                if (parameters[0].isBlank()) {
                    continue;
                }
                double quality = 1;
                // keep the parameters of a media type except the quality, whatever the whitespace
                // around them (RFC 9110, 5.6.6)
                StringBuilder value = new StringBuilder(parameters[0].trim());
                for (int i = 1; i < parameters.length; i++) {
                    String parameter = parameters[i].trim();
                    int equals = parameter.indexOf('=');
                    if (equals > 0 && parameter.substring(0, equals).trim().equalsIgnoreCase("q")) {
                        try {
                            quality = Double.parseDouble(parameter.substring(equals + 1).trim());
                        } catch (NumberFormatException e) {
                            quality = 0;
                        }
                    } else {
                        value.append(';').append(parameter);
                    }
                }
                values.add(new Weighted(value.toString(), quality));
            }
        }
        return values;
    }

    /**
     * An accepted value and its quality.
     *
     * @param value   The value
     * @param quality The quality
     */
    private record Weighted(String value, double quality) {
    }
}
