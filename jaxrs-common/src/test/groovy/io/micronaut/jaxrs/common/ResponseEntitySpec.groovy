package io.micronaut.jaxrs.common

import io.micronaut.buffer.netty.NettyByteBufferFactory
import io.micronaut.http.HttpResponse
import jakarta.ws.rs.core.Response
import spock.lang.Specification

class ResponseEntitySpec extends Specification {

    private static JaxRsMutableResponse received(String body) {
        new JaxRsMutableResponse(HttpResponse.ok(NettyByteBufferFactory.DEFAULT.wrap(body.bytes)))
    }

    void "readEntity of an input stream does not close the stream nor the response"() {
        given:
        boolean closed = false
        def stream = new ByteArrayInputStream("abc".bytes) {
            @Override
            void close() throws IOException {
                closed = true
            }
        }
        def response = received("ignored")
        response.setEntityStream(stream)

        when:
        InputStream read = response.readEntity(InputStream)

        then:
        read.is(stream)
        !closed
        read.text == "abc"

        when: "the stream was consumed"
        response.getEntity()

        then:
        thrown(IllegalStateException)

        when:
        response.close()

        then:
        closed
    }

    void "readEntity of another type closes the entity stream"() {
        given:
        boolean closed = false
        def stream = new ByteArrayInputStream("abc".bytes) {
            @Override
            void close() throws IOException {
                closed = true
            }
        }
        def response = received("ignored")
        response.setEntityStream(stream)

        expect: "the entity read is cached for getEntity"
        response.readEntity(String) == "abc"
        closed
        response.getEntity() == "abc"
    }

    void "getEntity returns the entity read last from a buffered entity"() {
        given:
        def response = received("abc")

        expect:
        response.bufferEntity()
        response.readEntity(String) == "abc"
        response.getEntity() == "abc"
        response.readEntity(byte[]) == "abc".bytes
        response.getEntity() == "abc".bytes
    }

    void "a buffered entity cannot be read after close"() {
        given:
        def response = received("abc")

        expect:
        response.bufferEntity()
        response.readEntity(String) == "abc"
        response.readEntity(String) == "abc"

        when:
        response.close()
        response.readEntity(String)

        then:
        thrown(IllegalStateException)

        when:
        response.getEntity()

        then:
        thrown(IllegalStateException)
    }

    void "bufferEntity buffers the entity stream a filter set"() {
        given:
        def response = received("original")
        response.setEntityStream(new ByteArrayInputStream("filtered".bytes))

        expect:
        response.bufferEntity()
        response.readEntity(String) == "filtered"
        response.readEntity(byte[]) == "filtered".bytes
    }

    void "the entity of a received response is an input stream"() {
        given:
        def response = received("abc")

        when:
        def entity = response.getEntity()

        then:
        entity instanceof InputStream

        and: "it is the entity stream the entity is read from"
        response.readEntity(String) == "abc"
    }

    void "an empty entity is read as a zero-length value"() {
        expect:
        received("").readEntity(String) == ""
        Response.noContent().build().readEntity(String) == ""
        Response.noContent().build().readEntity(byte[]) == new byte[0]
    }

    void "an empty entity read again is the same zero-length value"() {
        given:
        def response = received("")

        expect:
        response.readEntity(String) == ""
        response.readEntity(String) == ""
    }

    void "an empty entity of an application type is not read as null when read again"() {
        given:
        def response = received("")

        when:
        response.readEntity(URI)

        then:
        thrown(jakarta.ws.rs.ProcessingException)

        when:
        response.readEntity(URI)

        then:
        thrown(IllegalStateException)
    }
}
