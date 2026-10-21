package io.micronaut.jaxrs.common

import io.micronaut.http.simple.SimpleHttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import spock.lang.Specification

class HeadersSpec extends Specification {

    void "the names of the string headers are case-insensitive"() {
        given:
        def headers = new SimpleHttpHeaders()
        headers.add("X-Header", "a")
        def map = new JaxRsHeadersMultivaluedMap(headers)

        expect:
        map.get("x-header") == ["a"]
        map.containsKey("X-HEADER")
        map.getFirst("x-HEADER") == "a"
        map.get("absent") == null
        !map.containsKey("absent")
    }

    void "the mutable string headers are case-insensitive, and put replaces the values"() {
        given:
        def headers = new SimpleHttpHeaders()
        headers.add("X-Header", "a")
        def map = new JaxRsMutableHeadersMultivaluedMap(headers)

        when:
        def previous = map.put("x-header", ["b", "c"])

        then:
        previous == ["a"]
        headers.getAll("X-Header") == ["b", "c"]

        when:
        map.putAll(["X-HEADER": ["d"]])

        then:
        headers.getAll("X-Header") == ["d"]

        when:
        previous = map.remove("x-header")

        then:
        previous == ["d"]
        !headers.contains("X-Header")
        map.remove("x-header") == null
    }

    void "the string headers of a response are case-insensitive"() {
        given:
        def response = Response.ok().header("X-Header", "a").build()

        expect:
        response.getStringHeaders().get("x-header") == ["a"]
        response.getStringHeaders().containsKey("x-HEADER")
    }

    void "getRequestHeader is null for an absent header"() {
        given:
        def headers = new SimpleHttpHeaders()
        headers.add("X-Header", "a")
        def httpHeaders = JaxRsHttpHeaders.forRequest(headers)

        expect:
        httpHeaders.getRequestHeader("x-header") == ["a"]
        httpHeaders.getRequestHeader("absent") == null
    }

    void "any language is acceptable without an Accept-Language header"() {
        given:
        def headers = new SimpleHttpHeaders()

        expect:
        JaxRsHttpHeaders.forRequest(headers).getAcceptableLanguages() == [Locale.of("*")]

        when:
        headers.add("Accept-Language", "da, en-gb;q=0.6")

        then:
        JaxRsHttpHeaders.forRequest(headers).getAcceptableLanguages() == [Locale.forLanguageTag("da"), Locale.forLanguageTag("en-gb")]
    }

    void "a wildcard language with a quality is the wildcard locale"() {
        given:
        def headers = new SimpleHttpHeaders()
        headers.add("Accept-Language", "*;q=0.1, fr")

        expect:
        JaxRsHttpHeaders.forRequest(headers).getAcceptableLanguages() == [Locale.forLanguageTag("fr"), Locale.of("*")]
    }

    void "the acceptable media types are trimmed and a bare '*' is any type"() {
        given:
        def headers = new SimpleHttpHeaders()
        headers.add("Accept", "text/html, image/gif, *; q=.2, , application/json")

        expect:
        JaxRsHttpHeaders.forRequest(headers).getAcceptableMediaTypes()*.toString() == ["text/html", "image/gif", "application/json", "*/*;q=.2"]
    }

    void "an invalid media range is ignored"() {
        given:
        def headers = new SimpleHttpHeaders()
        headers.add("Accept", "foo, text/*")
        def invalid = new SimpleHttpHeaders()
        invalid.add("Accept", "foo")

        expect:
        JaxRsHttpHeaders.forRequest(headers).getAcceptableMediaTypes()*.toString() == ["text/*"]
        JaxRsHttpHeaders.forRequest(invalid).getAcceptableMediaTypes() == [MediaType.WILDCARD_TYPE]
    }
}
