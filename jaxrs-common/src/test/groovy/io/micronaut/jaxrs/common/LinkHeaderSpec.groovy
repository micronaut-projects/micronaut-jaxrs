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

    void "a header of several links is not a single link"() {
        when:
        Link.valueOf('</a>; rel="first", </b>; rel="last"')

        then:
        thrown(IllegalArgumentException)
    }
}
