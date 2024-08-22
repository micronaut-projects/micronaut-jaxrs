package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        String retrieve = client.toBlocking().retrieve(HttpRequest.GET(path + "?a=1&a=10&a=11"), String.class);
        assertEquals(methodCase(path) + " -> 1,10,11", retrieve);
    }

    private String methodCase(String path) {
        String[] split = path.split("[/-]");
        String title = Pattern.compile("^.").matcher(split[2]).replaceFirst(m -> m.group().toUpperCase());
        return split[1] + title;
    }

    @Requires(property = "spec.name", value = "FormParamTest")
    @Path("/form-param")
    static class TestController {

        private final JaxRsResourceInfo resourceInfo;

        TestController(JaxRsResourceInfo resourceInfo) {
            this.resourceInfo = resourceInfo;
        }

        @GET
        @Path("/string-array")
        @Produces("text/plain")
        public String stringArray(@FormParam("a") String[] values) {
            return content(Arrays.stream(values));
        }

        @GET
        @Path("/string-list")
        @Produces("text/plain")
        public String stringList(@FormParam("a") List<String> values) {
            return content(values.stream());
        }

        @GET
        @Path("/long-array")
        @Produces("text/plain")
        public String longArray(@FormParam("a") Long[] values) {
            return content(Arrays.stream(values).map(String::valueOf));
        }

        @GET
        @Path("/long-list")
        @Produces("text/plain")
        public String longList(@FormParam("a") List<Long> values) {
            return content(values.stream().map(String::valueOf));
        }

        private String content(Stream<String> body) {
            return resourceInfo.getResourceMethod().getName() + " -> " + body.collect(joining(","));
        }
    }
}
