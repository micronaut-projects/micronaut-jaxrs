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
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JaxRsSecurityHardeningTest {

    private final MicronautRuntimeDelegate delegate = new MicronautRuntimeDelegate();

    @TempDir
    Path tempDir;

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
    void multipartWriterUsesConfiguredBoundary() throws Exception {
        EntityPart part = EntityPart.withName("field")
            .content("value", String.class)
            .build();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        String boundary = JaxRsMultipart.writeParts(List.of(part), outputStream, multipart("customBoundary"));

        assertEquals("customBoundary", boundary);
        assertTrue(outputStream.toString(StandardCharsets.ISO_8859_1).startsWith("--customBoundary\r\n"));
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

    @Test
    void temporaryFilesUseConfiguredPrivateDirectory() throws Exception {
        Path configured = Files.createDirectory(tempDir.resolve("jaxrs"));

        Path file = JaxRsTemporaryFiles.createTempFile("entity-", ".tmp", configured).toPath();

        assertEquals(configured, file.getParent());
    }

    @Test
    void defaultTemporaryDirectoryRequiresUserHome() {
        String userHome = System.getProperty("user.home");
        try {
            System.clearProperty("user.home");

            IOException exception = assertThrows(IOException.class, () -> JaxRsTemporaryFiles.createTempFile("entity-", ".tmp", null));
            assertEquals("JAX-RS temporary directory requires user.home or micronaut.jaxrs.temp-directory", exception.getMessage());
        } finally {
            if (userHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", userHome);
            }
        }
    }

    private static MediaType multipart(String boundary) {
        return MediaType.valueOf("multipart/form-data; boundary=" + boundary);
    }
}
