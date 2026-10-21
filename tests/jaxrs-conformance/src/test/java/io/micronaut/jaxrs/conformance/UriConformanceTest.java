package io.micronaut.jaxrs.conformance;

import jakarta.ws.rs.core.UriBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.Random;

@Timeout(90)
class UriConformanceTest {
    private static final long SEED = 0x4a41585253L;
    private static final String[] VALUES = {"", "a", "a/b", "/", "a+b", "a b", "%", "%2F", "%GG", "a;b", "a?b", "a#b", "é", "中", "😀", "a&b=c", "{x}", "a\\b"};

    @Test
    void seededTemplateExpansionAndComponents() {
        var random = new Random(SEED);
        var differences = new Oracles.Differences(Explainers::uris);
        for (int i = 0; i < 2500; i++) {
            String value = VALUES[random.nextInt(VALUES.length)];
            String other = VALUES[random.nextInt(VALUES.length)];
            int mode = random.nextInt(10);
            differences.compare("mode=" + mode, "value=" + value + ", other=" + other, () -> switch (mode) {
                case 0 -> UriBuilder.fromUri("http://example.test/{x}/end").build(value).toString();
                case 1 -> UriBuilder.fromUri("http://example.test/{x}/end").buildFromEncoded(value).toString();
                case 2 -> UriBuilder.fromUri("http://example.test/{x}/end").buildFromMap(Map.of("x", value), false).toString();
                case 3 -> UriBuilder.fromUri("http://example.test/").queryParam("q", value).build().toString();
                case 4 -> UriBuilder.fromUri("http://example.test/").segment(value).build().toString();
                case 5 -> UriBuilder.fromUri("http://example.test/").matrixParam("m", value).build().toString();
                case 6 -> UriBuilder.fromUri("http://example.test/{x}?q={x}#{x}").build(value).toString();
                case 7 -> UriBuilder.fromUri("http://example.test/{x:[a-z]{1,3}}/{x}").build(value).toString();
                case 8 -> UriBuilder.fromUri("http://example.test/?q=old&x=1").replaceQueryParam("q", value, other).build().toString();
                default -> UriBuilder.fromUri("http://example.test/{x}/{y}").resolveTemplateFromEncoded("x", value).build(other).toString();
            });
        }
        differences.verify(SEED);
    }
}
