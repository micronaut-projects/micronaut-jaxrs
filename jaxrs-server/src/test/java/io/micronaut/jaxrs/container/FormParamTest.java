package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.multipart.MultipartBody;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.EntityPart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertEquals;

@MicronautTest
@Property(name = "spec.name", value = "FormParamTest")
class FormParamTest {

    @Inject
    @Client("/api/form-param")
    HttpClient client;

    @ParameterizedTest
    @ValueSource(strings = {
        "/string-array",
        "/string-list",
        "/long-array",
        "/long-list"
    })
    void checkParams(String path) {
        String retrieve = client.toBlocking().retrieve(form(path, "a=1&a=10&a=11"), String.class);
        assertEquals(methodCase(path) + " -> 1,10,11", retrieve);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/string-array",
        "/string-list",
        "/long-array",
        "/long-list"
    })
    void checkMultipartParams(String path) {
        String retrieve = client.toBlocking().retrieve(multipart(path, MultipartBody.builder()
            .addPart("a", "1")
            .addPart("a", "10")
            .addPart("a", "11")
            .build()), String.class);
        assertEquals(methodCase(path) + " -> 1,10,11", retrieve);
    }

    @Test
    void checkMultipartEntityPart() {
        String retrieve = client.toBlocking().retrieve(multipart("/entity-part", MultipartBody.builder()
            .addPart("a", "part.txt", MediaType.TEXT_PLAIN_TYPE, "hello".getBytes(StandardCharsets.UTF_8))
            .build()), String.class);
        assertEquals("entityPart -> a:part.txt:text/plain; charset=UTF-8:hello", retrieve);
    }

    @Test
    void checkMultipartEntityPartList() {
        String retrieve = client.toBlocking().retrieve(multipart("/entity-part-list", MultipartBody.builder()
            .addPart("a", "one")
            .addPart("b", "ignored")
            .addPart("a", "two")
            .build()), String.class);
        assertEquals("entityPartList -> a:-:text/plain; charset=UTF-8:one,a:-:text/plain; charset=UTF-8:two", retrieve);
    }

    @Test
    void checkMultipartEntityParameterList() {
        String retrieve = client.toBlocking().retrieve(multipart("/entity-parts", MultipartBody.builder()
            .addPart("a", "one")
            .addPart("b", "two")
            .build()), String.class);
        assertEquals("entityParts -> a:-:text/plain; charset=UTF-8:one,b:-:text/plain; charset=UTF-8:two", retrieve);
    }

    @Test
    void checkMultipartBinaryParams() {
        String bytes = client.toBlocking().retrieve(multipart("/bytes", MultipartBody.builder()
            .addPart("a", "bytes.bin", MediaType.APPLICATION_OCTET_STREAM_TYPE, "bytes".getBytes(StandardCharsets.UTF_8))
            .build()), String.class);
        assertEquals("bytes -> bytes", bytes);

        String stream = client.toBlocking().retrieve(multipart("/stream", MultipartBody.builder()
            .addPart("a", "stream.txt", MediaType.TEXT_PLAIN_TYPE, "stream".getBytes(StandardCharsets.UTF_8))
            .build()), String.class);
        assertEquals("stream -> stream", stream);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/default",
        "/missing"
    })
    void checkDefaults(String path) {
        String retrieve = client.toBlocking().retrieve(form(path, ""), String.class);
        String method = path.equals("/default") ? "defaultValue" : "missingValue";
        assertEquals(method + " -> default", retrieve);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/decoded",
        "/encoded"
    })
    void checkEncoded(String path) {
        String retrieve = client.toBlocking().retrieve(form(path, "a=_%60%27%24X+Y%40%22a+a%22"), String.class);
        if (path.equals("/encoded")) {
            assertEquals("encoded -> _%60%27%24X+Y%40%22a+a%22", retrieve);
        } else {
            assertEquals("decoded -> _`'$X Y@\"a a\"", retrieve);
        }
    }

    private HttpRequest<String> form(String path, String body) {
        return HttpRequest.POST(path, body).contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
    }

    private HttpRequest<MultipartBody> multipart(String path, MultipartBody body) {
        return HttpRequest.POST(path, body).contentType(MediaType.MULTIPART_FORM_DATA_TYPE);
    }

    private String methodCase(String path) {
        String[] split = path.substring(1).split("-");
        if (split.length == 1) {
            return split[0];
        }
        String title = Pattern.compile("^.").matcher(split[1]).replaceFirst(m -> m.group().toUpperCase());
        return split[0] + title;
    }

    @Requires(property = "spec.name", value = "FormParamTest")
    @Path("/form-param")
    static class TestController {

        private final JaxRsResourceInfo resourceInfo;

        TestController(JaxRsResourceInfo resourceInfo) {
            this.resourceInfo = resourceInfo;
        }

        @POST
        @Path("/string-array")
        @Produces("text/plain")
        public String stringArray(@FormParam("a") String[] values) {
            return content(Arrays.stream(values));
        }

        @POST
        @Path("/string-list")
        @Produces("text/plain")
        public String stringList(@FormParam("a") List<String> values) {
            return content(values.stream());
        }

        @POST
        @Path("/long-array")
        @Produces("text/plain")
        public String longArray(@FormParam("a") Long[] values) {
            return content(Arrays.stream(values).map(String::valueOf));
        }

        @POST
        @Path("/long-list")
        @Produces("text/plain")
        public String longList(@FormParam("a") List<Long> values) {
            return content(values.stream().map(String::valueOf));
        }

        @POST
        @Path("/entity-part")
        @Produces("text/plain")
        public String entityPart(@FormParam("a") EntityPart part) throws IOException {
            return content(Stream.of(describe(part)));
        }

        @POST
        @Path("/entity-part-list")
        @Produces("text/plain")
        public String entityPartList(@FormParam("a") List<EntityPart> parts) throws IOException {
            return content(describe(parts).stream());
        }

        @POST
        @Path("/entity-parts")
        @Produces("text/plain")
        public String entityParts(List<EntityPart> parts) throws IOException {
            return content(describe(parts).stream());
        }

        @POST
        @Path("/bytes")
        @Produces("text/plain")
        public String bytes(@FormParam("a") byte[] value) {
            return content(Stream.of(new String(value, StandardCharsets.UTF_8)));
        }

        @POST
        @Path("/stream")
        @Produces("text/plain")
        public String stream(@FormParam("a") InputStream value) throws IOException {
            return content(Stream.of(new String(value.readAllBytes(), StandardCharsets.UTF_8)));
        }

        @POST
        @Path("/default")
        @Produces("text/plain")
        public String defaultValue(@DefaultValue("default") @FormParam("a") String value) {
            return content(Stream.of(value));
        }

        @POST
        @Path("/missing")
        @Produces("text/plain")
        public String missingValue(@DefaultValue("default") @FormParam("a") String value) {
            return content(Stream.of(value));
        }

        @POST
        @Path("/decoded")
        @Produces("text/plain")
        public String decoded(@FormParam("a") String value) {
            return content(Stream.of(value));
        }

        @POST
        @Path("/encoded")
        @Produces("text/plain")
        public String encoded(@Encoded @FormParam("a") String value) {
            return content(Stream.of(value));
        }

        private String content(Stream<String> body) {
            return resourceInfo.getResourceMethod().getName() + " -> " + body.collect(joining(","));
        }

        private List<String> describe(List<EntityPart> parts) throws IOException {
            List<String> descriptions = new ArrayList<>(parts.size());
            for (EntityPart part : parts) {
                descriptions.add(describe(part));
            }
            return descriptions;
        }

        private String describe(EntityPart part) throws IOException {
            return part.getName() + ":" + part.getFileName().orElse("-") + ":" + part.getMediaType() + ":" + part.getContent(String.class);
        }
    }
}
