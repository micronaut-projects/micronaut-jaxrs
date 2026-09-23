package io.micronaut.jaxrs.providers;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.bind.config.PropertyNamingStrategy;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * JSON is read and written by JSON-B when an implementation is present (JAX-RS 4.2.4), with the
 * {@code Jsonb} of a context resolver of the application or of the client.
 */
@MicronautTest
@Property(name = "spec.name", value = "JsonbProviderTest")
class JsonbProviderTest {

    @Inject
    EmbeddedServer server;

    @Test
    void serverUsesTheJsonbOfTheApplication() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.getURL() + "/jsonb/echo"))
            .header("Content-Type", MediaType.APPLICATION_JSON)
            .header("Accept", MediaType.APPLICATION_JSON)
            .POST(HttpRequest.BodyPublishers.ofString("{\"first_name\":\"Joe\"}"))
            .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            // the resolver names the properties in snake case
            assertEquals("{\"first_name\":\"Joe echoed\"}", response.body());
        }
    }

    @Test
    void clientUsesItsJsonb() {
        try (Client client = ClientBuilder.newClient().register(new SnakeCaseResolver())) {
            Person person = new Person();
            person.setFirstName("Ann");
            Person echoed = client.target(server.getURI()).path("jsonb/echo")
                .request(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.json(person), Person.class);
            assertEquals("Ann echoed", echoed.getFirstName());
        }
    }

    public static class Person {
        private String firstName;

        public String getFirstName() {
            return firstName;
        }

        public void setFirstName(String firstName) {
            this.firstName = firstName;
        }
    }

    @Requires(property = "spec.name", value = "JsonbProviderTest")
    @Provider
    public static class SnakeCaseResolver implements ContextResolver<Jsonb> {
        @Override
        public Jsonb getContext(Class<?> type) {
            return type == Person.class
                ? JsonbBuilder.create(new JsonbConfig().withPropertyNamingStrategy(PropertyNamingStrategy.LOWER_CASE_WITH_UNDERSCORES))
                : null;
        }
    }

    @Requires(property = "spec.name", value = "JsonbProviderTest")
    @Path("/jsonb")
    public static class JsonbResource {
        @POST
        @Path("echo")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.APPLICATION_JSON)
        public Person echo(Person person) {
            person.setFirstName(person.getFirstName() + " echoed");
            return person;
        }
    }
}
