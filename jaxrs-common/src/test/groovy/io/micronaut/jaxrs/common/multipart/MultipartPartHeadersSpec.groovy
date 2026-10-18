package io.micronaut.jaxrs.common.multipart

import jakarta.ws.rs.core.EntityPart
import jakarta.ws.rs.core.GenericType
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedHashMap
import spock.lang.Specification

import java.nio.charset.StandardCharsets

class MultipartPartHeadersSpec extends Specification {

    void "part header names are case-insensitive"() {
        given:
        def body = "--b\r\n" +
            "content-disposition: form-data; name=\"x\"\r\n" +
            "CONTENT-TYPE: text/plain\r\n" +
            "\r\n" +
            "value\r\n" +
            "--b--\r\n"
        def reader = new JaxRsMultipartMessageBodyReader()
        def type = new GenericType<List<EntityPart>>() {}

        when:
        List<EntityPart> parts = reader.readFrom((Class) List, type.type, [] as java.lang.annotation.Annotation[],
            MediaType.valueOf("multipart/form-data; boundary=b"), new MultivaluedHashMap<String, String>(),
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))

        then:
        parts.size() == 1
        parts[0].name == "x"
        parts[0].mediaType == MediaType.TEXT_PLAIN_TYPE
        parts[0].headers.getFirst("Content-Disposition") == "form-data; name=\"x\""
        parts[0].getContent(String) == "value"
    }

    void "the headers of a built part are case-insensitive"() {
        when:
        def part = EntityPart.withName("x").header("X-Custom", "1").content("v").build()

        then:
        part.headers.getFirst("x-custom") == "1"
    }
}
