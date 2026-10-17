package io.micronaut.jaxrs.common

import jakarta.ws.rs.core.EntityTag
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedHashMap
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.Variant
import spock.lang.Specification

class ResponseBuilderSpec extends Specification {

    void "replaceAll writes the values with the header delegates"() {
        given:
        def headers = new MultivaluedHashMap<String, Object>()
        headers.add(HttpHeaders.LAST_MODIFIED, new Date(0))
        headers.add(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_TYPE)

        when:
        def response = Response.ok().header("X-Old", "1").replaceAll(headers).build()

        then:
        response.getHeaderString("X-Old") == null
        response.getHeaderString(HttpHeaders.LAST_MODIFIED) == "Thu, 1 Jan 1970 00:00:00 GMT"
        response.getHeaderString(HttpHeaders.CONTENT_TYPE) == "text/plain"
    }

    void "variants sets Vary with the headers the variants differ in"() {
        when:
        def response = Response.ok().variants(
                new Variant(MediaType.TEXT_PLAIN_TYPE, Locale.ENGLISH, null),
                new Variant(MediaType.APPLICATION_JSON_TYPE, Locale.ENGLISH, null)).build()

        then:
        response.getHeaderString(HttpHeaders.VARY) == HttpHeaders.ACCEPT

        when:
        response = Response.ok().variants(
                new Variant(MediaType.TEXT_PLAIN_TYPE, Locale.ENGLISH, "gzip"),
                new Variant(MediaType.APPLICATION_JSON_TYPE, Locale.FRENCH, "identity")).build()

        then:
        response.getHeaderString(HttpHeaders.VARY) == "Accept,Accept-Language,Accept-Encoding"

        when:
        response = Response.ok().variants(new Variant(MediaType.TEXT_PLAIN_TYPE, Locale.ENGLISH, null))
                .variants((List<Variant>) null).build()

        then:
        response.getHeaderString(HttpHeaders.VARY) == null
    }

    void "a null tag removes the ETag"() {
        expect:
        Response.ok().tag(new EntityTag("a")).tag((EntityTag) null).build().getHeaderString(HttpHeaders.ETAG) == null
        Response.ok().tag("a").tag((String) null).build().getHeaderString(HttpHeaders.ETAG) == null
    }
}
