package io.micronaut.jaxrs.conformance;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.Variant;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The minimized reproducers of the client API discrepancies found by {@link ClientApiConformanceTest},
 * {@link HeaderConformanceTest} and {@link UriConformanceTest}, asserting what the specification requires.
 * Every test here failing is a confirmed conformance bug of Micronaut.
 */
class ClientFindingsTest {
    private static RuntimeDelegate previous;

    @BeforeAll
    static void micronaut() {
        previous = RuntimeDelegate.getInstance();
        RuntimeDelegate.setInstance(Oracles.MICRONAUT);
    }

    @AfterAll
    static void restore() {
        RuntimeDelegate.setInstance(previous);
    }

    /** Response.status(int): IllegalArgumentException if status is less than 100 or greater than 599. */
    @Test
    void statusOutOfRangeIsRejected() {
        assertAll(
            () -> assertThrows(IllegalArgumentException.class, () -> Response.status(99)),
            () -> assertThrows(IllegalArgumentException.class, () -> Response.status(600)),
            () -> assertThrows(IllegalArgumentException.class, () -> Response.status(600, "x")));
    }

    /** ResponseBuilder.variant(): equivalent to setting content type, language and encoding separately (they replace). */
    @Test
    void variantReplacesTheLanguage() {
        Response r = Response.ok().language("en").variant(new Variant(MediaType.TEXT_HTML_TYPE, "fr", "gzip")).build();
        assertAll(
            () -> assertEquals(Locale.FRENCH, r.getLanguage()),
            () -> assertEquals("fr", r.getHeaderString(HttpHeaders.CONTENT_LANGUAGE)),
            () -> assertEquals("gzip", r.getHeaderString(HttpHeaders.CONTENT_ENCODING)));
    }

    /** RFC 9110 5.6.7: IMF-fixdate has a two-digit day. */
    @Test
    void datesAreImfFixdate() {
        assertAll(
            () -> assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", RuntimeDelegate.getInstance().createHeaderDelegate(Date.class).toString(new Date(0))),
            () -> assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", Response.ok().lastModified(new Date(0)).build().getHeaderString("Last-Modified")));
    }

    /** Response.fromResponse() copies the headers: it does not duplicate them. */
    @Test
    void fromResponseDoesNotDuplicateDateHeaders() {
        // the sequence of ClientApiConformanceTest.seededResponseBuilder "status=301 extra=1 ... Last-Modified: garbage"
        Response original = Response.status(301).header("Last-Modified", "garbage").allow("PUT", "get")
            .cookie(new jakarta.ws.rs.core.NewCookie.Builder("c").value("v").build()).tag("t").lastModified(new Date(0)).build();
        Response copy = Response.fromResponse(original).build();
        assertEquals(original.getHeaderString("Last-Modified"), copy.getHeaderString("Last-Modified"));
    }

    /** Response.fromResponse(): a copy of the response headers, a raw Set-Cookie header included. */
    @Test
    void fromResponseKeepsRawSetCookie() {
        Response original = Response.ok().header("Set-Cookie", "n=v").cookie(new jakarta.ws.rs.core.NewCookie.Builder("c").value("v").build()).build();
        Response copy = Response.fromResponse(original).build();
        assertEquals(original.getHeaderString("Set-Cookie"), copy.getHeaderString("Set-Cookie"));
    }

    /** An encoded template value is not re-encoded: the same path as buildFromEncoded, a '/' kept. */
    @Test
    void resolveTemplateFromEncodedKeepsSlash() {
        assertEquals(UriBuilder.fromUri("http://x.test/{x}").buildFromEncoded("a/b").toString(),
            UriBuilder.fromUri("http://x.test/{x}").resolveTemplateFromEncoded("x", "a/b").build().toString());
    }

    /**
     * RFC 7578 4.2 / RFC 2183: Content-Disposition parameters are quoted-strings, a semicolon inside one does not
     * end it, and a filename cannot supply the name of the part.
     */
    @Test
    void multipartDispositionParametersAreQuotedStrings() throws Exception {
        String entity = "--B\r\nContent-Disposition: form-data; filename=\"x; name=evil\"; name=\"real\"\r\n\r\nv\r\n"
            + "--B\r\nContent-Disposition: form-data; name=\"a;b\"\r\n\r\nw\r\n--B--\r\n";
        @SuppressWarnings({"unchecked", "rawtypes"})
        java.util.List<jakarta.ws.rs.core.EntityPart> parts = new io.micronaut.jaxrs.common.multipart.JaxRsMultipartMessageBodyReader().readFrom(
            (Class) java.util.List.class, new jakarta.ws.rs.core.GenericType<java.util.List<jakarta.ws.rs.core.EntityPart>>() { }.getType(),
            new java.lang.annotation.Annotation[0], MediaType.valueOf("multipart/form-data;boundary=B"), new jakarta.ws.rs.core.MultivaluedHashMap<>(),
            new java.io.ByteArrayInputStream(entity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertAll(
            () -> assertEquals("real", parts.get(0).getName()),
            () -> assertEquals("x; name=evil", parts.get(0).getFileName().orElse(null)),
            () -> assertEquals("a;b", parts.get(1).getName()));
    }

    /**
     * UriBuilder: "contextual encoding of characters not permitted in the corresponding URI component": '@' and
     * '/' are not permitted in a user-info, they cannot change the authority.
     */
    @Test
    void userInfoAndHostAreEncoded() {
        assertAll(
            () -> assertEquals("x.test", UriBuilder.fromUri("http://x.test/").userInfo("a/b").build().getHost()),
            () -> assertEquals("x.test", UriBuilder.fromUri("http://x.test/").userInfo("@").build().getHost()),
            () -> assertEquals("http://a%20b@x.test/", UriBuilder.fromUri("http://x.test/").userInfo("a b").build().toString()),
            () -> assertEquals("/", UriBuilder.fromUri("http://x.test/").host("a/b").build().getRawPath()));
    }
}
