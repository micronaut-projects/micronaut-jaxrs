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
import io.micronaut.http.HttpRequest;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.jaxrs.common.JaxRsHttpHeaders;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Variant;
import org.jspecify.annotations.Nullable;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The bean context implementation of {@link Request}.
 *
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Internal
@Singleton
final class JaxRsContextRequest implements Request {

    static final String SELECT_VARIANT_VARY = JaxRsContextRequest.class.getName() + ".selectVariant.vary";

    @Override
    public String getMethod() {
        return currentRequest().getMethodName();
    }

    @Override
    public Variant selectVariant(List<Variant> variants) {
        if (variants == null) {
            throw new IllegalArgumentException("Variants cannot be null");
        }
        HttpRequest<?> request = currentRequest();
        storeVaryHeaders(request, variants);
        JaxRsHttpHeaders headers = JaxRsHttpHeaders.forRequest(request.getHeaders());
        List<MediaType> acceptableMediaTypes = headers.getAcceptableMediaTypes();
        List<Locale> acceptableLanguages = headers.getAcceptableLanguages();
        List<String> acceptableEncodings = request.getHeaders().getAll(HttpHeaders.ACCEPT_ENCODING);
        return variants.stream()
            .filter(variant -> acceptsMediaType(variant.getMediaType(), acceptableMediaTypes))
            .filter(variant -> acceptsLanguage(variant.getLanguage(), acceptableLanguages))
            .filter(variant -> acceptsEncoding(variant.getEncoding(), acceptableEncodings))
            .findFirst()
            .orElse(null);
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions(EntityTag eTag) {
        if (eTag == null) {
            throw new IllegalArgumentException("Entity tag cannot be null");
        }
        HttpRequest<?> request = currentRequest();
        if (request.getHeaders().contains(HttpHeaders.IF_MATCH) && !matchesEntityTag(request, HttpHeaders.IF_MATCH, eTag, true)) {
            return preconditionFailed();
        }
        if (matchesEntityTag(request, HttpHeaders.IF_NONE_MATCH, eTag, false)) {
            return notModifiedOrPreconditionFailed(request, null, eTag);
        }
        return null;
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions(Date lastModified) {
        if (lastModified == null) {
            throw new IllegalArgumentException("Last modified date cannot be null");
        }
        HttpRequest<?> request = currentRequest();
        Date ifUnmodifiedSince = headerDate(request, HttpHeaders.IF_UNMODIFIED_SINCE);
        if (ifUnmodifiedSince != null && lastModified.after(ifUnmodifiedSince)) {
            return preconditionFailed();
        }
        Date ifModifiedSince = headerDate(request, HttpHeaders.IF_MODIFIED_SINCE);
        if (ifModifiedSince != null && !lastModified.after(ifModifiedSince)) {
            return notModified(lastModified, null);
        }
        return null;
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions(Date lastModified, EntityTag eTag) {
        if (lastModified == null) {
            throw new IllegalArgumentException("Last modified date cannot be null");
        }
        Response.ResponseBuilder responseBuilder = evaluatePreconditions(eTag);
        if (responseBuilder != null) {
            return responseBuilder;
        }
        return evaluatePreconditions(lastModified);
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions() {
        return null;
    }

    private static HttpRequest<?> currentRequest() {
        return ServerRequestContext.currentRequest()
            .orElseThrow(() -> new IllegalStateException("Current request not available"));
    }

    private static void storeVaryHeaders(HttpRequest<?> request, List<Variant> variants) {
        List<String> varyHeaders = new ArrayList<>(3);
        if (variants.stream().anyMatch(variant -> variant.getMediaType() != null)) {
            varyHeaders.add(HttpHeaders.ACCEPT);
        }
        if (variants.stream().anyMatch(variant -> variant.getLanguage() != null)) {
            varyHeaders.add(HttpHeaders.ACCEPT_LANGUAGE);
        }
        if (variants.stream().anyMatch(variant -> variant.getEncoding() != null)) {
            varyHeaders.add(HttpHeaders.ACCEPT_ENCODING);
        }
        if (!varyHeaders.isEmpty()) {
            request.setAttribute(SELECT_VARIANT_VARY, List.copyOf(varyHeaders));
        }
    }

    private static boolean acceptsMediaType(@Nullable MediaType mediaType, List<MediaType> acceptableMediaTypes) {
        return mediaType == null || acceptableMediaTypes.stream().anyMatch(mediaType::isCompatible);
    }

    private static boolean acceptsLanguage(@Nullable Locale language, List<Locale> acceptableLanguages) {
        if (language == null || acceptableLanguages.isEmpty()) {
            return true;
        }
        return acceptableLanguages.stream()
            .anyMatch(accepted -> accepted.equals(language) || accepted.getLanguage().equalsIgnoreCase(language.getLanguage()));
    }

    private static boolean acceptsEncoding(@Nullable String encoding, List<String> acceptableEncodings) {
        if (encoding == null || acceptableEncodings.isEmpty()) {
            return true;
        }
        return acceptableEncodings.stream()
            .flatMap(value -> Arrays.stream(value.split(",")))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .anyMatch(value -> acceptsEncodingValue(encoding, value));
    }

    private static boolean acceptsEncodingValue(String encoding, String value) {
        String[] parts = value.split(";", 2);
        String headerEncoding = parts[0].trim();
        if (parts.length == 2 && quality(parts[1]) == 0) {
            return false;
        }
        return "*".equals(headerEncoding) || encoding.equalsIgnoreCase(headerEncoding);
    }

    private static double quality(String parameters) {
        for (String parameter : parameters.split(";")) {
            String[] pair = parameter.trim().split("=", 2);
            if (pair.length == 2 && "q".equalsIgnoreCase(pair[0].trim())) {
                return Double.parseDouble(pair[1].trim());
            }
        }
        return 1;
    }

    private static boolean matchesEntityTag(HttpRequest<?> request, String headerName, EntityTag eTag, boolean strong) {
        return request.getHeaders().getAll(headerName).stream()
            .flatMap(value -> Arrays.stream(value.split(",")))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .anyMatch(value -> matchesEntityTagValue(value, eTag, strong));
    }

    private static boolean matchesEntityTagValue(String value, EntityTag eTag, boolean strong) {
        if ("*".equals(value)) {
            return true;
        }
        EntityTag candidate = EntityTag.valueOf(value);
        if (strong && (candidate.isWeak() || eTag.isWeak())) {
            return false;
        }
        return candidate.getValue().equals(eTag.getValue());
    }

    private static @Nullable Date headerDate(HttpRequest<?> request, String headerName) {
        ZonedDateTime date = request.getHeaders().getDate(headerName);
        return date == null ? null : Date.from(date.toInstant());
    }

    private static Response.ResponseBuilder notModifiedOrPreconditionFailed(HttpRequest<?> request,
                                                                            @Nullable Date lastModified,
                                                                            @Nullable EntityTag eTag) {
        return switch (request.getMethod()) {
            case GET, HEAD -> notModified(lastModified, eTag);
            default -> preconditionFailed();
        };
    }

    private static Response.ResponseBuilder notModified(@Nullable Date lastModified, @Nullable EntityTag eTag) {
        Response.ResponseBuilder responseBuilder = Response.notModified();
        if (lastModified != null) {
            responseBuilder.lastModified(lastModified);
        }
        if (eTag != null) {
            responseBuilder.tag(eTag);
        }
        return responseBuilder;
    }

    private static Response.ResponseBuilder preconditionFailed() {
        return Response.status(Response.Status.PRECONDITION_FAILED);
    }
}
