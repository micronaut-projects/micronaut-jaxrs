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

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JaxRsSecurityHardeningTest {

    private final MicronautRuntimeDelegate delegate = new MicronautRuntimeDelegate();

    @Test
    void multipartRejectsInvalidBoundarySyntax() {
        MediaType mediaType = MediaType.valueOf("multipart/form-data; boundary=\"bad \"");

        assertThrows(BadRequestException.class, () -> JaxRsMultipart.readParts(new byte[0], mediaType));
    }

    @Test
    void multipartRejectsMalformedContentDisposition() {
        String body = """
            --abc\r
            Content-Disposition: form-data; filename="x"\r
            \r
            value\r
            --abc--\r
            """;

        assertThrows(BadRequestException.class, () -> JaxRsMultipart.readParts(body.getBytes(StandardCharsets.ISO_8859_1), multipart("abc")));
    }

    @Test
    void multipartRejectsTooManyHeaders() {
        StringBuilder body = new StringBuilder("--abc\r\n");
        body.append("Content-Disposition: form-data; name=\"field\"\r\n");
        for (int i = 0; i < 100; i++) {
            body.append("X-Test-").append(i).append(": value\r\n");
        }
        body.append("\r\nvalue\r\n--abc--\r\n");

        assertThrows(BadRequestException.class, () -> JaxRsMultipart.readParts(body.toString().getBytes(StandardCharsets.ISO_8859_1), multipart("abc")));
    }

    @Test
    void entityPartRejectsCrLfInNamesAndHeaders() {
        assertThrows(IllegalArgumentException.class, () -> EntityPart.withName("field\r\nx").content("value", String.class).build());
        assertThrows(IllegalArgumentException.class, () -> EntityPart.withName("field").header("X-Test", "ok\r\nInjected: yes"));
    }

    @Test
    void entityPartPreservesPathLikeFilenameAsMetadata() throws Exception {
        EntityPart part = EntityPart.withName("file")
            .fileName("../secret.txt")
            .content("value", String.class)
            .build();

        assertEquals("../secret.txt", part.getFileName().orElseThrow());
    }

    @Test
    void multipartWriterRejectsInjectedHeaderValues() throws Exception {
        EntityPart part = EntityPart.withName("field")
            .header("X-Test", "ok")
            .content("value", String.class)
            .build();
        part.getHeaders().add("X-Test", "bad\r\nInjected: yes");

        assertThrows(IllegalArgumentException.class, () -> JaxRsMultipart.writeParts(List.of(part), new ByteArrayOutputStream()));
    }

    @Test
    void linkSerializationEscapesParametersAndRejectsInjectedValues() {
        Link link = delegate.createLinkBuilder()
            .uri("/resource")
            .param("title", "a\"b")
            .build();

        assertEquals("</resource>; title=\"a\\\"b\"", delegate.createHeaderDelegate(Link.class).toString(link));
        assertThrows(IllegalArgumentException.class, () -> delegate.createLinkBuilder().uri("/resource").param("title", "ok\r\nbad"));
    }

    @Test
    void responseBuilderRejectsInjectedHeaderValues() {
        Response.ResponseBuilder builder = delegate.createResponseBuilder();

        assertThrows(IllegalArgumentException.class, () -> builder.header("X-Test", "ok\r\nInjected: yes"));
    }

    private static MediaType multipart(String boundary) {
        return MediaType.valueOf("multipart/form-data; boundary=" + boundary);
    }
}
