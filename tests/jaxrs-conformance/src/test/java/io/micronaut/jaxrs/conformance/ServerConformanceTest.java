package io.micronaut.jaxrs.conformance;

import io.micronaut.jaxrs.conformance.Servers.Req;
import io.micronaut.jaxrs.conformance.Servers.Res;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sends the same randomly generated requests to Micronaut (over HTTP) and to Jersey (in memory) and compares
 * the status, the media type, the Allow header and the bodies of successful responses.
 */
@Timeout(150)
class ServerConformanceTest {
    private static Servers servers;

    static final String[] SEGMENTS = {"123", "abc", "a-b", "x", "xy", "xabcy", "xA1y", "a%2Fb", "a+b", "a%20b", "%GG", "a%2", "lit", "LIT",
        "a%3Bb", "%C3%A9", "..", ".", "a;k=1", "a;k", "a;k=1;k=2", "a;k=x%20y", "0-", "-0", "a-b-c", "~!$&'()*,", "a%25b", "a@b:c", "a%00b"};
    static final String[] QUERY = {"x=1", "x=a+b", "x=a%2Bb", "x=%GG", "x", "x=", "y=", "y", "x=1&x=2", "=v", "x=%E2%82%AC", "x=a=b",
        "x=1&&y=2", "X=1", "x=a%26b", "y=a+b&y=c", "x=%", "x=;"};
    static final String[] ACCEPT = {"*/*", "text/plain", "application/json", "text/*", "application/xml;q=0.9, text/plain;q=0.5",
        "text/html;q=0, */*;q=0.1", "application/vnd.x+json", "application/*", "TEXT/PLAIN", "text/plain;q=0", "foo",
        "text/plain;q=1.5", "application/json;q=0.5, text/html;q=0.5", "text/plain, application/json", "*/*;q=0.5, text/html",
        "application/xml, application/json;q=0.9", "image/png", "text/*;q=0.3, text/html;q=0.7", "*", "text/plain;level=1",
        "application/json;q=0.0001", "application/json;q=0.0", "text/html; q=0.5 , text/plain ;q=0.6"};
    static final String[] CONTENT_TYPE = {"text/plain", "text/plain; charset=UTF-8", "application/json", "application/vnd.a+json",
        "application/octet-stream", "application/x-www-form-urlencoded", "application/xml", "garbage", "TEXT/PLAIN",
        "application/json; charset=ISO-8859-1", "text/html", "application/+json", "application/x-www-form-urlencoded;charset=UTF-8",
        "*/*", "text/*"};

    @BeforeAll
    static void start() {
        servers = new Servers(ServerResources.CLASSES);
    }

    @AfterAll
    static void stop() {
        servers.close();
    }

    private static String pick(Random r, String[] a) {
        return a[r.nextInt(a.length)];
    }

    private static String randomPath(Random r) {
        String s = pick(r, SEGMENTS);
        String s2 = pick(r, SEGMENTS);
        return switch (r.nextInt(14)) {
            case 0, 1 -> "/r/t/" + s;
            case 2 -> "/r/t/" + s + "/z";
            case 3 -> "/r/rest/" + s + "/" + s2;
            case 4 -> "/r/m/" + s + (r.nextBoolean() ? ";a=" + pick(r, new String[]{"1", "x%20y", "a+b", "", "%2F"}) : "")
                + (r.nextBoolean() ? ";b=1;b=2" : "");
            case 5 -> "/r/q" + (r.nextBoolean() ? "/" : "") + "?" + pick(r, QUERY) + (r.nextBoolean() ? "&" + pick(r, QUERY) : "");
            case 6 -> "/r/loc/" + s;
            case 7 -> "/r/loc/" + s + "/" + s2;
            case 8 -> "/r/loc/" + s + "/deeper" + (r.nextBoolean() ? "/" + s2 : "");
            case 9 -> "/p/" + s;
            case 10 -> "/p/lit" + (r.nextBoolean() ? "/" + s : "");
            case 11 -> pick(r, new String[]{"/r/t/", "/r/t", "/r//t/abc", "/r/t/abc/", "//r/t/abc", "/r/./t/abc", "/r/t/../t/abc", "/R/t/abc",
                "/r/neg", "/r/wild", "/r/none", "/r/headers", "/r/variant", "/r/rest/", "/r/m", "/r/q", "/r/loc", "/r/loc/", "/p", "/p/", "/"});
            default -> "/r/t/" + s + (r.nextBoolean() ? "?" + pick(r, QUERY) : "");
        };
    }

