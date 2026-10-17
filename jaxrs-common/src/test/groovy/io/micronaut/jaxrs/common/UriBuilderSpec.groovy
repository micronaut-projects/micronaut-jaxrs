package io.micronaut.jaxrs.common

import jakarta.ws.rs.core.UriBuilder
import jakarta.ws.rs.core.UriBuilderException
import spock.lang.Specification

class UriBuilderSpec extends Specification {

    void "a matrix parameter with a name to encode is replaced"() {
        expect:
        UriBuilder.fromPath("/p").matrixParam("a b", 1).replaceMatrixParam("a b", 2).build().toString() == "/p;a%20b=2"
    }

    void "buildFromMap fails with a UriBuilderException"() {
        when:
        UriBuilder.fromUri("http://localhost").host("bad host{x}").buildFromEncodedMap([x: "%zz"])

        then:
        thrown(UriBuilderException)
    }
}
