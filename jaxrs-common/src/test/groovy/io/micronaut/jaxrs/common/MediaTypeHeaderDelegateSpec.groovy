package io.micronaut.jaxrs.common

import jakarta.ws.rs.core.MediaType
import spock.lang.Specification

class MediaTypeHeaderDelegateSpec extends Specification {

    void "an empty quoted parameter value is the empty string"() {
        expect:
        MediaType.valueOf('a/b;x=""').getParameters().get("x") == ""
        new MediaType("a", "b", [x: ""]).toString() == 'a/b;x=""'
    }

    void "a quoted-pair is unescaped when parsed and escaped when serialized"() {
        expect:
        MediaType.valueOf('text/plain;x="a\\"b"').getParameters().get("x") == 'a"b'
        MediaType.valueOf('text/plain;x="a\\\\b"').getParameters().get("x") == 'a\\b'
        new MediaType("text", "plain", [x: 'a"b']).toString() == 'text/plain;x="a\\"b"'
    }

    void "serializing a media type does not change how a string is parsed later"() {
        given:
        new MediaType("X-PROBE", "ABC").toString()

        expect:
        MediaType.valueOf("x-probe/abc").getType() == "x-probe"
    }

    void "optional whitespace is not part of the type"() {
        expect:
        MediaType.valueOf(" text/plain ").getType() == "text"
        MediaType.valueOf(" text/plain ").getSubtype() == "plain"
        MediaType.valueOf("text/plain ; charset=UTF-8").getSubtype() == "plain"
        MediaType.valueOf("*; q=.2").isWildcardSubtype() && MediaType.valueOf("*; q=.2").isWildcardType()
    }

    void "a type or a subtype that is not a token is rejected"() {
        when:
        MediaType.valueOf(value)

        then:
        thrown(IllegalArgumentException)

        where:
        value << ["text/pl ain", "text/pl?ain", 'text/pl"ain', "te{xt/plain", "text/pl\tain"]
    }

    void "a token type is accepted"() {
        expect:
        MediaType.valueOf("application/vnd.a+json").getSubtype() == "vnd.a+json"
        MediaType.valueOf("x-a/b!#\$%&'*^_`|~").getType() == "x-a"
    }

    void "serializing does not depend on the default locale"() {
        given:
        Locale previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr"))

        when:
        String value = new MediaType("IMAGE", "TIFF").toString()

        then:
        value == "image/tiff"

        cleanup:
        Locale.setDefault(previous)
    }
}
