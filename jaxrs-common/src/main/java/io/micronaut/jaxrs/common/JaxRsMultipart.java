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
package io.micronaut.jaxrs.common;

import io.micronaut.core.annotation.Internal;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Multipart/form-data parsing and serialization helpers.
 */
@Internal
public final class JaxRsMultipart {

    public static final String MULTIPART_FORM_DATA = "multipart/form-data";
    public static final String CONTENT_DISPOSITION = "Content-Disposition";
    public static final String CONTENT_TYPE = "Content-Type";
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private JaxRsMultipart() {
    }

    /**
     * @param mediaType The media type
     * @return Whether the media type is multipart/form-data
     */
    public static boolean isMultipartFormData(@Nullable MediaType mediaType) {
        return mediaType != null
            && "multipart".equalsIgnoreCase(mediaType.getType())
            && "form-data".equalsIgnoreCase(mediaType.getSubtype());
    }

    /**
     * @param inputStream The entity stream
     * @param mediaType The multipart media type
     * @return The parsed entity parts
     * @throws IOException If reading fails
     */
    public static List<EntityPart> readParts(InputStream inputStream, MediaType mediaType) throws IOException {
        return readParts(inputStream.readAllBytes(), mediaType);
    }

    /**
     * @param bytes The body bytes
     * @param mediaType The multipart media type
     * @return The parsed entity parts
     */
    public static List<EntityPart> readParts(byte[] bytes, MediaType mediaType) {
        String boundary = boundary(mediaType);
        if (boundary == null || boundary.isBlank()) {
            throw new BadRequestException("Missing multipart boundary");
        }
        String body = new String(bytes, StandardCharsets.ISO_8859_1);
        String marker = "--" + boundary;
        List<EntityPart> parts = new ArrayList<>();
        int markerStart = body.indexOf(marker);
        while (markerStart > -1) {
            int partStart = markerStart + marker.length();
            if (body.startsWith("--", partStart)) {
                break;
            }
            partStart = skipLineBreak(body, partStart);
            int headersEnd = headerEnd(body, partStart);
            if (headersEnd < 0) {
                break;
            }
            String headerText = body.substring(partStart, headersEnd);
            int contentStart = headersEnd + headerSeparatorLength(body, headersEnd);
            int nextMarkerStart = nextMarker(body, marker, contentStart);
            if (nextMarkerStart < 0) {
                break;
            }
            int contentEnd = trimLineBreakBefore(body, nextMarkerStart);
            MultivaluedMap<String, String> headers = parseHeaders(headerText);
            EntityPart part = part(headers, body.substring(contentStart, contentEnd).getBytes(StandardCharsets.ISO_8859_1));
            if (part != null) {
                parts.add(part);
            }
            markerStart = nextMarkerStart + 1;
            markerStart = body.indexOf(marker, markerStart);
        }
        return parts;
    }

    /**
     * Writes multipart/form-data.
     *
     * @param parts The entity parts
     * @param outputStream The target stream
     * @return The generated boundary
     * @throws IOException If writing fails
     */
    public static String writeParts(List<EntityPart> parts, OutputStream outputStream) throws IOException {
        String boundary = "MicronautJaxRsBoundary" + UUID.randomUUID().toString().replace("-", "");
        for (EntityPart part : parts) {
            outputStream.write(("--" + boundary).getBytes(StandardCharsets.ISO_8859_1));
            outputStream.write(CRLF);
            for (Map.Entry<String, List<String>> entry : part.getHeaders().entrySet()) {
                for (String value : entry.getValue()) {
                    outputStream.write((entry.getKey() + ": " + value).getBytes(StandardCharsets.ISO_8859_1));
                    outputStream.write(CRLF);
                }
            }
            outputStream.write(CRLF);
            try (InputStream content = part.getContent()) {
                content.transferTo(outputStream);
            }
            outputStream.write(CRLF);
        }
        outputStream.write(("--" + boundary + "--").getBytes(StandardCharsets.ISO_8859_1));
        outputStream.write(CRLF);
        return boundary;
    }

