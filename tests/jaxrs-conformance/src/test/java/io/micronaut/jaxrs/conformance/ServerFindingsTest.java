package io.micronaut.jaxrs.conformance;

import io.micronaut.jaxrs.conformance.Servers.Req;
import io.micronaut.jaxrs.conformance.Servers.Res;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.util.ArrayList;
import java.util.List;

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
}
