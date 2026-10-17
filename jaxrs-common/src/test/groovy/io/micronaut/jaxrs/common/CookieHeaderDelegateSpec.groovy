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
}
