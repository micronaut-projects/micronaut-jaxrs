package io.micronaut.jaxrs.conformance;

import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
