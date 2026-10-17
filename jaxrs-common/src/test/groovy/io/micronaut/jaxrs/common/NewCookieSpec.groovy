package io.micronaut.jaxrs.common

import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.NewCookie
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.RuntimeDelegate
import spock.lang.Specification

class NewCookieSpec extends Specification {

    def delegate = RuntimeDelegate.getInstance().createHeaderDelegate(NewCookie)

    void "the response builder writes all the attributes of a cookie"() {
        given:
        def expiry = new Date(0)
        def cookie = new NewCookie.Builder("session")
                .value("abc")
                .path("/p")
                .domain("example.com")
                .comment("hi")
                .maxAge(60)
                .expiry(expiry)
                .secure(true)
                .httpOnly(true)
                .sameSite(NewCookie.SameSite.LAX)
                .version(1)
                .build()

        when:
        def response = Response.ok().cookie(cookie).build()
        def header = response.getStringHeaders().getFirst(HttpHeaders.SET_COOKIE)

        then:
        header.contains("Max-Age=60")
        header.contains("Expires=Thu, 01 Jan 1970 00:00:00 GMT")
        header.contains("Secure")
        header.contains("HttpOnly")
        header.contains("SameSite=Lax")
        header.contains("Comment=hi")
        header.contains("Version=1")

        when:
        def parsed = response.getCookies()["session"]

        then:
        parsed.value == "abc"
        parsed.path == "/p"
        parsed.domain == "example.com"
        parsed.comment == "hi"
        parsed.maxAge == 60
        parsed.expiry == expiry
        parsed.secure
        parsed.httpOnly
        parsed.sameSite == NewCookie.SameSite.LAX
    }

    void "a cookie with an expiry in the past deletes it"() {
        when:
        def response = Response.ok().cookie(new NewCookie.Builder("a").value("b").expiry(new Date(1000)).maxAge(0).build()).build()

        then:
        response.getCookies()["a"].maxAge == 0
        response.getCookies()["a"].expiry == new Date(1000)
    }

    void "the first pair is the cookie whatever the other attributes"() {
        when:
        NewCookie cookie = delegate.fromString("name=value; SameSite=Lax; Partitioned; secure; path=/x; expires=Wed, 21 Oct 2015 07:28:00 GMT")

        then:
        cookie.name == "name"
        cookie.value == "value"
        cookie.sameSite == NewCookie.SameSite.LAX
        cookie.secure
        cookie.path == "/x"
        cookie.expiry == Date.from(java.time.Instant.parse("2015-10-21T07:28:00Z"))
    }

    void "the old expires format is parsed"() {
        when:
        NewCookie cookie = delegate.fromString("name=value; Expires=Wed, 21-Oct-2015 07:28:00 GMT")

        then:
        cookie.expiry == Date.from(java.time.Instant.parse("2015-10-21T07:28:00Z"))
    }

    void "the expiry is formatted in GMT whatever the default locale and time zone"() {
        given:
        def locale = Locale.default
        def zone = TimeZone.default
        Locale.default = Locale.GERMANY
        TimeZone.default = TimeZone.getTimeZone("Asia/Tokyo")

        when:
        def header = delegate.toString(new NewCookie.Builder("a").value("b").expiry(new Date(0)).build())

        then:
        header.contains("Expires=Thu, 01 Jan 1970 00:00:00 GMT")

        cleanup:
        Locale.default = locale
        TimeZone.default = zone
    }

    void "two cookies of a name do not fail"() {
        when:
        def response = Response.ok()
                .header(HttpHeaders.SET_COOKIE, "a=1; SameSite=Lax")
                .header(HttpHeaders.SET_COOKIE, "a=2; Partitioned")
                .build()

        then:
        response.getCookies()["a"].value == "2"
    }
}
