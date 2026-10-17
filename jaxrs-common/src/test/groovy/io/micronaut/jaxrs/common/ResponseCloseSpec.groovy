package io.micronaut.jaxrs.common

import jakarta.ws.rs.core.Response
import spock.lang.Specification

class ResponseCloseSpec extends Specification {

    void "close closes an input stream entity"() {
        given:
        boolean closed = false
        def stream = new ByteArrayInputStream(new byte[1]) {
            @Override
            void close() throws IOException {
                closed = true
            }
        }

        when:
        Response.ok(stream).build().close()

        then:
        closed
    }
}
