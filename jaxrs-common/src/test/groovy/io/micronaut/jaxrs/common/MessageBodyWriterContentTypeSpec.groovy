package io.micronaut.jaxrs.common

import io.micronaut.core.type.Argument
import io.micronaut.http.MediaType
import io.micronaut.http.simple.SimpleHttpHeaders
import io.micronaut.jaxrs.common.body.standard.JaxRsStringMessageBodyReaderWriter
import spock.lang.Specification

class MessageBodyWriterContentTypeSpec extends Specification {

    void "a wildcard subtype is not written as the Content-Type"() {
        given:
        def headers = new SimpleHttpHeaders()
        def writer = new JaxRsMessageBodyWriter<String>(produces.collect { MediaType.of(it) }, new JaxRsStringMessageBodyReaderWriter())

        when:
        writer.writeTo(Argument.of(String), MediaType.of(negotiated), "x", headers, new ByteArrayOutputStream())

        then:
        headers.get("Content-Type") == expected

        where:
        negotiated      | produces             | expected
        "application/*" | []                   | "application/octet-stream"
        "application/*" | ["application/json"] | "application/json"
        "text/plain"    | []                   | "text/plain"
    }
}
