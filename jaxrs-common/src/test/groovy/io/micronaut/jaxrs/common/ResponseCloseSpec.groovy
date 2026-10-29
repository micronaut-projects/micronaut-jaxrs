package io.micronaut.jaxrs.common

import jakarta.ws.rs.core.Response
import spock.lang.Specification

class ResponseCloseSpec extends Specification {

    void "close closes an input stream entity"() {
        given:
        def stream = new TrackingInputStream(new byte[1])

        when:
        Response.ok(stream).build().close()

        then:
        stream.closed
    }
}
