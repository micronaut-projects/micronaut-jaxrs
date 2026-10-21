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

    /** HttpHeaders.getAcceptableMediaTypes(): RFC 9110 OWS around the list elements is not part of the types. */
    @Test
    void acceptableMediaTypesAreTrimmed() throws Exception {
        String body = send("GET", "/r/headers", "Accept: application/json, text/*").text();
        assertTrue(body.startsWith("headers:[<application>/<json>{}, <text>/<*>{}]"), body);
    }

    /** RFC 9110 15.5.1 / 15.6.1: an unparseable Accept header is a client error, not a server error. */
    @Test
    void invalidAcceptIsNotAServerError() throws Exception {
        Res res = send("GET", "/r/headers", "Accept: foo");
        assertTrue(res.status() < 500, res.status() + " " + res.text());
    }

    /** The default Accept header of java.net.HttpURLConnection, with OWS before the parameters of each element. */
    @Test
    void jdkDefaultAcceptHeader() throws Exception {
        Res res = send("GET", "/r/headers", "Accept: text/html, image/gif, image/jpeg, *; q=.2, */*; q=.2");
        assertEquals(200, res.status(), res.text());
    }

    /** HttpHeaders.getAcceptableLanguages(): the wildcard is a Locale whose language is "*". */
    @Test
    void wildcardLanguageWithQuality() throws Exception {
        String body = send("GET", "/r/headers", "Accept-Language: *;q=0.1, fr").text();
        assertTrue(body.contains("|[fr, *]|"), body);
    }

    /** JAX-RS 3.7.2: the 405 Allow header lists the methods of the matched resource only. */
    @Test
    void allowListsTheMethodsOfTheMatchedResource() throws Exception {
        Res res = send("PATCH", "/p/lit", "Accept: text/plain;q=0", "Content-Type: text/plain", "BODY=b");
        assertEquals(405, res.status());
        assertFalse(res.header("Allow").contains("DELETE"), res.header("Allow"));
    }
}
