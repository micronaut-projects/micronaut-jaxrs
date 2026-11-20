package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Without a validator, here disabled, the constraints of the resources are not validated.
 */
@MicronautTest
@Property(name = "spec.name", value = "BeanValidationDisabledTest")
@Property(name = "micronaut.validator.enabled", value = "false")
class BeanValidationDisabledTest {

    @Inject
    @Client("/api")
    HttpClient client;

    @Test
    void notValidated() {
        assertEquals("q=", client.toBlocking().retrieve(HttpRequest.GET("/unvalidated/param?q=")));
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/unvalidated/return"), String.class);
        assertEquals(204, response.getStatus().getCode());
    }

    @Requires(property = "spec.name", value = "BeanValidationDisabledTest")
    @Path("/unvalidated")
    @Produces("text/plain")
    public static class UnvalidatedResource {

        @GET
        @Path("param")
        public String param(@NotBlank @QueryParam("q") String q) {
            return "q=" + q;
        }

        @GET
        @Path("return")
        public @NotNull String returnValue() {
            return null;
        }
    }
}
