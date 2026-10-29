package io.micronaut.jaxrs.providers;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.NoContentException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.annotation.Annotation;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An empty entity is no content for the JAXB provider (JAX-RS 4.2.4).
 */
class JaxbEmptyEntityTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void emptyEntityIsNoContent() {
        JaxRsJaxbMessageBodyReaderWriter provider = new JaxRsJaxbMessageBodyReaderWriter();
        assertThrows(NoContentException.class, () -> provider.readFrom((Class) StandardProvidersTest.Item.class, StandardProvidersTest.Item.class,
            new Annotation[0], MediaType.APPLICATION_XML_TYPE, new MultivaluedHashMap<>(), new ByteArrayInputStream(new byte[0])));
    }
}
