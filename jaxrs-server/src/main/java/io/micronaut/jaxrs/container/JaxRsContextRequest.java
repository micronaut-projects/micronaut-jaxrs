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
import org.jspecify.annotations.Nullable;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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
        List<String> ifMatch = tags(request.getHeaders().getAll(HttpHeaders.IF_MATCH));
        if (!ifMatch.isEmpty() && !ifMatch.contains("*") && ifMatch.stream().noneMatch(tag -> strongMatch(tag, eTag))) {
            return Response.status(Response.Status.PRECONDITION_FAILED);
        }
        List<String> ifNoneMatch = tags(request.getHeaders().getAll(HttpHeaders.IF_NONE_MATCH));
        if (!ifNoneMatch.isEmpty() && (ifNoneMatch.contains("*") || ifNoneMatch.stream().anyMatch(tag -> weakMatch(tag, eTag)))) {
            return readOnly(request)
                ? Response.notModified(eTag)
                : Response.status(Response.Status.PRECONDITION_FAILED);
        }
        return null;
    }

    @Override
    public Response.@Nullable ResponseBuilder evaluatePreconditions(Date lastModified) {
        if (lastModified == null) {
            throw new IllegalArgumentException("No last modification date");
        }
        HttpRequest<?> request = request();
        long modified = lastModified.getTime() / 1000;
        ZonedDateTime ifUnmodifiedSince = request.getHeaders().findDate(HttpHeaders.IF_UNMODIFIED_SINCE).orElse(null);
        if (ifUnmodifiedSince != null && modified > ifUnmodifiedSince.toEpochSecond()) {
            return Response.status(Response.Status.PRECONDITION_FAILED);
        }
        ZonedDateTime ifModifiedSince = request.getHeaders().findDate(HttpHeaders.IF_MODIFIED_SINCE).orElse(null);
        if (ifModifiedSince != null && readOnly(request) && modified <= ifModifiedSince.toEpochSecond()) {
            return Response.notModified();
        }
        return null;
    }

    @Override
    public Response.@Nullable ResponseBuilder evaluatePreconditions(Date lastModified, EntityTag eTag) {
        if (lastModified == null || eTag == null) {
            throw new IllegalArgumentException("No last modification date or entity tag");
        }
        Response.ResponseBuilder byTag = evaluatePreconditions(eTag);
        if (byTag != null) {
            return byTag;
        }
        HttpRequest<?> request = request();
        long modified = lastModified.getTime() / 1000;
        // If-Unmodified-Since is ignored when If-Match is present (RFC 9110, 13.1.4)
        if (!request.getHeaders().contains(HttpHeaders.IF_MATCH)) {
            ZonedDateTime ifUnmodifiedSince = request.getHeaders().findDate(HttpHeaders.IF_UNMODIFIED_SINCE).orElse(null);
            if (ifUnmodifiedSince != null && modified > ifUnmodifiedSince.toEpochSecond()) {
                return Response.status(Response.Status.PRECONDITION_FAILED);
            }
        }
        if (request.getHeaders().contains(HttpHeaders.IF_NONE_MATCH)) {
            // If-Modified-Since is ignored when If-None-Match is present
            return null;
        }
        ZonedDateTime ifModifiedSince = request.getHeaders().findDate(HttpHeaders.IF_MODIFIED_SINCE).orElse(null);
        if (ifModifiedSince != null && readOnly(request) && modified <= ifModifiedSince.toEpochSecond()) {
            return Response.notModified(eTag);
        }
        return null;
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
        // the most specific accepted range determines the quality (RFC 9110, 12.5.1)
        double best = 0;
        int bestSpecificity = -1;
        for (Weighted weighted : accept) {
            MediaType accepted = MediaType.valueOf(weighted.value);
            if (accepted.isCompatible(mediaType)) {
                int specificity = accepted.isWildcardType() ? 0 : accepted.isWildcardSubtype() ? 1 : 2;
                // a more specific accepted type counts more
                double score = weighted.quality * (specificity == 0 ? 0.98 : specificity == 1 ? 0.99 : 1);
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

    private static List<Weighted> weighted(List<String> headers) {
        List<Weighted> values = new ArrayList<>();
        for (String header : headers) {
            for (String part : header.split(",")) {
                String[] parameters = part.trim().split(";");
                if (parameters[0].isBlank()) {
                    continue;
                }
                double quality = 1;
                for (int i = 1; i < parameters.length; i++) {
                    String parameter = parameters[i].trim();
                    if (parameter.startsWith("q=")) {
                        try {
                            quality = Double.parseDouble(parameter.substring(2));
                        } catch (NumberFormatException e) {
                            quality = 0;
                        }
                    }
                }
                String value = part.trim();
                // keep the parameters of a media type except the quality
                int q = value.indexOf(";q=");
                values.add(new Weighted(q < 0 ? value : value.substring(0, q), quality));
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
