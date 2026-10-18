package io.micronaut.jaxrs.client;

import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.NoContentException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Property(name = "spec.name", value = "JaxRsClient")
@MicronautTest
class JaxRsClientStateTest {

    @Inject
    EmbeddedServer server;

    Client client;

    @BeforeEach
    void setUp() {
        client = ClientBuilder.newClient();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    private WebTarget target(String path) {
        return client.target(server.getURI()).path("client").path(path);
    }

    @Test
    void changingTheUriBuilderOfATargetDoesNotChangeTheTarget() {
        WebTarget target = target("hello");
        target.getUriBuilder().path("extra").queryParam("q", "v");
        assertEquals(server.getURI().resolve("/client/hello"), target.getUri());
        assertEquals("hello", target.request().get(String.class));
    }

    @Test
    void aBuiltInvocationKeepsTheHeadersOfItsBuild() {
        Invocation.Builder builder = target("header/Authorization").request();
        builder.header("Authorization", "A");
        Invocation invocation = builder.buildGet();
        builder.header("Authorization", null).header("Authorization", "B");
        assertEquals("A", invocation.invoke(String.class));
        assertEquals("B", builder.get(String.class));
    }

    @Test
    void anEmptyEntityCannotBeReadAsAnApplicationType() {
        try (Response response = target("empty").request().get()) {
            assertEquals(204, response.getStatus());
            ProcessingException e = assertThrows(ProcessingException.class, () -> response.readEntity(Pojo.class));
            assertInstanceOf(NoContentException.class, e.getCause());
        }
    }

    @Test
    void anEmptyEntityIsAnEmptyString() {
        try (Response response = target("empty").request().get()) {
            assertEquals("", response.readEntity(String.class));
        }
    }

    @Serdeable
    record Pojo(String name) {
    }
}
