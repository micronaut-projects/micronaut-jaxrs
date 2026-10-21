package io.micronaut.jaxrs.conformance;

import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

@Timeout(90)
class HeaderConformanceTest {
    private static final long SEED = 0x484541444552L;

    @Test
    void seededQuotedCacheControlFieldsAndExtensions() {
        var random = new Random(SEED);
        var differences = new Oracles.Differences(Explainers::headers);
        String[] values = {"a", "a,b", "a=b", "a b", "a,b=c"};
        for (int i = 0; i < 500; i++) {
            String directive = List.of("private", "no-cache", "extension").get(random.nextInt(3));
            String header = directive + "=\"" + values[random.nextInt(values.length)] + "\", max-age=" + random.nextInt(100);
            differences.compare(directive, header, () -> cache(CacheControl.valueOf(header)));
        }
        differences.verify(SEED);
    }

    @Test
    void seededMediaTypeQuotedStringRoundTrips() {
        var random = new Random(SEED);
        var differences = new Oracles.Differences(Explainers::headers);
        String[] values = {"a", "a b", "a;b", "a,b", "a\"b", "a\\b", "a=b", "a/b"};
        for (int i = 0; i < 500; i++) {
            String value = values[random.nextInt(values.length)] + i;
            differences.compare("quoted-string", value, () -> {
                MediaType original = new MediaType("text", "plain", Map.of("x", value));
                MediaType parsed = MediaType.valueOf(original.toString());
                return parsed.getParameters().get("x");
            });
        }
        differences.verify(SEED);
    }

    @Test
    void seededHeaderParsing() {
        var random = new Random(SEED);
        var differences = new Oracles.Differences(Explainers::headers);
        String[] media = {"text/plain", " text/plain ", "text/plain ; charset=UTF-8", "TEXT/PLAIN;X=\"a;b\"", "text/pl@in", "text/pl[ain", "text/pl=ain", "text/pl ain", "text/plain;x=\"a\\\"b\"", "text/plain;x=\"a\\\\b\""};
        String[] tags = {"\"a\"", "W/\"a\"", "\"a,b\"", "a", "W/a", "\"", "", " \"a\" ", "w/\"a\""};
        for (int i = 0; i < 500; i++) {
            String m = media[random.nextInt(media.length)];
            String t = tags[random.nextInt(tags.length)];
            differences.compare("media:" + m, m, () -> {
                MediaType parsed = MediaType.valueOf(m);
                return List.of(parsed.getType().toLowerCase(), parsed.getSubtype().toLowerCase(), new TreeMap<>(parsed.getParameters()));
            });
            differences.compare("etag:" + t, t, () -> {
                EntityTag parsed = EntityTag.valueOf(t);
                return List.of(parsed.getValue(), parsed.isWeak());
            });
        }
        differences.verify(SEED);
    }

    private static Object cache(CacheControl value) {
        return List.of(value.isPrivate(), value.getPrivateFields(), value.isNoCache(), value.getNoCacheFields(),
            value.getMaxAge(), value.isNoStore(), value.isNoTransform(), new TreeMap<>(value.getCacheExtension()));
    }
}
