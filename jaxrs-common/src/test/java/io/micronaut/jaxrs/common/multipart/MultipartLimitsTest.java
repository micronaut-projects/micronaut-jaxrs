package io.micronaut.jaxrs.common.multipart;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The reader of a {@code List<EntityPart>} holds every part in memory: the number of parts and
 * the size of their headers are limited, an entity over the limits is answered with 413.
 */
class MultipartLimitsTest {

    private static final int MAX_PARTS = JaxRsMultipartMessageBodyReader.LIMITS.maxParts();

    @Test
    void partsWithinTheLimit() throws IOException {
        assertEquals(MAX_PARTS, read(parts(MAX_PARTS, "")).size());
    }

    @Test
    void tooManyParts() {
        WebApplicationException e = assertThrows(WebApplicationException.class, () -> read(parts(MAX_PARTS + 1, "")));
        assertEquals(413, e.getResponse().getStatus());
    }

    @Test
    void tooLargeHeaders() {
        String header = "X-Large: " + "a".repeat(JaxRsMultipartMessageBodyReader.LIMITS.maxHeaderSize()) + "\r\n";
        WebApplicationException e = assertThrows(WebApplicationException.class, () -> read(parts(1, header)));
        assertEquals(413, e.getResponse().getStatus());
    }

    private static String parts(int count, String header) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < count; i++) {
            body.append("--b\r\nContent-Disposition: form-data; name=\"p").append(i).append("\"\r\n").append(header).append("\r\nv\r\n");
        }
        return body.append("--b--\r\n").toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<EntityPart> read(String body) throws IOException {
        return new JaxRsMultipartMessageBodyReader().readFrom((Class) List.class, new GenericType<List<EntityPart>>() { }.getType(),
            new Annotation[0], MediaType.valueOf("multipart/form-data; boundary=b"), new MultivaluedHashMap<>(),
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }
}
