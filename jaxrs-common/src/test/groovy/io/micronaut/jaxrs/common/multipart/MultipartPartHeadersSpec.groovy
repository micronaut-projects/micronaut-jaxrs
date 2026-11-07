package io.micronaut.jaxrs.common.multipart

import io.micronaut.jaxrs.common.TrackingInputStream
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

    void "folded part headers are unfolded and the default type is the one of the headers"() {
        given:
        def body = "--b\r\n" +
            "Content-Disposition: form-data;\r\n name=\"x\"\r\n" +
            "\r\n" +
            "value\r\n" +
            "--b--\r\n"
        def type = new GenericType<List<EntityPart>>() {}

        when:
        List<EntityPart> parts = new JaxRsMultipartMessageBodyReader().readFrom((Class) List, type.type, [] as java.lang.annotation.Annotation[],
            MediaType.valueOf("multipart/form-data; boundary=b"), new MultivaluedHashMap<String, String>(),
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))

        then:
        parts[0].name == "x"
        parts[0].mediaType == MediaType.TEXT_PLAIN_TYPE
        parts[0].headers.getFirst("Content-Type") == "text/plain"
        parts[0].getContent(String) == "value"
    }

    void "a failed write closes the streams of the parts that are not written"() {
        given:
        def failing = new InputStream() {
            boolean closed

            @Override
            int read() throws IOException {
                throw new IOException("broken")
            }

            @Override
            void close() {
                closed = true
            }
        }
        def second = new TrackingInputStream("b".bytes)
        def parts = [
            EntityPart.withName("a").content(failing).mediaType(MediaType.APPLICATION_OCTET_STREAM_TYPE).build(),
            EntityPart.withName("b").content(second).mediaType(MediaType.APPLICATION_OCTET_STREAM_TYPE).build()
        ]
        def writer = new JaxRsMultipartMessageBodyWriter()

        when:
        writer.writeTo(parts, List, new GenericType<List<EntityPart>>() {}.type, [] as java.lang.annotation.Annotation[],
            MediaType.valueOf("multipart/form-data; boundary=b"), new MultivaluedHashMap<String, Object>(), new ByteArrayOutputStream())

        then:
        def e = thrown(IOException)
        e.message == "broken"
        failing.closed
        second.closed
    }

    void "a failed write closes the streams of the parts of another implementation that are not written"() {
        given:
        def external = new TrackingInputStream("b".bytes)
        def foreign = [
            getName     : { "b" },
            getContent  : { external },
            getFileName : { Optional.empty() },
            getMediaType: { MediaType.APPLICATION_OCTET_STREAM_TYPE },
            getHeaders  : { new MultivaluedHashMap<String, String>() }
        ] as EntityPart
        def parts = [
            EntityPart.withName("a").content(new InputStream() {
                @Override
                int read() throws IOException {
                    throw new IOException("broken")
                }
            }).mediaType(MediaType.APPLICATION_OCTET_STREAM_TYPE).build(),
            foreign
        ]

        when:
        new JaxRsMultipartMessageBodyWriter().writeTo(parts, List, new GenericType<List<EntityPart>>() {}.type, [] as java.lang.annotation.Annotation[],
            MediaType.valueOf("multipart/form-data; boundary=b"), new MultivaluedHashMap<String, Object>(), new ByteArrayOutputStream())

        then:
        def e = thrown(IOException)
        e.message == "broken"
        external.closed
    }

    void "the headers of a built part are case-insensitive"() {
        when:
        def part = EntityPart.withName("x").header("X-Custom", "1").content("v").build()

        then:
        part.headers.getFirst("x-custom") == "1"
    }

    void "headers replaces only the given header names"() {
        given:
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>()
        headers.add("Y", "2")
        headers.add("x-other", "3")

        when:
        def part = EntityPart.withName("x").header("X", "1").header("X-Other", "0").headers(headers).content("v").build()

        then:
        part.headers.getFirst("X") == "1"
        part.headers.getFirst("Y") == "2"
        part.headers.get("X-Other") == ["3"]
    }

    void "a Content-Type set with headers is the media type of the part"() {
        given:
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>()
        headers.add("content-type", "text/html")

        when:
        def part = EntityPart.withName("x").mediaType(MediaType.APPLICATION_JSON_TYPE).headers(headers).content("v").build()

        then:
        part.mediaType == MediaType.TEXT_HTML_TYPE
        part.headers.get("Content-Type") == ["text/html"]
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
        def stream = new TrackingInputStream("abc".bytes)
        def part = EntityPart.withName("x").content(stream).build()

        expect:
        part.getContent(String) == "abc"
        stream.closed
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

    void "the content of a built part is written by the writer the providers select"() {
        given:
        def writer = [
            isWriteable: { Class t, java.lang.reflect.Type g, java.lang.annotation.Annotation[] a, MediaType m -> true },
            writeTo    : { Object o, Class t, java.lang.reflect.Type g, java.lang.annotation.Annotation[] a, MediaType m, MultivaluedMap h, OutputStream out ->
                out.write(("CUSTOM:" + (o instanceof byte[] ? new String((byte[]) o, StandardCharsets.UTF_8) : o)).getBytes(StandardCharsets.UTF_8)) }
        ] as jakarta.ws.rs.ext.MessageBodyWriter
        def providers = [
            getMessageBodyWriter: { Class t, java.lang.reflect.Type g, java.lang.annotation.Annotation[] a, MediaType m -> writer }
        ] as Providers
        def none = [
            getMessageBodyWriter: { Class t, java.lang.reflect.Type g, java.lang.annotation.Annotation[] a, MediaType m -> null }
        ] as Providers

        expect:
        written(EntityPart.withName("x").content("hello").build(), providers) == "CUSTOM:hello"
        written(EntityPart.withName("x").content("hello".bytes).build(), providers) == "CUSTOM:hello"
        written(EntityPart.withName("x").content("hello").build(), none) == "hello"
        written(EntityPart.withName("x").content("hello".bytes).build(), none) == "hello"
        written(EntityPart.withName("x").content("hello").build(), null) == "hello"
    }

    private static String written(EntityPart part, Providers providers) {
        def out = new ByteArrayOutputStream()
        ((JaxRsEntityPart) part).write(out, providers)
        return out.toString(StandardCharsets.UTF_8)
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

    void "Content-Disposition parameters are quoted-strings and a filename cannot supply the name"() {
        when:
        def parts = read("--B\r\nContent-Disposition: form-data; filename=\"x; name=evil\"; name=\"real\"\r\n\r\nv\r\n" +
            "--B\r\nContent-Disposition: form-data; name=\"a;b\"; filename=\"c\\\"d\\\\e\"\r\n\r\nw\r\n--B--\r\n", "B")

        then:
        parts[0].name == "real"
        parts[0].fileName.orElse(null) == "x; name=evil"
        parts[1].name == "a;b"
        parts[1].fileName.orElse(null) == "c\"d\\e"
    }

    void "quotes, backslashes and semicolons in a name or filename survive writing and reading"() {
        given:
        def out = new ByteArrayOutputStream()
        def type = new GenericType<List<EntityPart>>() {}
        def written = [EntityPart.withName("a;b\"c\\").fileName("f\\\"; name=evil").content("v").build()]

        when:
        new JaxRsMultipartMessageBodyWriter().writeTo(written, List, type.type, [] as java.lang.annotation.Annotation[],
            MediaType.valueOf("multipart/form-data; boundary=B"), new MultivaluedHashMap<String, Object>(), out)
        def parts = read(out.toString(StandardCharsets.UTF_8), "B")

        then:
        parts[0].name == "a;b\"c\\"
        parts[0].fileName.orElse(null) == "f\\\"; name=evil"
    }

    void "the headers a part writer sets are the headers of the part"() {
        given:
        def writer = [
            isWriteable: { Class t, java.lang.reflect.Type g, java.lang.annotation.Annotation[] a, MediaType m -> true },
            writeTo    : { Object o, Class t, java.lang.reflect.Type g, java.lang.annotation.Annotation[] a, MediaType m, MultivaluedMap h, OutputStream out ->
                h.putSingle("Content-Type", "text/plain;charset=ISO-8859-1")
                h.putSingle("X-Written", "1")
                out.write(((String) o).getBytes(StandardCharsets.ISO_8859_1)) }
        ] as jakarta.ws.rs.ext.MessageBodyWriter
        def providers = [
            getMessageBodyWriter: { Class t, java.lang.reflect.Type g, java.lang.annotation.Annotation[] a, MediaType m -> writer }
        ] as Providers
        def multipartWriter = new JaxRsMultipartMessageBodyWriter()
        multipartWriter.providers = providers
        def out = new ByteArrayOutputStream()
        def type = new GenericType<List<EntityPart>>() {}

        when:
        multipartWriter.writeTo([EntityPart.withName("x").mediaType(MediaType.TEXT_PLAIN_TYPE).content("caf\u00e9").build()], List, type.type,
            [] as java.lang.annotation.Annotation[], MediaType.valueOf("multipart/form-data; boundary=B"), new MultivaluedHashMap<String, Object>(), out)
        def parts = read(out.toString(StandardCharsets.ISO_8859_1), "B")

        then:
        parts[0].mediaType == MediaType.valueOf("text/plain;charset=ISO-8859-1")
        parts[0].headers.getFirst("X-Written") == "1"
    }

    void "a delimiter within the preamble that does not start a line is not one"() {
        given:
        def body = "text--b\r\nContent-Disposition: form-data; name=\"fake\"\r\n\r\nwrong\r\n" +
            "--b\r\nContent-Disposition: form-data; name=\"real\"\r\n\r\nright\r\n--b--\r\n"

        when:
        List<EntityPart> parts = new JaxRsMultipartMessageBodyReader().readFrom((Class) List, new GenericType<List<EntityPart>>() {}.type,
            [] as java.lang.annotation.Annotation[], MediaType.valueOf("multipart/form-data; boundary=b"),
            new MultivaluedHashMap<String, String>(), new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))

        then:
        parts*.name == ["real"]
        parts[0].getContent(String) == "right"
    }

    void "the default media type of a built part is its Content-Type header"() {
        when:
        def field = EntityPart.withName("x").content("a").build()
        def file = EntityPart.withName("f").fileName("f.bin").content("a").build()

        then:
        field.mediaType == MediaType.TEXT_PLAIN_TYPE
        field.headers.getFirst("Content-Type") == "text/plain"
        file.headers.getFirst("Content-Type") == "application/octet-stream"
    }

    void "a line break in a part header is rejected"() {
        given:
        def type = new GenericType<List<EntityPart>>() {}

        when:
        new JaxRsMultipartMessageBodyWriter().writeTo([EntityPart.withName("x").header(name, value).content("v").build()], List, type.type,
            [] as java.lang.annotation.Annotation[], MediaType.valueOf("multipart/form-data; boundary=B"),
            new MultivaluedHashMap<String, Object>(), new ByteArrayOutputStream())

        then:
        thrown(IllegalArgumentException)

        where:
        name           | value
        "X-Label"      | "safe\r\nX-Injected: yes"
        "X-Label"      | "safe\nX-Injected: yes"
        "X-Bad\r\nX-I" | "v"
        "X Bad"        | "v"
    }

    void "a header a part writer sets with a line break is rejected"() {
        given:
        def writer = [
            isWriteable: { Class t, java.lang.reflect.Type g, java.lang.annotation.Annotation[] a, MediaType m -> true },
            writeTo    : { Object o, Class t, java.lang.reflect.Type g, java.lang.annotation.Annotation[] a, MediaType m, MultivaluedMap h, OutputStream out ->
                h.putSingle("X-Written", "1\r\nX-Injected: yes")
                out.write(((String) o).bytes) }
        ] as jakarta.ws.rs.ext.MessageBodyWriter
        def multipartWriter = new JaxRsMultipartMessageBodyWriter()
        multipartWriter.providers = [
            getMessageBodyWriter: { Class t, java.lang.reflect.Type g, java.lang.annotation.Annotation[] a, MediaType m -> writer }
        ] as Providers

        when:
        multipartWriter.writeTo([EntityPart.withName("x").content("v").build()], List, new GenericType<List<EntityPart>>() {}.type,
            [] as java.lang.annotation.Annotation[], MediaType.valueOf("multipart/form-data; boundary=B"),
            new MultivaluedHashMap<String, Object>(), new ByteArrayOutputStream())

        then:
        thrown(IllegalArgumentException)
    }

    void "the content of a part is written to the stream as its writer writes it"() {
        given:
        def chunks = []
        def writer = [
            isWriteable: { Class t, java.lang.reflect.Type g, java.lang.annotation.Annotation[] a, MediaType m -> true },
            writeTo    : { Object o, Class t, java.lang.reflect.Type g, java.lang.annotation.Annotation[] a, MediaType m, MultivaluedMap h, OutputStream out ->
                out.write("first".bytes)
                out.flush()
                chunks << "written"
                out.write("second".bytes) }
        ] as jakarta.ws.rs.ext.MessageBodyWriter
        def providers = [
            getMessageBodyWriter: { Class t, java.lang.reflect.Type g, java.lang.annotation.Annotation[] a, MediaType m -> writer }
        ] as Providers
        def out = new ByteArrayOutputStream() {
            @Override
            void flush() {
                chunks << toString(StandardCharsets.UTF_8)
            }
        }

        when:
        ((JaxRsEntityPart) EntityPart.withName("x").content(new Object()).build()).write(out, providers, { h -> out.write("HEAD".bytes) })

        then:
        chunks[0] == "HEADfirst"
        out.toString(StandardCharsets.UTF_8) == "HEADfirstsecond"
    }

    void "a close delimiter followed by other text is not one"() {
        when:
        def parts = read("--b\r\nContent-Disposition: form-data; name=\"x\"\r\n\r\none\r\n--b--NOT_A_BOUNDARY\r\nsuffix\r\n--b--\r\n", "b")

        then:
        parts.size() == 1
        parts[0].getContent(String) == "one\r\n--b--NOT_A_BOUNDARY\r\nsuffix"
        read("--b\r\nContent-Disposition: form-data; name=\"x\"\r\n\r\none\r\n--b--", "b")[0].getContent(String) == "one"
        read("--b\r\nContent-Disposition: form-data; name=\"x\"\r\n\r\none\r\n--b-- \r\nepilogue", "b")[0].getContent(String) == "one"
    }

    void "an invalid Content-Type of a part is a bad request"() {
        when:
        read("--b\r\nContent-Disposition: form-data; name=\"x\"\r\nContent-Type: invalid\r\n\r\none\r\n--b--\r\n", "b")

        then:
        thrown(jakarta.ws.rs.BadRequestException)
    }

    void "an InputStream read of a part uses an application reader"() {
        given:
        def reader = [
            isReadable: { Class t, java.lang.reflect.Type g, java.lang.annotation.Annotation[] a, MediaType m -> t == InputStream },
            readFrom  : { Class t, java.lang.reflect.Type g, java.lang.annotation.Annotation[] a, MediaType m, MultivaluedMap h, InputStream s ->
                new ByteArrayInputStream(("decrypted:" + new String(s.readAllBytes(), StandardCharsets.UTF_8)).bytes) }
        ] as MessageBodyReader
        def providers = [
            getMessageBodyReader: { Class t, java.lang.reflect.Type g, java.lang.annotation.Annotation[] a, MediaType m -> t == InputStream ? reader : null }
        ] as Providers

        expect:
        new String(JaxRsEntityPart.read("x", null, MediaType.TEXT_PLAIN_TYPE, "abc".bytes, providers).getContent(InputStream).readAllBytes(),
            StandardCharsets.UTF_8) == "decrypted:abc"
        new String(JaxRsEntityPart.read("x", null, MediaType.TEXT_PLAIN_TYPE, "abc".bytes, null).getContent(InputStream).readAllBytes(),
            StandardCharsets.UTF_8) == "abc"
    }

    void "a part whose headers do not end is a bad request"() {
        given:
        def body = "--b\r\n" +
            "Content-Disposition: form-data; name=\"a\"\r\n" +
            "--b\r\n" +
            "Content-Disposition: form-data; name=\"c\"\r\n" +
            "\r\n" +
            "X\r\n" +
            "--b--\r\n"

        when:
        read(body, "b")

        then:
        thrown(jakarta.ws.rs.BadRequestException)
    }

    void "the content of a read part is a range of the entity, not a copy"() {
        given:
        byte[] entity = "xxabcxx".bytes
        def part = JaxRsEntityPart.read("x", null, MediaType.TEXT_PLAIN_TYPE, JaxRsEntityPart.headers(), entity, 2, 3, null)

        when:
        entity[2] = (byte) 'A'

        then:
        part.getContent(String) == "Abc"
    }
}
