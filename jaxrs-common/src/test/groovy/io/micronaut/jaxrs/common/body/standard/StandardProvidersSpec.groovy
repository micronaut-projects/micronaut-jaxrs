package io.micronaut.jaxrs.common.body.standard

import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedHashMap
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.core.NoContentException
import spock.lang.Specification

import java.lang.annotation.Annotation

class StandardProvidersSpec extends Specification {

    static final Annotation[] NONE = new Annotation[0]

    void "a short is read as a short"() {
        expect:
        new JaxRsNumberMessageBodyReaderWriter<Short>().readFrom(Short, Short, NONE, MediaType.TEXT_PLAIN_TYPE, null,
                new ByteArrayInputStream("12".bytes)) == (short) 12
    }

    void "a character is written in the charset"() {
        given:
        def out = new ByteArrayOutputStream()

        when:
        new JaxRsCharacterMessageBodyReaderWriter().writeTo('é' as Character, Character, Character, NONE,
                MediaType.TEXT_PLAIN_TYPE.withCharset("UTF-8"), null, out)

        then:
        new String(out.toByteArray(), "UTF-8") == "é"
    }

    void "an empty boolean is no content"() {
        when:
        new JaxRsBooleanMessageBodyReaderWriter().readFrom(Boolean, Boolean, NONE, MediaType.TEXT_PLAIN_TYPE, null,
                new ByteArrayInputStream(new byte[0]))

        then:
        def e = thrown(Exception)
        e instanceof NoContentException || e.cause instanceof NoContentException
    }

    void "a form of objects is written"() {
        given:
        def map = new MultivaluedHashMap<String, Object>()
        map.add("a", 1)
        map.add("b", "x y")
        def out = new ByteArrayOutputStream()

        when:
        new JaxRsMultivaluedMapMessageBodyWriter().writeTo((MultivaluedHashMap) map, MultivaluedHashMap, MultivaluedHashMap, NONE,
                MediaType.APPLICATION_FORM_URLENCODED_TYPE, null, out)

        then:
        out.toString("UTF-8") == "a=1&b=x%20y"
    }

    void "the stream of a failed write is closed"() {
        given:
        boolean closed = false
        def input = new ByteArrayInputStream("abc".bytes) {
            @Override
            void close() {
                closed = true
            }
        }
        def out = new OutputStream() {
            @Override
            void write(int b) {
                throw new IOException("broken")
            }
        }

        when:
        new JaxRsInputStreamMessageBodyWriter<InputStream>().writeTo(input, InputStream, InputStream, NONE,
                MediaType.APPLICATION_OCTET_STREAM_TYPE, null, out)

        then:
        thrown(IOException)
        closed
    }

    void "a malformed form is a bad request"() {
        when:
        reader.readFrom(MultivaluedMap, MultivaluedMap, NONE, MediaType.APPLICATION_FORM_URLENCODED_TYPE, null,
                new ByteArrayInputStream("x=%ZZ".bytes))

        then:
        thrown(jakarta.ws.rs.BadRequestException)

        where:
        reader << [new JaxRsMultivaluedStringStringMapMessageBodyReader(), new JaxRsMultivaluedStringObjectMapMessageBodyReader()]
    }

    void "a malformed number is a bad request"() {
        when:
        new JaxRsNumberMessageBodyReaderWriter<Integer>().readFrom(Integer, Integer, NONE, MediaType.TEXT_PLAIN_TYPE, null,
                new ByteArrayInputStream("abc".bytes))

        then:
        thrown(jakarta.ws.rs.BadRequestException)
    }

    void "an unsupported charset of a text entity is a bad request"() {
        when:
        reader.readFrom(type, type, NONE, MediaType.valueOf(mediaType), null, new ByteArrayInputStream("1".bytes))

        then:
        thrown(jakarta.ws.rs.BadRequestException)

        where:
        reader                                           | type           | mediaType
        new JaxRsStringMessageBodyReaderWriter()         | String         | "text/plain;charset=bogus"
        new JaxRsNumberMessageBodyReaderWriter<Integer>() | Integer        | "text/plain;charset=bogus"
        new JaxRsCharacterMessageBodyReaderWriter()      | Character      | "text/plain;charset=bogus"
        new JaxRsBooleanMessageBodyReaderWriter()        | Boolean        | "text/plain;charset=b@d"
        new JaxRsMultivaluedStringStringMapMessageBodyReader() | MultivaluedMap | "application/x-www-form-urlencoded;charset=bogus"
    }

    void "a form entity is decoded in its charset"() {
        when:
        MultivaluedMap<String, ?> map = reader.readFrom(MultivaluedMap, MultivaluedMap, NONE,
                MediaType.valueOf("application/x-www-form-urlencoded;charset=ISO-8859-1"), null,
                new ByteArrayInputStream("name=café".getBytes("ISO-8859-1")))

        then:
        map.getFirst("name") == "café"

        where:
        reader << [new JaxRsMultivaluedStringStringMapMessageBodyReader(), new JaxRsMultivaluedStringObjectMapMessageBodyReader()]
    }

    void "a Reader is written without closing the entity stream and closed when the write fails"() {
        given:
        boolean outClosed = false
        def out = new ByteArrayOutputStream() {
            @Override
            void close() {
                outClosed = true
            }
        }
        boolean readerClosed = false
        def reader = new StringReader("abc") {
            @Override
            void close() {
                readerClosed = true
            }
        }

        when:
        new JaxRsReaderMessageBodyWriter().writeTo(reader, Reader, Reader, NONE, MediaType.TEXT_PLAIN_TYPE, new MultivaluedHashMap<String, Object>(), out)

        then:
        out.toString("UTF-8") == "abc"
        !outClosed
        readerClosed

        when:
        readerClosed = false
        reader = new StringReader("abc") {
            @Override
            void close() {
                readerClosed = true
            }
        }
        def failing = new OutputStream() {
            @Override
            void write(int b) throws IOException {
                throw new IOException("broken")
            }
        }
        new JaxRsReaderMessageBodyWriter().writeTo(reader, Reader, Reader, NONE, MediaType.TEXT_PLAIN_TYPE, new MultivaluedHashMap<String, Object>(), failing)

        then:
        thrown(IOException)
        readerClosed
    }
}
