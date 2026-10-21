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

    /** RFC 8288 3.3: occurrences of rel after the first MUST be ignored. */
    @Test
    void repeatedRelIsIgnored() {
        assertEquals(java.util.List.of("next"), Link.valueOf("<http://x.test/a>; rel=next; rel=last").getRels());
    }

    /** Link.valueOf: IllegalArgumentException if a syntax error is found (not another exception). */
    @Test
    void linkSyntaxErrorIsAnIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> Link.valueOf("< http://x.test >; rel=next"));
    }

    /** RFC 9110 5.6.6: an empty quoted-string is a parameter value, the empty string. */
    @Test
    void emptyQuotedParameterIsEmpty() {
        assertEquals("", MediaType.valueOf("a/b;x=\"\"").getParameters().get("x"));
    }

    /** RFC 9110 5.6.4: a backslash-escaped character in a quoted-string is the character itself. */
    @Test
    void quotedPairIsUnescaped() {
        assertAll(
            () -> assertEquals("a\"b", MediaType.valueOf("text/plain;x=\"a\\\"b\"").getParameters().get("x")),
            () -> assertEquals("a\\b", MediaType.valueOf("text/plain;x=\"a\\\\b\"").getParameters().get("x")));
    }

    /** RFC 9110 5.6.4: a quote or backslash in a quoted parameter value is escaped when serialized. */
    @Test
    void quotedParameterIsEscaped() {
        MediaType type = new MediaType("text", "plain", java.util.Map.of("x", "a\"b"));
        assertEquals("text/plain;x=\"a\\\"b\"", type.toString());
    }

    /** MediaType.valueOf("text/plain") gives the type as written, whatever MediaType was serialized before. */
    @Test
    void serializationDoesNotChangeLaterParsing() {
        String ignored = new MediaType("X-PROBE", "ABC").toString();
        assertEquals("x-probe", MediaType.valueOf("x-probe/abc").getType(), ignored);
    }

    /** RFC 9110 5.6.6 and 8.3.1: optional whitespace around the media type and before the semicolon. */
    @Test
    void mediaTypeWhitespaceIsNotPartOfTheType() {
        assertAll(
            () -> assertEquals("text", MediaType.valueOf(" text/plain ").getType()),
            () -> assertEquals("plain", MediaType.valueOf(" text/plain ").getSubtype()),
            () -> assertEquals("plain", MediaType.valueOf("text/plain ; charset=UTF-8").getSubtype()));
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
