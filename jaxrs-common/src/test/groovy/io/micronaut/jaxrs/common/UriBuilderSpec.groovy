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

    void "a template value cannot change the authority"() {
        expect:
        UriBuilder.fromUri("http://{host}/").build("trusted.example@evil.example").host != "evil.example"
        UriBuilder.fromUri("http://{user}@host/").build("a@b").host == "host"
        UriBuilder.fromUri("http://host/#{f}").build("a#b").rawFragment == "a%23b"
        UriBuilder.fromUri("http://{host}:8080/p").build("example.org").toString() == "http://example.org:8080/p"
    }

    void "an opaque scheme-specific part replaces the hierarchical components"() {
        expect:
        UriBuilder.fromUri("http://old/a").scheme("mailto").schemeSpecificPart("{user}@example.org").build("alice").toString() == "mailto:alice@example.org"
        UriBuilder.fromUri("http://old/a?q=1").scheme("mailto").schemeSpecificPart("bob@example.org").build().toString() == "mailto:bob@example.org"
        UriBuilder.fromUri("http://old/a#f").scheme("mailto").schemeSpecificPart("{user}@example.org").build("alice").toString() == "mailto:alice@example.org#f"
    }

    void "removing the matrix parameters removes their semicolon"() {
        expect:
        UriBuilder.fromPath("/a;x=1").replaceMatrix(null).build().toString() == "/a"
        UriBuilder.fromPath("/a;x=1").replaceMatrix("y=2").build().toString() == "/a;y=2"
    }

    void "replacing a matrix parameter keeps the templates of the others in place"() {
        expect:
        UriBuilder.fromPath("p;m={x};n={y}").replaceMatrixParam("m", "v").buildFromMap([x: "X", y: "Y"]).toString() == "p;n=Y;m=v"
        UriBuilder.fromPath("_jaxrs_uri_parameter/{id};m=1").replaceMatrixParam("m", "2").build("7").toString() == "_jaxrs_uri_parameter/7;m=2"
    }

    void "an unresolved template keeps the braces of its regular expression"() {
        when:
        def builder = UriBuilder.fromPath("{x:[0-9]{2}}/{y}").resolveTemplate("y", "ok")

        then:
        builder.toTemplate() == "{x:[0-9]{2}}/ok"
        builder.build("12").toString() == "12/ok"
    }

    void "an empty query replaces the query"() {
        expect:
        UriBuilder.fromUri("http://example/a?q=1").uri(URI.create("http://example/b?")).build().toString() == "http://example/b?"
    }
}
