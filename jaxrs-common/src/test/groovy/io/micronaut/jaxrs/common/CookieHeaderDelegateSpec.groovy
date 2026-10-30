package io.micronaut.jaxrs.common

import jakarta.ws.rs.core.Cookie
import jakarta.ws.rs.ext.RuntimeDelegate
import spock.lang.Specification

class CookieHeaderDelegateSpec extends Specification {

    void "the path and domain of a request cookie are attributes"() {
        given:
        def delegate = RuntimeDelegate.getInstance().createHeaderDelegate(Cookie)

        expect:
        delegate.toString(new Cookie.Builder("a").value("b").build()) == "a=b"

        when:
        def header = delegate.toString(new Cookie.Builder("a").value("b").path("/p").domain("example.com").build())
        def parsed = CookieHeaderDelegate.parseCookies(header)

        then:
        !header.contains("; Path=")
        parsed.size() == 1
        parsed[0].name == "a"
        parsed[0].value == "b"
        parsed[0].path == "/p"
        parsed[0].domain == "example.com"
    }

    void "a literal backslash of a quoted value survives serialization"() {
        given:
        def delegate = RuntimeDelegate.getInstance().createHeaderDelegate(Cookie)

        when:
        def header = delegate.toString(new Cookie.Builder("id").value('a\\b c').build())

        then:
        header == 'id="a\\\\b c"'
        CookieHeaderDelegate.parseCookies(header)[0].value == 'a\\b c'
    }

    void "the attributes of a cookie are not those of the next one"() {
        when:
        def parsed = CookieHeaderDelegate.parseCookies('$Version=1; a=1; $Path=/one; $Domain=one.example; b=2')

        then:
        parsed.size() == 2
        parsed[0].path == "/one"
        parsed[0].domain == "one.example"
        parsed[1].name == "b"
        parsed[1].path == null
        parsed[1].domain == null
    }

    void "a delimiter in a quoted value is part of the value"() {
        given:
        def delegate = RuntimeDelegate.getInstance().createHeaderDelegate(Cookie)

        expect:
        delegate.fromString(delegate.toString(new Cookie.Builder("n").value("a,b").build())).value == "a,b"
        CookieHeaderDelegate.parseCookies('n="a;b"; m=c').collect { it.value } == ["a;b", "c"]
    }
}
