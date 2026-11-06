package io.micronaut.jaxrs.common

import io.micronaut.core.type.Argument
import io.micronaut.http.simple.SimpleHttpHeaders
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import spock.lang.Specification

class WriterInterceptorContextSpec extends Specification {

    void "setMediaType updates the Content-Type header"() {
        given:
        def headers = new SimpleHttpHeaders()
        headers.add(HttpHeaders.CONTENT_TYPE, "text/plain")
        def state = new JaxRsWriterInterceptorContextState.ClassicState(headers, "x", new ByteArrayOutputStream())
        def context = new JaxRsWriterInterceptorContext(Collections.emptyIterator(), {}, Argument.STRING,
                MediaType.TEXT_PLAIN_TYPE, state, [:])

        when:
        context.setMediaType(MediaType.APPLICATION_JSON_TYPE)

        then:
        context.mediaType == MediaType.APPLICATION_JSON_TYPE
        headers.getAll(HttpHeaders.CONTENT_TYPE) == ["application/json"]

        when:
        context.setMediaType(null)

        then:
        !headers.contains(HttpHeaders.CONTENT_TYPE)
    }
}
