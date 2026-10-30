package io.micronaut.jaxrs.client;

import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.ResponseProcessingException;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lifecycle of the streams and responses of the client on its failure and cancellation paths,
 * and the contracts of its API.
 */
@Property(name = "spec.name", value = "JaxRsClient")
@MicronautTest
class JaxRsClientLifecycleTest {

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
    void replacedEntityStreamIsClosedWhenTheWriterFails() {
        AtomicBoolean closed = new AtomicBoolean();
        WebTarget target = target("echo")
            .register(new FailingWriter())
            .register((ClientRequestFilter) context -> context.setEntityStream(new FilterOutputStream(context.getEntityStream()) {
                @Override
                public void close() throws IOException {
                    closed.set(true);
                    super.close();
                }
            }));

        ProcessingException e = assertThrows(ProcessingException.class,
            () -> target.request().post(Entity.entity(new Thing(), MediaType.TEXT_PLAIN_TYPE), String.class));
        assertInstanceOf(IOException.class, e.getCause());
        assertTrue(closed.get());
    }

    @Test
    void failureOfAResponseFilterCarriesTheFilteredResponse() {
        AtomicBoolean closed = new AtomicBoolean();
        WebTarget target = target("hello")
            .register((ClientResponseFilter) (request, response) -> {
                response.setEntityStream(new FilterInputStream(response.getEntityStream()) {
                    @Override
                    public void close() throws IOException {
                        closed.set(true);
                        super.close();
                    }
                });
                throw new IOException("filter failed");
            });

        ResponseProcessingException e = assertThrows(ResponseProcessingException.class, () -> target.request().get(String.class));
        e.getResponse().close();
        assertTrue(closed.get());
    }

    static final class Thing {
    }

    static final class FailingWriter implements MessageBodyWriter<Thing> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Thing.class;
        }

        @Override
        public void writeTo(Thing thing, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            throw new IOException("writer failed");
        }
    }
}
