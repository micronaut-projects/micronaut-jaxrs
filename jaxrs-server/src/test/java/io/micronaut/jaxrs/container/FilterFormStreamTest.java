package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The form parameters are read from the entity stream a request filter set.
 */
@MicronautTest
@Property(name = "spec.name", value = "FilterFormStreamTest")
class FilterFormStreamTest {

    @Inject
    @Client("/api/filter-form-stream")
    HttpClient client;

    @ParameterizedTest
    @CsvSource({
        "/form,new",
        "/async,new",
        "/encoded,new",
        "/entity,new new",
    })
    void formParamsReadTheReplacedEntity(String path, String expected) {
        String result = client.toBlocking().retrieve(HttpRequest.POST(path, "x=old")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE));
        assertEquals(expected, result);
    }

    @Requires(property = "spec.name", value = "FilterFormStreamTest")
    @Provider
    public static class ReplacingFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            if (requestContext.getUriInfo().getPath().startsWith("filter-form-stream")) {
                requestContext.setEntityStream(new ByteArrayInputStream("x=new".getBytes(StandardCharsets.UTF_8)));
            }
        }
    }

    @Requires(property = "spec.name", value = "FilterFormStreamTest")
    @Path("/filter-form-stream")
    @Consumes(jakarta.ws.rs.core.MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(jakarta.ws.rs.core.MediaType.TEXT_PLAIN)
    public static class FormResource {
        @POST
        @Path("/form")
        public String form(@FormParam("x") String x) {
            return x;
        }

        @POST
        @Path("/async")
        public CompletionStage<String> async(@FormParam("x") String x) {
            return CompletableFuture.completedFuture(x);
        }

        @POST
        @Path("/encoded")
        public String encoded(@Encoded @FormParam("x") String x) {
            return x;
        }

        @POST
        @Path("/entity")
        public String entity(@FormParam("x") String x, MultivaluedMap<String, String> form) {
            return x + " " + form.getFirst("x");
        }
    }
}
