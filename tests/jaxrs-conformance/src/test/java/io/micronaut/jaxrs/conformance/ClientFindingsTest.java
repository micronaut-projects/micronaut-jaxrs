package io.micronaut.jaxrs.conformance;

import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

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
}