    @Test
    void seededRoutingAndParameters() throws Exception {
        run(0x524f555445L, 1500, r -> {
            List<String[]> headers = new ArrayList<>();
            if (r.nextInt(3) == 0) {
                headers.add(new String[]{"Accept", pick(r, ACCEPT)});
            }
            String method = r.nextInt(6) == 0 ? pick(r, new String[]{"HEAD", "OPTIONS", "DELETE", "PUT", "POST", "PATCH"}) : "GET";
            byte[] body = method.equals("PUT") || method.equals("POST") || method.equals("PATCH") ? "b".getBytes() : null;
            if (body != null) {
                headers.add(new String[]{"Content-Type", "text/plain"});
            }
            return new Req(method, randomPath(r), headers, body);
        });
    }

    @Test
    void seededNegotiation() throws Exception {
        run(0x4e45474fL, 1000, r -> {
            List<String[]> headers = new ArrayList<>();
            int n = r.nextInt(3);
            if (n > 0) {
                StringBuilder accept = new StringBuilder(pick(r, ACCEPT));
                for (int i = 1; i < n; i++) {
                    accept.append(", ").append(pick(r, ACCEPT));
                }
                headers.add(new String[]{"Accept", accept.toString()});
            }
            if (r.nextInt(3) == 0) {
                headers.add(new String[]{"Accept-Language", pick(r, new String[]{"de", "en;q=0.5, de;q=0.9", "fr", "*", "de;q=0", "en-US", "*;q=0.1, fr"})});
            }
            if (r.nextInt(3) == 0) {
                headers.add(new String[]{"Accept-Encoding", pick(r, new String[]{"gzip", "identity;q=0, gzip", "br", "*", "gzip;q=0", "*;q=0"})});
            }
            String path = pick(r, new String[]{"/r/neg", "/r/wild", "/r/none", "/r/t/abc", "/r/variant", "/r/headers", "/p/lit/x", "/r/echo"});
            String method = r.nextInt(5) == 0 ? "HEAD" : "GET";
            return new Req(method, path, headers, null);
        });
    }

    @Test
    void seededBodiesAndConsumes() throws Exception {
        run(0x424f4459L, 1000, r -> {
            List<String[]> headers = new ArrayList<>();
            if (r.nextInt(5) != 0) {
                headers.add(new String[]{"Content-Type", pick(r, CONTENT_TYPE)});
            }
            if (r.nextInt(3) == 0) {
                headers.add(new String[]{"Accept", pick(r, ACCEPT)});
            }
            byte[] body = switch (r.nextInt(6)) {
                case 0 -> new byte[0];
                case 1 -> {
                    byte[] b = new byte[r.nextInt(200_000)];
                    for (int i = 0; i < b.length; i++) {
                        b[i] = (byte) ('a' + r.nextInt(26));
                    }
                    yield b;
                }
                case 2 -> pick(r, new String[]{"x=1&y=2", "x=a+b&x=%2B", "x=%GG", "x", "=", "&&", "x=1;y=2", "y=", "x=%E2%82%AC&y=%"})
                    .getBytes(StandardCharsets.ISO_8859_1);
                case 3 -> "héllo".getBytes(StandardCharsets.UTF_8);
                case 4 -> null;
                default -> "{\"a\":1}".getBytes();
            };
            String method = pick(r, new String[]{"POST", "POST", "PUT", "PATCH", "GET"});
            String path = pick(r, new String[]{"/r/echo", "/r/echo", "/r/form", "/r/formmap", "/r/loc/a", "/r/cond", "/r/q?x=1"});
            if (method.equals("GET") && body != null && body.length > 0 && r.nextBoolean()) {
                body = null;
            }
            return new Req(method, path, headers, body);
        });
    }

