package io.micronaut.jaxrs.common

import jakarta.ws.rs.core.UriBuilder
import jakarta.ws.rs.core.UriBuilderException
import spock.lang.Specification

class UriBuilderSpec extends Specification {

    void "the templates of an opaque URI are resolved"() {
        expect:
        UriBuilder.fromUri("mailto:{user}@example.org").build("alice").toString() == "mailto:alice@example.org"
        UriBuilder.fromUri("mailto:{user}@example.org").buildFromMap([user: "bob"]).toString() == "mailto:bob@example.org"
        UriBuilder.fromUri("mailto:x@example.org").scheme("mailto").schemeSpecificPart("{user}@example.org").build("alice").toString() == "mailto:alice@example.org"
        UriBuilder.fromUri("mailto:{user}@example.org").toTemplate() == "mailto:{user}@example.org"
        UriBuilder.fromUri("mailto:x@example.org").schemeSpecificPart("y@example.org").build().toString() == "mailto:y@example.org"
    }

    void "a matrix parameter with a name to encode is replaced"() {
        expect:
        UriBuilder.fromPath("/p").matrixParam("a b", 1).replaceMatrixParam("a b", 2).build().toString() == "/p;a%20b=2"
    }

    void "buildFromMap fails with a UriBuilderException"() {
        when:
        UriBuilder.fromUri("http://localhost").scheme("{x}").buildFromEncodedMap([x: "1"])

        then:
        thrown(UriBuilderException)
    }

    void "a user-info and a host are encoded and cannot change the authority"() {
        expect:
        UriBuilder.fromUri("http://x.test/").userInfo("a/b").build().host == "x.test"
        UriBuilder.fromUri("http://x.test/").userInfo("@").build().host == "x.test"
        UriBuilder.fromUri("http://x.test/").userInfo("a b").build().toString() == "http://a%20b@x.test/"
        UriBuilder.fromUri("http://u@x.test/").userInfo("").build().toString() == "http://x.test/"
        UriBuilder.fromUri("http://x.test/").userInfo("u:p{x}").build("v").toString() == "http://u:pv@x.test/"
        UriBuilder.fromUri("http://x.test/").host("a/b").build().rawPath == "/"
        UriBuilder.fromUri("http://x.test/").host("[::1]").build().host == "[::1]"
    }

    void "a template resolved from an encoded value keeps a slash in the path, as buildFromEncoded does"() {
        expect:
        UriBuilder.fromUri("http://x.test/{x}").resolveTemplateFromEncoded("x", "a/b").build().toString() == "http://x.test/a/b"
        UriBuilder.fromUri("http://x.test/{x}").resolveTemplatesFromEncoded([x: "a/b"]).build().toString() == "http://x.test/a/b"
        UriBuilder.fromUri("http://x.test/{x}").buildFromEncoded("a/b").toString() == "http://x.test/a/b"
    }
}
