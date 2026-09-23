package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multipart entities as {@link EntityPart}s (JAX-RS 3.1 sections 3.5.4 and 4.2.4).
 */
@MicronautTest
@Property(name = "spec.name", value = "MultipartTest")
class MultipartTest {

    private static final String BODY = """
        --test-boundary\r
        Content-Disposition: form-data; name="text"\r
        Content-Type: text/plain\r
        \r
        some text\r
        --test-boundary\r
        Content-Disposition: form-data; name="file"; filename="file.txt"\r
        Content-Type: application/octet-stream\r
        X-Extra: extra\r
        \r
        file content\r
        --test-boundary--\r
        """;

    @Inject
    EmbeddedServer server;

    private HttpResponse<String> post(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.getURL() + "/api/multipart/" + path))
            .header("Content-Type", "multipart/form-data; boundary=test-boundary")
            .POST(HttpRequest.BodyPublishers.ofString(BODY))
            .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void partsAreReadAsAList() throws Exception {
        HttpResponse<String> response = post("list");
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("text:text/plain:null:some text|file:application/octet-stream:file.txt:file content:extra", response.body());
    }

    @Test
    void partsAreFormParameters() throws Exception {
        HttpResponse<String> response = post("params");
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("some text|file.txt:file content|file content", response.body());
    }

    @Test
    void partsAreWrittenWithABoundary() throws Exception {
        HttpResponse<String> response = post("echo");
        assertEquals(200, response.statusCode(), response.body());
        String contentType = response.headers().firstValue("Content-Type").orElseThrow();
        assertTrue(contentType.startsWith("multipart/form-data;boundary="), contentType + " " + response.body());
        String boundary = contentType.substring(contentType.indexOf('=') + 1);
        assertEquals("--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"echoed\"; filename=\"echo.txt\"\r\n"
            + "Content-Type: text/plain\r\n"
            + "X-Part: part\r\n"
            + "\r\n"
            + "some text\r\n"
            + "--" + boundary + "--\r\n", response.body());
    }

    @Requires(property = "spec.name", value = "MultipartTest")
    @Path("/multipart")
    public static class MultipartResource {

        @POST
        @Path("list")
        @Consumes(MediaType.MULTIPART_FORM_DATA)
        public String list(List<EntityPart> parts) {
            return parts.stream().map(part -> {
                try {
                    String extra = part.getHeaders().getFirst("X-Extra");
                    return part.getName() + ":" + part.getMediaType() + ":" + part.getFileName().orElse(null) + ":"
                        + part.getContent(String.class) + (extra == null ? "" : ":" + extra);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).collect(Collectors.joining("|"));
        }

        @POST
        @Path("params")
        @Consumes(MediaType.MULTIPART_FORM_DATA)
        public String params(@FormParam("text") String text,
                             @FormParam("file") EntityPart file,
                             @FormParam("file") InputStream stream) throws IOException {
            return text + "|" + file.getFileName().orElse(null) + ":" + file.getContent(String.class)
                + "|" + new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        @POST
        @Path("echo")
        @Consumes(MediaType.MULTIPART_FORM_DATA)
        @Produces(MediaType.MULTIPART_FORM_DATA)
        public Response echo(List<EntityPart> parts) throws IOException {
            List<EntityPart> echoed = List.of(EntityPart.withName("echoed")
                .content("echo.txt", parts.get(0).getContent())
                .mediaType(MediaType.TEXT_PLAIN_TYPE)
                .header("X-Part", "part")
                .build());
            return Response.ok(new GenericEntity<>(echoed) {
            }, MediaType.MULTIPART_FORM_DATA).build();
        }
    }
}