    @Test
    void seededConditionalRequests() throws Exception {
        String[] tags = {"\"v1\"", "W/\"v1\"", "*", "\"v2\"", "\"v2\", \"v1\"", "W/\"v2\", W/\"v1\"", "v1", "\"v1", ""};
        String[] dates = {"Sun, 01 Jan 2023 00:00:00 GMT", "Sat, 31 Dec 2022 23:59:59 GMT", "Sun, 01 Jan 2023 00:00:01 GMT",
            "Mon, 01 Jan 2024 00:00:00 GMT", "Sunday, 01-Jan-23 00:00:00 GMT", "Sun Jan  1 00:00:00 2023", "garbage",
            "Sun, 01 Jan 2023 00:00:00 +0000", "Wed, 01 Jan 2200 00:00:00 GMT"};
        run(0x434f4e44L, 1500, r -> {
            List<String[]> headers = new ArrayList<>();
            if (r.nextInt(2) == 0) {
                headers.add(new String[]{"If-Match", pick(r, tags)});
            }
            if (r.nextInt(2) == 0) {
                headers.add(new String[]{"If-None-Match", pick(r, tags)});
            }
            if (r.nextInt(2) == 0) {
                headers.add(new String[]{"If-Modified-Since", pick(r, dates)});
            }
            if (r.nextInt(2) == 0) {
                headers.add(new String[]{"If-Unmodified-Since", pick(r, dates)});
            }
            String path = pick(r, new String[]{"/r/cond", "/r/cond", "/r/cond/weak", "/r/cond/date", "/r/cond/none"});
            String method = path.endsWith("none") ? "DELETE" : r.nextInt(3) == 0 ? "PUT" : r.nextInt(4) == 0 ? "HEAD" : "GET";
            byte[] body = method.equals("PUT") ? "b".getBytes() : null;
            if (body != null) {
                headers.add(new String[]{"Content-Type", "text/plain"});
            }
            return new Req(method, path, headers, body);
        });
    }

    private void run(long seed, int count, Function<Random, Req> generator) throws Exception {
        Random random = new Random(seed);
        Map<String, List<String>> examples = new LinkedHashMap<>();
        int mismatches = 0;
        for (int i = 0; i < count; i++) {
            Req req = generator.apply(random);
            Res ours = servers.micronaut(req);
            Res theirs = servers.jersey(req);
            List<String> diffs = compare(req, ours, theirs);
            if (!diffs.isEmpty()) {
                mismatches++;
                String label = explain(req, ours, theirs, String.join(" ", diffs));
                String key = label != null ? label : String.join(",", diffs.stream().map(d -> d.substring(0, d.indexOf('='))).toList());
                examples.computeIfAbsent(key, k -> new ArrayList<>());
                if (examples.get(key).size() < 6) {
                    examples.get(key).add("#" + i + " " + req + "\n      " + String.join("\n      ", diffs));
                }
            }
        }
        StringBuilder report = new StringBuilder("seed=" + seed + " cases=" + count + " mismatches=" + mismatches + "\n");
        examples.forEach((k, v) -> report.append("== ").append(k).append(" (").append(v.size()).append(" shown)\n    ")
            .append(String.join("\n    ", v)).append('\n'));
        System.out.println(report);
        assertTrue(examples.keySet().stream().allMatch(k -> Arrays.stream(k.split(",")).allMatch(d -> d.startsWith("info-") || d.startsWith("KNOWN") || d.startsWith("BUG") || d.startsWith("IMPL") || d.startsWith("JERSEY"))),
            report::toString);
    }

