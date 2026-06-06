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
import io.micronaut.http.multipart.CompletedPart;
import io.micronaut.http.multipart.FormFieldMetadata;
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
    private static final String DEFAULT_BOUNDARY_PREFIX = "MicronautJaxRsBoundary";
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final int MAX_BOUNDARY_LENGTH = 70;
    private static final int MAX_PARTS = 1_000;
    private static final int MAX_HEADERS_PER_PART = 100;
    private static final int MAX_HEADER_SECTION_LENGTH = 64 * 1024;
    private static final int MAX_HEADER_LINE_LENGTH = 8 * 1024;

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
        if (boundary == null) {
            throw new BadRequestException("Missing multipart boundary");
        }
        validateBoundary(boundary);
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
                throw new BadRequestException("Malformed multipart headers");
            }
            String headerText = body.substring(partStart, headersEnd);
            if (headerText.length() > MAX_HEADER_SECTION_LENGTH) {
                throw new BadRequestException("Multipart headers are too large");
            }
            int contentStart = headersEnd + headerSeparatorLength(body, headersEnd);
            int nextMarkerStart = nextMarker(body, marker, contentStart);
            if (nextMarkerStart < 0) {
                throw new BadRequestException("Multipart closing boundary is missing");
            }
            int contentEnd = trimLineBreakBefore(body, nextMarkerStart);
            MultivaluedMap<String, String> headers = parseHeaders(headerText);
            EntityPart part = part(headers, body.substring(contentStart, contentEnd).getBytes(StandardCharsets.ISO_8859_1));
            if (part != null) {
                if (parts.size() == MAX_PARTS) {
                    throw new BadRequestException("Too many multipart parts");
                }
                parts.add(part);
            }
            markerStart = nextMarkerStart + 1;
            markerStart = body.indexOf(marker, markerStart);
        }
        return parts;
    }

    /**
     * Creates a JAX-RS entity part from a completed Micronaut multipart part.
     *
     * @param part The completed Micronaut part
     * @return The JAX-RS entity part
     * @throws IOException If the part content cannot be read
     */
    public static EntityPart entityPart(CompletedPart part) throws IOException {
        FormFieldMetadata metadata = part.getMetadata();
        String partName = metadata.name();
        if (partName == null || partName.isBlank()) {
            throw new BadRequestException("Multipart entity part name is required");
        }
        String partFileName = metadata.fileName();
        MediaType partMediaType = metadata.mediaType() == null ? MediaType.TEXT_PLAIN_TYPE : JaxRsUtils.convert(metadata.mediaType());
        MultivaluedMap<String, String> partHeaders = new MultivaluedHashMap<>();
        partHeaders.putSingle(CONTENT_DISPOSITION, contentDisposition(partName, partFileName));
        partHeaders.putSingle(CONTENT_TYPE, partMediaType.toString());
        return JaxRsEntityPart.parsed(partName, partFileName, partMediaType, partHeaders, part.getBytes());
    }

    /**
     * Writes multipart/form-data.
     *
     * @param parts The entity parts
     * @param outputStream The target stream
     * @param mediaType The outbound media type
     * @return The generated boundary
     * @throws IOException If writing fails
     */
    public static String writeParts(List<EntityPart> parts, OutputStream outputStream, @Nullable MediaType mediaType) throws IOException {
        String boundary = boundary(mediaType);
        if (boundary == null) {
            boundary = DEFAULT_BOUNDARY_PREFIX + UUID.randomUUID().toString().replace("-", "");
        } else {
            validateBoundary(boundary);
        }
        return writeParts(parts, outputStream, boundary);
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
        return writeParts(parts, outputStream, (MediaType) null);
    }

    private static String writeParts(List<EntityPart> parts, OutputStream outputStream, String boundary) throws IOException {
        for (EntityPart part : parts) {
            outputStream.write(("--" + boundary).getBytes(StandardCharsets.ISO_8859_1));
            outputStream.write(CRLF);
            for (Map.Entry<String, List<String>> entry : part.getHeaders().entrySet()) {
                String name = JaxRsHeaderValues.validateToken(entry.getKey());
                for (String value : entry.getValue()) {
                    outputStream.write((name + ": " + JaxRsHeaderValues.validateHeaderValue(value)).getBytes(StandardCharsets.ISO_8859_1));
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
        JaxRsHeaderValues.validateHeaderValue(disposition);
        Map<String, String> parameters = headerParameters(disposition);
        String name = parameters.get("name");
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Multipart Content-Disposition name is required");
        }
        String contentType = first(headers, CONTENT_TYPE);
        if (contentType != null) {
            JaxRsHeaderValues.validateHeaderValue(contentType);
        }
        MediaType mediaType = contentType == null ? MediaType.TEXT_PLAIN_TYPE : MediaType.valueOf(contentType);
        return JaxRsEntityPart.parsed(name, parameters.get("filename"), mediaType, headers, bytes);
    }

    private static @Nullable String boundary(MediaType mediaType) {
        if (mediaType == null) {
            return null;
        }
        String boundary = mediaType.getParameters().get("boundary");
        if (boundary == null) {
            return null;
        }
        return unquote(boundary);
    }

    private static String contentDisposition(String name, @Nullable String fileName) {
        String value = "form-data; name=" + JaxRsHeaderValues.quoteParameterValue(name);
        if (fileName != null) {
            value += "; filename=" + JaxRsHeaderValues.quoteParameterValue(fileName);
        }
        return value;
    }

    private static MultivaluedMap<String, String> parseHeaders(String headerText) {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        int count = 0;
        for (String line : headerText.replace("\r\n", "\n").split("\n")) {
            if (line.length() > MAX_HEADER_LINE_LENGTH) {
                throw new BadRequestException("Multipart header line is too large");
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new BadRequestException("Malformed multipart header");
            }
            if (++count > MAX_HEADERS_PER_PART) {
                throw new BadRequestException("Too many multipart headers");
            }
            headers.add(
                JaxRsHeaderValues.validateToken(line.substring(0, separator).trim()),
                JaxRsHeaderValues.validateHeaderValue(line.substring(separator + 1).trim())
            );
        }
        return headers;
    }

    private static Map<String, String> headerParameters(String headerValue) {
        List<String> tokens = splitHeaderValue(headerValue);
        if (tokens.isEmpty() || !"form-data".equalsIgnoreCase(tokens.get(0))) {
            throw new BadRequestException("Malformed multipart Content-Disposition");
        }
        MultivaluedMap<String, String> result = new MultivaluedHashMap<>();
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            int separator = token.indexOf('=');
            if (separator > 0) {
                String name = token.substring(0, separator).trim().toLowerCase(Locale.ROOT);
                result.putSingle(
                    JaxRsHeaderValues.validateToken(name),
                    JaxRsHeaderValues.validateHeaderValue(unquote(token.substring(separator + 1).trim()))
                );
            } else {
                throw new BadRequestException("Malformed multipart Content-Disposition parameter");
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
            if (escaped) {
                throw new BadRequestException("Malformed quoted multipart parameter");
            }
            return result.toString();
        }
        return value;
    }

    private static void validateBoundary(String boundary) {
        if (boundary.isBlank() || boundary.length() > MAX_BOUNDARY_LENGTH) {
            throw new BadRequestException("Invalid multipart boundary");
        }
        for (int i = 0; i < boundary.length(); i++) {
            char c = boundary.charAt(i);
            boolean valid = c >= '0' && c <= '9'
                || c >= 'A' && c <= 'Z'
                || c >= 'a' && c <= 'z'
                || c == '\'' || c == '(' || c == ')' || c == '+'
                || c == '_' || c == ',' || c == '-' || c == '.'
                || c == '/' || c == ':' || c == '=' || c == '?'
                || c == ' ';
            if (!valid || c == ' ' && i == boundary.length() - 1) {
                throw new BadRequestException("Invalid multipart boundary");
            }
        }
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