    private static @Nullable EntityPart part(MultivaluedMap<String, String> headers, byte[] bytes) {
        String disposition = first(headers, CONTENT_DISPOSITION);
        if (disposition == null) {
            return null;
        }
        Map<String, String> parameters = headerParameters(disposition);
        String name = parameters.get("name");
        if (name == null || name.isBlank()) {
            return null;
        }
        String contentType = first(headers, CONTENT_TYPE);
        MediaType mediaType = contentType == null ? MediaType.TEXT_PLAIN_TYPE : MediaType.valueOf(contentType);
        return JaxRsEntityPart.parsed(name, parameters.get("filename"), mediaType, headers, bytes);
    }

    private static @Nullable String boundary(MediaType mediaType) {
        String boundary = mediaType.getParameters().get("boundary");
        if (boundary == null) {
            return null;
        }
        return unquote(boundary);
    }

    private static MultivaluedMap<String, String> parseHeaders(String headerText) {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        for (String line : headerText.replace("\r\n", "\n").split("\n")) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            headers.add(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
        }
        return headers;
    }

    private static Map<String, String> headerParameters(String headerValue) {
        List<String> tokens = splitHeaderValue(headerValue);
        MultivaluedMap<String, String> result = new MultivaluedHashMap<>();
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            int separator = token.indexOf('=');
            if (separator > 0) {
                result.putSingle(token.substring(0, separator).trim().toLowerCase(Locale.ROOT), unquote(token.substring(separator + 1).trim()));
            }
        }
        return result.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().get(0)));
    }

    private static List<String> splitHeaderValue(String value) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
            } else if (c == '\\' && quoted) {
                escaped = true;
            } else if (c == '"') {
                quoted = !quoted;
                current.append(c);
            } else if (c == ';' && !quoted) {
                result.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        result.add(current.toString().trim());
        return result;
    }

    private static @Nullable String first(MultivaluedMap<String, String> headers, String name) {
        String value = headers.getFirst(name);
        if (value != null) {
            return value;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    private static String unquote(String value) {
        if (value.length() > 1 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            StringBuilder result = new StringBuilder(value.length() - 2);
            boolean escaped = false;
            for (int i = 1; i < value.length() - 1; i++) {
                char c = value.charAt(i);
                if (escaped) {
                    result.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else {
                    result.append(c);
                }
            }
            return result.toString();
        }
        return value;
    }

    private static int skipLineBreak(String body, int index) {
        if (body.startsWith("\r\n", index)) {
            return index + 2;
        }
        if (body.startsWith("\n", index)) {
            return index + 1;
        }
        return index;
    }

    private static int headerEnd(String body, int start) {
        int crlf = body.indexOf("\r\n\r\n", start);
        int lf = body.indexOf("\n\n", start);
        if (crlf < 0) {
            return lf;
        }
        if (lf < 0) {
            return crlf;
        }
        return Math.min(crlf, lf);
    }

    private static int headerSeparatorLength(String body, int index) {
        return body.startsWith("\r\n\r\n", index) ? 4 : 2;
    }

    private static int nextMarker(String body, String marker, int start) {
        int crlf = body.indexOf("\r\n" + marker, start);
        int lf = body.indexOf("\n" + marker, start);
        if (crlf < 0) {
            return lf;
        }
        if (lf < 0) {
            return crlf;
        }
        return Math.min(crlf, lf);
    }

    private static int trimLineBreakBefore(String body, int markerStart) {
        if (markerStart > 0 && body.charAt(markerStart) == '\n') {
            if (markerStart > 1 && body.charAt(markerStart - 1) == '\r') {
                return markerStart - 1;
            }
            return markerStart;
        }
        return markerStart;
    }

    /**
     * @param inputStream The input stream
     * @return The bytes
     * @throws IOException If reading fails
     */
    public static byte[] readAll(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        inputStream.transferTo(outputStream);
        return outputStream.toByteArray();
    }
}