    private static final java.time.format.DateTimeFormatter RFC850 = new java.time.format.DateTimeFormatterBuilder()
        .appendPattern("EEEE, dd-MMM-").appendValueReduced(java.time.temporal.ChronoField.YEAR, 2, 2, 1970)
        .appendPattern(" HH:mm:ss 'GMT'").toFormatter(Locale.US).withZone(java.time.ZoneOffset.UTC);
    private static final java.time.format.DateTimeFormatter ASCTIME = java.time.format.DateTimeFormatter
        .ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.US).withZone(java.time.ZoneOffset.UTC);
    private static final java.time.format.DateTimeFormatter IMF = java.time.format.DateTimeFormatter
        .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(java.time.ZoneOffset.UTC);

    /** RFC 9110 5.6.7: the three formats a recipient must accept; null when invalid (the field is then ignored). */
    static Long httpDate(String value) {
        if (value == null) {
            return null;
        }
        for (var f : List.of(IMF, RFC850, ASCTIME)) {
            try {
                return java.time.ZonedDateTime.parse(value, f).toEpochSecond();
            } catch (Exception e) {
                // next
            }
        }
        return null;
    }

    /** The entity tags of a field, "*" as itself; null when one is malformed. */
    static List<String> tags(String value) {
        List<String> out = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return out;
        }
        if (value.trim().equals("*")) {
            return List.of("*");
        }
        var m = java.util.regex.Pattern.compile("\\s*(W/)?\"([^\"]*)\"\\s*(,|$)").matcher(value);
        int pos = 0;
        while (pos < value.length()) {
            if (!m.find(pos) || m.start() != pos) {
                return null;
            }
            out.add((m.group(1) == null ? "" : "W/") + m.group(2));
            pos = m.end();
        }
        return out;
    }

    /**
     * RFC 9110 13.2.2 applied to the conditional resources of {@link ServerResources}: the status the request should get,
     * null when a tag list is malformed.
     */
    static Integer preconditionOracle(Req req, String path) {
        boolean read = req.method().equals("GET") || req.method().equals("HEAD");
        int ok = req.method().equals("DELETE") ? 204 : 200;
        List<String> ifMatch = tags(header(req, "If-Match"));
        List<String> ifNoneMatch = tags(header(req, "If-None-Match"));
        if (ifMatch == null || ifNoneMatch == null) {
            return null;
        }
        boolean hasIfMatch = header(req, "If-Match") != null;
        boolean hasIfNoneMatch = header(req, "If-None-Match") != null;
        if (path.equals("/r/cond/none")) {
            return hasIfMatch ? 412 : ok;
        }
        String etag = path.equals("/r/cond") ? "v1" : path.equals("/r/cond/weak") ? "W/v1" : null;
        boolean dates = !path.equals("/r/cond/weak");
        long lastModified = ServerResources.LAST_MODIFIED.getTime() / 1000;
        if (etag != null && hasIfMatch) {
            boolean strong = ifMatch.contains("*") || !etag.startsWith("W/") && ifMatch.contains(etag);
            if (!strong) {
                return 412;
            }
        } else if (dates) {
            Long ius = httpDate(header(req, "If-Unmodified-Since"));
            if (ius != null && lastModified > ius) {
                return 412;
            }
        }
        if (etag != null && hasIfNoneMatch) {
            String opaque = etag.replace("W/", "");
            boolean weak = ifNoneMatch.contains("*") || ifNoneMatch.stream().anyMatch(t -> t.replace("W/", "").equals(opaque));
            if (weak) {
                return read ? 304 : 412;
            }
        } else if (dates && read) {
            Long ims = httpDate(header(req, "If-Modified-Since"));
            if (ims != null && lastModified <= ims) {
                return 304;
            }
        }
        return ok;
    }

    private static String header(Req req, String name) {
        for (String[] h : req.headers()) {
            if (h[0].equalsIgnoreCase(name)) {
                return h[1];
            }
        }
        return null;
    }

    /**
     * The triage of a difference: "BUG" labels are confirmed Micronaut bugs reproduced in ServerFindingsTest,
     * "IMPL" implementation-defined differences, "JERSEY" Jersey (or in-memory container) deviations. Labels have no comma.
     */
    static String explain(Req req, Res ours, Res theirs, String diffs) {
        String target = req.target();
        String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
        String query = target.contains("?") ? target.substring(target.indexOf('?') + 1) : "";
        String accept = String.valueOf(header(req, "Accept"));
        String contentType = header(req, "Content-Type");
        int a = ours.status();
        int b = theirs.status();
        if (diffs.startsWith("jersey-rejects") && path.contains(";")) {
            return "JERSEY the in-memory handler throws for a matrix parameter after a locator";
        }
        if (diffs.startsWith("jersey-throws") && path.equals("/r/formmap")) {
            return "IMPL MultivaluedMap form without an entity is empty (Jersey NPE)";
        }
        if (diffs.startsWith("jersey-throws") && (contentType == null || !contentType.toLowerCase().startsWith("application/x-www-form"))) {
            return "IMPL @FormParam without a form entity is empty (Jersey IllegalStateException)";
        }
        if (a == 406 && b == 200 && (path.equals("/r/none") || path.equals("/r/variant")) && accept.contains("text/*")) {
            return "IMPL no @Produces and Accept text/*: 406 per JAX-RS 3.8 (M not concrete) - Jersey's String writer offers text/plain";
        }
        if (a == 200 && b == 406 && path.equals("/r/wild")) {
            return "JERSEY sorts the candidate types by q before specificity (JAX-RS 3.8 step 6: specificity first)";
        }
        if (b == 400 && accept.matches(".*q=\\d\\.\\d{4,}.*")) {
            return "IMPL lenient: a q-value with more than 3 decimals is accepted (Jersey 400)";
        }
        if (diffs.startsWith("body=") && path.startsWith("/r/rest/") && path.contains(";")
            && ours.text().replace("|/r/rest/", "|r/rest/").replaceAll("\\|\\[[^|]*\\]$", "")
            .equals(theirs.text().replaceAll("\\|\\[[^|]*\\]$", ""))) {
            return "JERSEY getMatchedURIs() truncated when the path has matrix parameters";
        }
        if (a == 406 && (b == 200 || b == 500 && path.startsWith("/p/lit/")) && accept.matches(".*q=0(\\.0*)?(\\D.*|$)")) {
            return "JERSEY ignores q=0 in Accept (RFC 9110 12.4.2: not acceptable)";
        }
        if (a == 200 && b == 406 && path.equals("/r/variant") && header(req, "Accept-Encoding") != null
            && !header(req, "Accept-Encoding").toLowerCase(Locale.ROOT).matches(".*(identity|\\*)\\s*;\\s*q=0(\\.0*)?\\s*(,.*|;.*)?")) {
            return "JERSEY selectVariant rejects the identity variant when Accept-Encoding does not list it (RFC 9110 12.5.3: acceptable unless excluded)";
        }
        if (a == 406 && b == 200 && path.equals("/r/variant") && String.valueOf(header(req, "Accept-Encoding")).matches(".*\\*\\s*;\\s*q=0(\\.0*)?\\s*(,.*|;.*)?")
            && !header(req, "Accept-Encoding").toLowerCase(Locale.ROOT).contains("identity")) {
            return "JERSEY selectVariant accepts the identity variant that '*;q=0' excludes (RFC 9110 12.5.3)";
        }
        if (a == 200 && b == 406 && path.equals("/r/variant") && req.method().equals("HEAD")) {
            return "IMPL selectVariant tie-break between equally acceptable variants";
        }
        if (a == 406 && b == 200 && path.equals("/r/variant") && String.valueOf(header(req, "Accept-Language")).contains("q=0")) {
            return "IMPL selectVariant: a language the Accept-Language header does not list is not acceptable (Jersey accepts it)";
        }
        if (a == 415 && b == 400 && "garbage".equals(contentType)) {
            return "IMPL an unparseable Content-Type matches no @Consumes: 415 (Jersey 400)";
        }
        if ((a / 100 == 2 || a == 406 || a == 500 && path.startsWith("/p/lit/")) && b == 400 && (accept.contains("q=1.5") || "garbage".equals(contentType))) {
            return "IMPL lenient: an invalid q-value or Content-Type is accepted - then 406 if nothing else is acceptable (Jersey 400)";
        }
        if (path.startsWith("/r/cond") && a != b) {
            Integer rfc = preconditionOracle(req, path);
            if (rfc == null) {
                return "IMPL malformed entity tag list (Jersey 400 - Micronaut lenient)";
            }
            if (rfc == a) {
                return "JERSEY precondition result differs from RFC 9110 13.2.2 (Micronaut agrees with the RFC)";
            }
            if (a == 412 && header(req, "If-Match") != null && header(req, "If-Unmodified-Since") != null) {
                return "KNOWN review item 12: If-Unmodified-Since applied together with If-Match";
            }
            return null;
        }
        if (path.startsWith("/r/cond")) {
            String ims = String.valueOf(header(req, "If-Modified-Since")) + String.valueOf(header(req, "If-Unmodified-Since"));
            if (b == 400 || a == 412 && header(req, "If-Match") != null && header(req, "If-Unmodified-Since") != null) {
                return "IMPL malformed entity tags or the known If-Match/If-Unmodified-Since issue (review item 12)";
            }
            if ("".equals(header(req, "If-None-Match")) || "".equals(header(req, "If-Match"))) {
                return "IMPL an empty If-None-Match/If-Match header (Jersey ignores it)";
            }
            if (ims.contains("+0000")) {
                return "IMPL a non IMF-fixdate '+0000' date";
            }
        }
        if (diffs.startsWith("allow=") && String.valueOf(ours.header("Allow")).contains("DELETE") && path.startsWith("/p/lit")) {
            return "BUG the 405 Allow header lists a method of another resource class (allowListsTheMethodsOfTheMatchedResource)";
        }
        if (diffs.startsWith("content-type=ours application/json") && (path.equals("/r/none") || path.equals("/r/variant"))) {
            return "IMPL without @Produces a String is application/json (a JSON writer accepts String); Jersey text/plain";
        }
        if (path.equals("/r/variant") && diffs.contains("variant:")) {
            return "IMPL selectVariant tie-break between equally acceptable variants";
        }
        if (diffs.startsWith("body=") && path.equals("/r/headers") && sameAcceptableTypes(ours.text(), theirs.text())) {
            return "IMPL HttpHeaders.getAcceptableMediaTypes(): order of equally weighted types (Jersey: most specific first)";
        }
        if (diffs.startsWith("body=") && path.equals("/r/headers") && accept.contains("foo")) {
            return "IMPL HttpHeaders.getAcceptableMediaTypes(): an invalid media range is ignored (Jersey: foo/*)";
        }
        if (diffs.startsWith("body=") && path.contains("+")) {
            return "BUG '+' in a path parameter is decoded to a space (plusInPathParamIsAPlus)";
        }
        if (diffs.startsWith("body=") && path.startsWith("/r/loc/")) {
            return "JERSEY @MatrixParam of a locator taken from the last request segment";
        }
        if ((a / 100 == 2 || a == 500 && path.startsWith("/p/lit/")) && b == 406 && accept.contains("foo")) {
            return "IMPL an invalid Accept media range is ignored by route negotiation (Jersey 406)";
        }
        if (a == 406 && (b / 100 == 2 || b == 500 && path.startsWith("/p/lit/")) && java.util.regex.Pattern.compile("(^|,)\\s*\\*\\s*(;|,|$)").matcher(accept).find()) {
            return "IMPL a bare '*' (invalid - sent by java.net.HttpURLConnection) is a wildcard alone but not within a list (Jersey: always)";
        }
        if (diffs.startsWith("content-type=") && (path.equals("/r/none") || path.equals("/r/variant"))) {
            return "IMPL no @Produces: choice among equally acceptable types for a String (Micronaut: Accept order - Jersey: text/plain)";
        }
        if (a == 406 && b == 415) {
            return "IMPL a request without Content-Type is */* for @Consumes (Jersey 415)";
        }
        if (diffs.startsWith("body=") && path.startsWith("/r/rest/") && ours.text().contains("=[null]")) {
            return "IMPL UriInfo path segment matrix parameter without value is [null] (Jersey [\"\"] - PathSegment of @PathParam is [\"\"])";
        }
        if (diffs.startsWith("body=") && (path.equals("/r/formmap") || path.equals("/r/form"))) {
            return "IMPL form key without '=' is an empty list (Jersey [null]); @Encoded form value re-encoded by Jersey";
        }
        if (diffs.startsWith("body=") && path.startsWith("/r/m/")) {
            return "IMPL a matrix parameter with an empty name is kept";
        }
        return null;
    }

    /** Whether two /r/headers bodies have the same acceptable types, in any order, and the same rest. */
    private static boolean sameAcceptableTypes(String ours, String theirs) {
        int a = ours.indexOf("]|");
        int b = theirs.indexOf("]|");
        if (a < 0 || b < 0 || !ours.substring(a).equals(theirs.substring(b))) {
            return false;
        }
        return new TreeSet<>(Arrays.asList(ours.substring(ours.indexOf('[') + 1, a).split(", ")))
            .equals(new TreeSet<>(Arrays.asList(theirs.substring(theirs.indexOf('[') + 1, b).split(", "))));
    }

    static List<String> compare(Req req, Res ours, Res theirs) {
        List<String> diffs = new ArrayList<>();
        if (theirs.status() == -400) {
            // Jersey cannot even represent the request: we expect a client error
            if (ours.status() < 400 || ours.status() >= 500) {
                diffs.add("jersey-rejects-status=ours " + ours.status() + " / jersey " + theirs.text() + " | ours body: " + abbreviate(ours.text()));
            }
            return diffs;
        }
        if (theirs.status() == -500) {
            if (ours.status() < 400) {
                diffs.add("jersey-throws-status=ours " + ours.status() + " / jersey " + theirs.text() + " | ours body: " + abbreviate(ours.text()));
            } else {
                diffs.add("info-jersey-throws=ours " + ours.status() + " / jersey " + theirs.text());
            }
            return diffs;
        }
        if (theirs.status() == 400 && ours.status() / 100 == 4) {
            if (ours.status() != 400) {
                diffs.add("info-jersey-400=ours " + ours.status());
            }
            return diffs;
        }
        if (ours.status() != theirs.status()) {
            diffs.add("status=ours " + ours.status() + " / jersey " + theirs.status() + " | ours body: " + abbreviate(ours.text()));
            return diffs;
        }
        boolean ok = ours.status() / 100 == 2;
        if (ok || ours.status() == 304 || ours.status() == 412) {
            String a = mediaType(ours.header("Content-Type"));
            String b = mediaType(theirs.header("Content-Type"));
            if (ok && !req.method().equals("OPTIONS") && ours.status() != 204 && !a.equals(b)) {
                diffs.add("content-type=ours " + ours.header("Content-Type") + " / jersey " + theirs.header("Content-Type"));
            }
            if (ours.status() != 304 && ok && !req.method().equals("HEAD") && !req.method().equals("OPTIONS")
                && !Arrays.equals(ours.body(), theirs.body())) {
                diffs.add("body=ours " + abbreviate(ours.text()) + " / jersey " + abbreviate(theirs.text()));
            }
            for (String h : new String[]{"ETag", "Last-Modified"}) {
                if (req.target().startsWith("/r/cond") && (ours.header(h) == null) != (theirs.header(h) == null)) {
                    diffs.add(h + "=ours " + ours.header(h) + " / jersey " + theirs.header(h));
                }
            }
        }
        if (ours.status() == 405 || req.method().equals("OPTIONS")) {
            String a = methods(ours.header("Allow"));
            String b = methods(theirs.header("Allow"));
            if (!a.equals(b)) {
                diffs.add("allow=ours " + ours.header("Allow") + " / jersey " + theirs.header("Allow"));
            }
        }
        return diffs;
    }

    static String abbreviate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    static String mediaType(String header) {
        if (header == null) {
            return "<none>";
        }
        String mt = header.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return mt;
    }

    static String methods(String allow) {
        if (allow == null) {
            return "<none>";
        }
        TreeSet<String> set = new TreeSet<>();
        for (String m : allow.split(",")) {
            String method = m.trim().toUpperCase(Locale.ROOT);
            // HEAD and OPTIONS are supported automatically: Jersey lists OPTIONS, Micronaut HEAD
            if (!method.equals("HEAD") && !method.equals("OPTIONS")) {
                set.add(method);
            }
        }
        return set.toString();
    }
}
