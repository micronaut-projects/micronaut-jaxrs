package io.micronaut.jaxrs.conformance;

import io.micronaut.jaxrs.conformance.Servers.Req;
import io.micronaut.jaxrs.conformance.Servers.Res;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The minimized reproducers of the server discrepancies found by {@link ServerConformanceTest}, asserting what
 * the specification (or the RFC it refers to) requires. Every test here failing is a confirmed Micronaut bug.
 */
class ServerFindingsTest {
    private static Servers servers;

    @BeforeAll
    static void start() {
        servers = new Servers(ServerResources.CLASSES);
    }

    @AfterAll
    static void stop() {
        servers.close();
    }

    private static Res send(String method, String target, String... headers) throws Exception {
        List<String[]> list = new ArrayList<>();
        byte[] body = null;
        for (String h : headers) {
            if (h.startsWith("BODY=")) {
                body = h.substring(5).getBytes();
            } else {
                list.add(h.split(": ", 2));
            }
        }
        return servers.micronaut(new Req(method, target, list, body));
    }

    /** RFC 3986: '+' is not an encoding of a space in a path; @PathParam is percent-decoded only (JAX-RS 3.2). */
    @Test
    void plusInPathParamIsAPlus() throws Exception {
        assertEquals("name:a+b|a+b", send("GET", "/r/t/a+b").text());
        assertEquals("deep:a+b", send("GET", "/r/t/a+b/z").text());
    }

    /** UriInfo.getQueryParameters()/@QueryParam: a semicolon is not a query parameter separator. */
    @Test
    void semicolonDoesNotSplitQueryParameters() throws Exception {
        assertEquals("q:[a;b]|dflt|[a;b]|{x=[a;b]}|{x=[a;b]}", send("GET", "/r/q?x=a;b").text());
    }

    /**
     * UriInfo.getQueryParameters(false) has the same parameters as getQueryParameters(true), not decoded: an empty value
     * is kept, and "=v" is a parameter with an empty name (or ignored), never one named "=v" or "v".
     */
    @Test
    void encodedQueryParametersKeepEmptyValues() throws Exception {
        assertEquals("q:[a, ]|dflt|[a, ]|{x=[a, ]}|{x=[a, ]}", send("GET", "/r/q?x=a&x=").text());
        String body = send("GET", "/r/q?=v").text();
        assertFalse(body.contains("v=[]") || body.contains("=v="), body);
    }

    /** RFC 9110 13.2.2: If-Unmodified-Since (step 2) is evaluated before If-None-Match (step 3). */
    @Test
    void ifUnmodifiedSinceBeforeIfNoneMatch() throws Exception {
        assertEquals(412, send("GET", "/r/cond", "If-None-Match: \"v1\"", "If-Unmodified-Since: Sat, 31 Dec 2022 23:59:59 GMT").status());
    }

    /** RFC 9110 5.6.7: a recipient that parses a timestamp MUST accept the obsolete formats too. */
    @Test
    void obsoleteDateFormatsAreAccepted() throws Exception {
        assertAll(
            () -> assertEquals(304, send("GET", "/r/cond/date", "If-Modified-Since: Sunday, 01-Jan-23 00:00:00 GMT").status()),
            () -> assertEquals(304, send("GET", "/r/cond/date", "If-Modified-Since: Sun Jan  1 00:00:00 2023").status()),
            () -> assertEquals(412, send("GET", "/r/cond/date", "If-Unmodified-Since: Sat Dec 31 00:00:00 2022").status()));
    }

    /** RFC 9110 12.5.3: identity is acceptable unless excluded; Request.selectVariant() must not reject it. */
    @Test
    void identityVariantIsAcceptableWhenNotExcluded() throws Exception {
        Res res = send("GET", "/r/variant", "Accept-Encoding: gzip;q=0");
        assertEquals(200, res.status());
        assertTrue(res.text().endsWith("|identity"), res.text());
    }

    /** JAX-RS 4.2.2 step 5 / 3.8: no MessageBodyWriter for the entity and the selected type is a 500, not a 406. */
    @Test
    void noWriterIsAServerError() throws Exception {
        assertEquals(500, send("GET", "/p/lit/x").status());
        assertEquals(500, send("GET", "/p/lit/x", "Accept: text/plain").status());
    }

    /** JAX-RS 3.7.2 step 3: a request entity whose Content-Type does not match @Consumes is not dispatched. */
    @Test
    void unparseableContentTypeDoesNotMatchConsumes() throws Exception {
        Res res = send("PUT", "/r/echo", "Content-Type: garbage", "BODY=x");
        assertTrue(res.status() == 400 || res.status() == 415, res.status() + " " + res.text());
    }

    /** UriInfo.getPath(): the path relative to the base URI (http://host/), so without a leading slash. */
    @Test
    void uriInfoPathIsRelative() throws Exception {
        String body = send("GET", "/r/headers").text();
        assertTrue(body.endsWith("|/|r/headers|/r/headers"), body);
    }

    /** JAX-RS 3.7.2: the 405 Allow header lists the methods of the matched resource only. */
    @Test
    void allowListsTheMethodsOfTheMatchedResource() throws Exception {
        Res res = send("PATCH", "/p/lit", "Accept: text/plain;q=0", "Content-Type: text/plain", "BODY=b");
        assertEquals(405, res.status());
        assertFalse(res.header("Allow").contains("DELETE"), res.header("Allow"));
    }
}
