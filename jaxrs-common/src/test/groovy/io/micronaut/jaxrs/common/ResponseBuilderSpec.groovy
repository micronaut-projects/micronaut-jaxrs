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

    void "encoding, contentLocation and cacheControl replace the header, header adds a value"() {
        given:
        def first = new jakarta.ws.rs.core.CacheControl()
        first.noStore = true
        def second = new jakarta.ws.rs.core.CacheControl()
        second.maxAge = 5
        second.noTransform = false
        def response = Response.ok()
                .encoding("gzip").encoding("br")
                .contentLocation(URI.create("/one")).contentLocation(URI.create("/two"))
                .cacheControl(first).cacheControl(second)
                .header("X-A", "1").header("X-A", "2")
                .build()

        expect:
        response.getStringHeaders().get(HttpHeaders.CONTENT_ENCODING) == ['br']
        response.getStringHeaders().get(HttpHeaders.CONTENT_LOCATION) == ['/two']
        response.getStringHeaders().get(HttpHeaders.CACHE_CONTROL) == ['max-age=5']
        response.getStringHeaders().get("X-A") == ['1', '2']
    }

    void "a generic entity given with annotations keeps its generic type"() {
        given:
        def generic = new jakarta.ws.rs.core.GenericEntity<List<String>>(['a'], new jakarta.ws.rs.core.GenericType<List<String>>() {}.type)
        def annotation = Deprecated.getAnnotation(java.lang.annotation.Documented)
        def response = (JaxRsResponse) Response.ok().entity(generic, [annotation] as java.lang.annotation.Annotation[]).build()
        def body = (JaxRsGenericEntity) response.getResponse().body()

        expect:
        response.getEntity() == ['a']
        body.type == generic.type
        body.asArgument().type == List
        body.asArgument().typeParameters*.type == [String]
        body.annotations.toList() == [annotation]
    }

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

    void "fromResponse keeps a raw Set-Cookie header"() {
        given:
        def original = Response.ok().header(HttpHeaders.SET_COOKIE, "n=v").cookie(new NewCookie.Builder("c").value("v").build()).build()

        expect:
        original.headers.get(HttpHeaders.SET_COOKIE).size() == 2
        Response.fromResponse(original).build().getHeaderString(HttpHeaders.SET_COOKIE) == original.getHeaderString(HttpHeaders.SET_COOKIE)
    }

    void "a null annotated entity clears the entity"() {
        expect:
        !Response.ok("old").entity(null, new java.lang.annotation.Annotation[0]).build().hasEntity()
    }

    void "an identity variant differs from a gzip one"() {
        when:
        def response = Response.ok().variants(
                new Variant(MediaType.TEXT_PLAIN_TYPE, Locale.ENGLISH, null),
                new Variant(MediaType.TEXT_PLAIN_TYPE, Locale.ENGLISH, "gzip")).build()

        then:
        response.getHeaderString(HttpHeaders.VARY) == HttpHeaders.ACCEPT_ENCODING
    }

    void "fromResponse keeps the repeated Date values of a header"() {
        given:
        def original = Response.ok().header("X-Date", new Date(0)).header("X-Date", new Date(1000)).build()

        when:
        def copy = Response.fromResponse(original).build()

        then:
        original.getHeaders().get("X-Date") == [new Date(0), new Date(1000)]
        copy.getHeaders().get("X-Date") == [new Date(0), new Date(1000)]
        copy.getStringHeaders().get("X-Date").size() == 2
    }

    void "the media type of the response is kept as given in the metadata"() {
        expect:
        Response.ok().type(MediaType.TEXT_PLAIN_TYPE).build().getHeaders().getFirst("Content-Type") == MediaType.TEXT_PLAIN_TYPE
    }

    void "the names of the metadata are case-insensitive"() {
        given:
        def response = Response.ok().lastModified(new Date(0)).build()

        when:
        response.getHeaders().remove("last-modified")

        then:
        !response.getHeaders().containsKey("Last-Modified")
        response.getHeaders().size() == 0
        response.getHeaders().getFirst("LAST-MODIFIED") == null
    }

    void "allow replaces the allowed methods"() {
        expect:
        Response.ok().allow("GET").allow("POST").build().allowedMethods == ["POST"] as Set
        Response.ok().allow("GET").allow(["POST"] as Set).build().allowedMethods == ["POST"] as Set
    }
}
