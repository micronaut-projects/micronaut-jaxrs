package io.micronaut.jaxrs.common

import jakarta.ws.rs.core.EntityTag
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Link
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedHashMap
import jakarta.ws.rs.core.NewCookie
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
        response.getHeaderString(HttpHeaders.LAST_MODIFIED) == "Thu, 01 Jan 1970 00:00:00 GMT"
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

    void "tag of a string is a strong entity tag"() {
        expect:
        Response.ok().tag("v1").build().getHeaderString(HttpHeaders.ETAG) == '"v1"'
        Response.ok().tag("v1").build().getEntityTag() == new EntityTag("v1")
    }

    void "null links remove the links"() {
        expect:
        Response.ok().links(Link.fromUri("http://a").rel("a").build()).links((Link[]) null).build().getLinks().isEmpty()
    }

    void "build resets the builder"() {
        given:
        def builder = Response.status(404).header("X-Header", "a").entity("e")

        when:
        def first = builder.build()
        def second = builder.build()

        then:
        first.status == 404
        first.getHeaderString("X-Header") == "a"
        first.getEntity() == "e"
        second.status == 200
        second.getHeaderString("X-Header") == null
        !second.hasEntity()
    }

    void "a null entity keeps the selected status"() {
        expect:
        Response.status(200).entity(null).build().status == 200
        Response.ok().entity(null).entity("replacement").build().status == 200

        and: "a response without a selected status is a 204 without an entity"
        jakarta.ws.rs.ext.RuntimeDelegate.instance.createResponseBuilder().entity(null).build().status == 204
        jakarta.ws.rs.ext.RuntimeDelegate.instance.createResponseBuilder().entity("e").build().status == 200
    }

    void "the allowed methods are split at commas"() {
        expect:
        Response.ok().allow("GET", "post").build().allowedMethods == ["GET", "POST"] as Set
        Response.ok().header(HttpHeaders.ALLOW, "GET, POST").header(HttpHeaders.ALLOW, "PUT").build().allowedMethods == ["GET", "POST", "PUT"] as Set
    }

    void "the header views write through to the response"() {
        given:
        def response = Response.ok().header("X-Secret", "1").header("X-Other", "2").build()

        when:
        response.headers.keySet().remove("X-Secret")

        then:
        response.getHeaderString("X-Secret") == null
        !response.headers.containsKey("X-Secret")

        when:
        response.headers.entrySet().find { it.key == "X-Other" }.setValue(["3"])

        then:
        response.getHeaderString("X-Other") == "3"

        when:
        response.headers.values().clear()

        then:
        response.getHeaderString("X-Other") == null
    }

    void "a status out of 100..599 is rejected"() {
        when:
        Response.status(status)

        then:
        thrown(IllegalArgumentException)

        when:
        Response.status(status, "x")

        then:
        thrown(IllegalArgumentException)

        where:
        status << [99, 600]
    }

    void "a variant replaces the language"() {
        when:
        def response = Response.ok().language("en").variant(new Variant(MediaType.TEXT_HTML_TYPE, "fr", "gzip")).build()

        then:
        response.language == Locale.FRENCH
        response.getHeaderString(HttpHeaders.CONTENT_LANGUAGE) == "fr"
        response.getHeaderString(HttpHeaders.CONTENT_ENCODING) == "gzip"
    }

    void "the dates are IMF-fixdates"() {
        expect:
        Response.ok().lastModified(new Date(0)).build().getHeaderString(HttpHeaders.LAST_MODIFIED) == "Thu, 01 Jan 1970 00:00:00 GMT"
        Response.ok().expires(new Date(0)).build().getHeaderString(HttpHeaders.EXPIRES) == "Thu, 01 Jan 1970 00:00:00 GMT"
        Response.ok().header("X-Date", new Date(0)).build().getHeaderString("X-Date") == "Thu, 01 Jan 1970 00:00:00 GMT"
    }

    void "fromResponse does not duplicate a date header set raw then with lastModified"() {
        given:
        def original = Response.status(301).header(HttpHeaders.LAST_MODIFIED, "garbage").allow("PUT", "get")
            .cookie(new NewCookie.Builder("c").value("v").build()).tag("t").lastModified(new Date(0)).build()

        expect:
        Response.fromResponse(original).build().getHeaderString(HttpHeaders.LAST_MODIFIED) == original.getHeaderString(HttpHeaders.LAST_MODIFIED)
    }

}
