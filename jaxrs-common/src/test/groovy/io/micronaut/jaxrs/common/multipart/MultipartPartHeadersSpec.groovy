package io.micronaut.jaxrs.common.multipart

import jakarta.ws.rs.core.EntityPart
import jakarta.ws.rs.core.GenericType
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedHashMap
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.ext.MessageBodyReader
import jakarta.ws.rs.ext.Providers
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

    private static List<EntityPart> read(String body, String boundary) {
        def type = new GenericType<List<EntityPart>>() {}
        new JaxRsMultipartMessageBodyReader().readFrom((Class) List, type.type, [] as java.lang.annotation.Annotation[],
            MediaType.valueOf("multipart/form-data; boundary=" + boundary), new MultivaluedHashMap<String, String>(),
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
    }

    void "a boundary prefix within the content is not a delimiter"() {
        given:
        def body = "--abc\r\n" +
            "Content-Disposition: form-data; name=\"x\"\r\n" +
            "\r\n" +
            "one\r\n--abcX\r\ntwo\r\n" +
            "--abc \r\n" +
            "Content-Disposition: form-data; name=\"y\"\r\n" +
            "\r\n" +
            "three\r\n" +
            "--abc--\r\n"

        when:
        def parts = read(body, "abc")

        then:
        parts.size() == 2
        parts[0].getContent(String) == "one\r\n--abcX\r\ntwo"
        parts[1].getContent(String) == "three"
    }

    void "a typed read closes the content stream"() {
        given:
        boolean closed = false
        def stream = new ByteArrayInputStream("abc".bytes) {
            @Override
            void close() throws IOException {
                closed = true
            }
        }
        def part = EntityPart.withName("x").content(stream).build()

        expect:
        part.getContent(String) == "abc"
        closed
    }

    void "the headers of a part are immutable"() {
        given:
        def built = EntityPart.withName("x").header("X-Custom", "1").content("v").build()
        def read = read("--b\r\nContent-Disposition: form-data; name=\"x\"\r\n\r\nv\r\n--b--\r\n", "b")[0]

        when:
        built.headers.clear()

        then:
        thrown(UnsupportedOperationException)

        when:
        built.headers.get("X-Custom").add("2")

        then:
        thrown(UnsupportedOperationException)

        when:
        read.headers.add("X-New", "1")

        then:
        thrown(UnsupportedOperationException)
        built.headers.getFirst("X-Custom") == "1"
    }

    void "the media type of a part is its Content-Type header"() {
        when:
        def part = EntityPart.withName("x").mediaType("application/xml").content("v").build()

        then:
        part.mediaType == MediaType.APPLICATION_XML_TYPE
        part.headers.getFirst("Content-Type") == "application/xml"

        when:
        part = EntityPart.withName("x").header("Content-Type", "application/json").content("v").build()

        then:
        part.mediaType == MediaType.APPLICATION_JSON_TYPE
    }
    void "a typed String read of a part uses an application reader"() {
        given:
        def reader = [
            isReadable: { Class t, java.lang.reflect.Type g, java.lang.annotation.Annotation[] a, MediaType m -> t == String },
            readFrom  : { Class t, java.lang.reflect.Type g, java.lang.annotation.Annotation[] a, MediaType m, MultivaluedMap h, InputStream s ->
                "read:" + new String(s.readAllBytes(), StandardCharsets.UTF_8) }
        ] as MessageBodyReader
        def providers = [
            getMessageBodyReader: { Class t, java.lang.reflect.Type g, java.lang.annotation.Annotation[] a, MediaType m -> t == String ? reader : null }
        ] as Providers

        expect:
        JaxRsEntityPart.read("x", null, MediaType.TEXT_PLAIN_TYPE, "abc".bytes, providers).getContent(String) == "read:abc"
        JaxRsEntityPart.read("x", null, MediaType.TEXT_PLAIN_TYPE, "abc".bytes, providers).getContent(byte[]) == "abc".bytes
    }

    void "a null media type of a built part is rejected"() {
        when:
        EntityPart.withName("x").mediaType((MediaType) null)

        then:
        thrown(IllegalArgumentException)

        when:
        EntityPart.withName("x").mediaType((String) null)

        then:
        thrown(IllegalArgumentException)
    }
}
