package io.micronaut.jaxrs.providers;

import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JAXB provider of the client uses the context resolvers registered with the client (JAX-RS 4.2.4, 4.3).
 */
@MicronautTest
class JaxbClientContextResolverTest {

    @Inject
    EmbeddedServer server;

    @Test
    void theClientJaxbProviderUsesARegisteredContextResolver() {
        JaxbContextResolver resolver = new JaxbContextResolver();
        try (Client client = ClientBuilder.newClient().register(resolver)) {
            StandardProvidersTest.Item item = new StandardProvidersTest.Item();
            item.name = "one";
            StandardProvidersTest.Item echoed = client.target(server.getURI()).path("providers").path("jaxb")
                .request("application/xml")
                .post(Entity.xml(item), StandardProvidersTest.Item.class);

            assertEquals("one!", echoed.name);
        }
        // asked to write the request entity and to read the response entity
        assertTrue(resolver.types.size() >= 2, resolver.types.toString());
        assertTrue(resolver.types.stream().allMatch(t -> t == StandardProvidersTest.Item.class), resolver.types.toString());
    }

    public static final class JaxbContextResolver implements ContextResolver<JAXBContext> {
        final List<Class<?>> types = new CopyOnWriteArrayList<>();

        @Override
        public JAXBContext getContext(Class<?> type) {
            types.add(type);
            try {
                return JAXBContext.newInstance(type);
            } catch (JAXBException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
