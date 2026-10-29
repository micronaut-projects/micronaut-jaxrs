package io.micronaut.jaxrs.common

import io.micronaut.http.HttpResponse
import jakarta.ws.rs.core.Link
import spock.lang.Specification

class LinkHeaderSpec extends Specification {

    void "a header of several links has each link, relative ones resolved against the request URI"() {
        given:
        def response = new JaxRsResponse(HttpResponse.ok()
                .header("Link", '</a>; rel="first", <http://other/b>; rel="last"')
                .attribute(JaxRsResponse.REQUEST_URI, URI.create("http://host/x/y")))

        expect:
        response.getLinks().size() == 2
        response.getLink("first").getUri() == URI.create("http://host/a")
        response.getLink("last").getUri() == URI.create("http://other/b")
    }

    void "a link of several relations is found by each of them"() {
        given:
        def response = new JaxRsResponse(HttpResponse.ok()
                .header("Link", '<http://example.org/next>; rel="next alternate"'))

        expect:
        response.hasLink("next")
        response.hasLink("alternate")
        !response.hasLink("prev")
        response.getLink("next").getUri() == URI.create("http://example.org/next")
        response.getLink("alternate").getUri() == URI.create("http://example.org/next")
    }

    void "the quoted strings of a link are escaped and unescaped"() {
        given:
        def link = Link.fromUri("http://x/").title('a"b\\c').build()

        expect:
        link.toString() == '<http://x/>; title="a\\"b\\\\c"'
        Link.valueOf(link.toString()).title == 'a"b\\c'
        Link.valueOf('<http://x/>; rel=next; title="a\\"b"').title == 'a"b'
        Link.valueOf('<http://x/>; rel=next; title="a\\"b"').rel == 'next'
    }

    void "a header of several links is not a single link"() {
        when:
        Link.valueOf('</a>; rel="first", </b>; rel="last"')

        then:
        thrown(IllegalArgumentException)
    }

    void "occurrences of rel after the first are ignored"() {
        expect:
        Link.valueOf('<http://x.test/a>; rel=next; rel=last').getRels() == ["next"]
    }

    void "an invalid link is an IllegalArgumentException"() {
        when:
        Link.valueOf('< http://x.test >; rel=next')

        then:
        thrown(IllegalArgumentException)
    }

    void "whitespace is allowed around the equals sign of a parameter"() {
        given:
        def link = Link.valueOf('<http://example.org/>; rel = "next"; title =  t ')

        expect:
        link.rel == "next"
        link.title == "t"
        new JaxRsResponse(HttpResponse.ok().header("Link", '<http://example.org/>; rel = "next"')).hasLink("next")
    }
}
