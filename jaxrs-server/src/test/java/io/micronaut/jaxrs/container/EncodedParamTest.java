package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code @Encoded} parameters get the values as they were received, not re-encoded decoded values.
 */
@MicronautTest
@Property(name = "spec.name", value = "EncodedParamTest")
class EncodedParamTest {

    @Inject
    EmbeddedServer server;

    private final HttpClient client = HttpClient.newHttpClient();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "a%2Fb|a/b",
        "a!'()~*|a!'()~*",
        "%C3%A9t%C3%A9|été",
        "a%20b|a b",
    })
    void encodedPathParam(String raw, String decoded) throws Exception {
        assertEquals(raw + "|" + decoded + "|" + raw, get("/api/encoded/path/" + raw));
    }

    @Test
    void encodedPathParamWithPlus() throws Exception {
        // the raw value keeps the plus
        String body = get("/api/encoded/path/a+b");
        assertEquals("a+b", body.substring(0, body.indexOf('|')));
        assertEquals("a+b", body.substring(body.lastIndexOf('|') + 1));
    }

    @Test
    void encodedClass() throws Exception {
        assertEquals("a%2Fb!+", get("/api/encoded-class/a%2Fb!+"));
    }

    @Test
    void encodedMatrixParam() throws Exception {
        assertEquals("a%20b+c%2F|a b+c/", get("/api/encoded/matrix;m=a%20b+c%2F"));
    }

    @Test
    void encodedFormParam() throws Exception {
        assertEquals("a+b%20c%2F!|a b c/!|a b c/!", post("/api/encoded/form", "f=a+b%20c%2F!&g=a+b%20c%2F!"));
    }

    @Test
    void encodedFormParamOfAnAsynchronousMethod() throws Exception {
        assertEquals("a+b%C3%A9|a bé", post("/api/encoded/form-async", "f=a+b%C3%A9&g=a+b%C3%A9"));
    }

    @Test
    void decodedFormParam() throws Exception {
        assertEquals("a b%", post("/api/encoded/form-decoded", "g=a+b%25"));
    }

    private String get(String path) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(server.getURL() + path)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private String post(String path, String form) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(server.getURL() + path))
                .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED)
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    @Requires(property = "spec.name", value = "EncodedParamTest")
    @Path("/encoded")
    @Produces(MediaType.TEXT_PLAIN)
    static class EncodedResource {

        @GET
        @Path("/path/{p}")
        public String path(@Encoded @PathParam("p") String raw, @PathParam("p") String decoded, @Context UriInfo uriInfo) {
            return raw + "|" + decoded + "|" + uriInfo.getPathParameters(false).getFirst("p");
        }

        @GET
        @Path("/matrix")
        public String matrix(@Encoded @MatrixParam("m") String raw, @MatrixParam("m") String decoded) {
            return raw + "|" + decoded;
        }

        @POST
        @Path("/form")
        @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
        public String form(@Encoded @FormParam("f") String raw, @FormParam("g") String decoded, MultivaluedMap<String, String> form) {
            return raw + "|" + decoded + "|" + form.getFirst("f");
        }

        @POST
        @Path("/form-async")
        @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
        public CompletionStage<String> formAsync(@Encoded @FormParam("f") String raw, @FormParam("g") String decoded) {
            return CompletableFuture.completedFuture(raw + "|" + decoded);
        }

        @POST
        @Path("/form-decoded")
        @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
        public String formDecoded(@FormParam("g") String decoded) {
            return decoded;
        }
    }

    @Requires(property = "spec.name", value = "EncodedParamTest")
    @Path("/encoded-class")
    @Produces(MediaType.TEXT_PLAIN)
    @Encoded
    static class EncodedClassResource {

        @GET
        @Path("/{p}")
        public String path(@PathParam("p") String raw) {
            return raw;
        }
    }
}
