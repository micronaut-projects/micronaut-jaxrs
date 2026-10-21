package io.micronaut.jaxrs.conformance;

import io.micronaut.jaxrs.common.multipart.JaxRsMultipartMessageBodyReader;
import io.micronaut.jaxrs.common.multipart.JaxRsMultipartMessageBodyWriter;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property tests of the multipart provider: random parts written then read back must be the same parts, and
 * randomly framed raw multipart entities (RFC 2046 5.1.1, RFC 7578) must parse to the parts an independent
 * serializer put in them. There is no Jersey multipart module in the cache, so the oracle is the RFC.
 */
@Timeout(120)
class MultipartConformanceTest {
    private static final Type LIST = new GenericType<List<EntityPart>>() { }.getType();
    private static final String BCHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ'()+_,-./:=? ";
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

    record Part(String name, String fileName, String mediaType, byte[] content) {
        @Override
        public String toString() {
            return "Part[name=" + escape(name) + ", fileName=" + (fileName == null ? null : escape(fileName)) + ", mediaType=" + mediaType
                + ", content=" + (content.length > 80 ? "<" + content.length + " bytes>" : escape(new String(content, StandardCharsets.ISO_8859_1))) + "]";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Part p && name.equals(p.name) && java.util.Objects.equals(fileName, p.fileName)
                && MediaType.valueOf(mediaType).equals(MediaType.valueOf(p.mediaType)) && Arrays.equals(content, p.content);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c < 0x20 || c > 0x7e) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String boundary(Random r) {
        int length = 1 + r.nextInt(r.nextBoolean() ? 8 : 70);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            char c = BCHARS.charAt(r.nextInt(BCHARS.length()));
            // the last character is not a space
            sb.append(i == length - 1 && c == ' ' ? 'x' : c);
        }
        return sb.toString();
    }

    private static String name(Random r) {
        String[] names = {"a", "file", "a b", "a;b", "a\"b", "a\\b", "été", "a=b", "a,b", "a\\\"b", "x; name=y", "中", "a'b", "A"};
        return names[r.nextInt(names.length)];
    }

    private static byte[] content(Random r, String boundary) {
        String[] pieces = {"", "x", "\r\n", "\n", "\r", "--", "-", "--" + boundary.substring(0, Math.max(0, boundary.length() - 1)),
            "\r\n--" + boundary + "x", "\r\n-" + boundary, "--" + boundary + " x", "\r\n\r\n", "Content-Type: a/b\r\n", "\u0000", "ÿ"};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int n = r.nextInt(6);
        for (int i = 0; i < n; i++) {
            out.writeBytes(pieces[r.nextInt(pieces.length)].getBytes(StandardCharsets.ISO_8859_1));
        }
        if (r.nextInt(10) == 0) {
            byte[] large = new byte[r.nextInt(100_000)];
            r.nextBytes(large);
            out.writeBytes(large);
        }
        byte[] content = out.toByteArray();
        // RFC 2046: the boundary must not appear in the content as a delimiter line
        String text = "\r\n" + new String(content, StandardCharsets.ISO_8859_1) + "\r\n";
        String delimiter = "\r\n--" + boundary;
        for (int i = text.indexOf(delimiter); i >= 0; i = text.indexOf(delimiter, i + 1)) {
            String rest = text.substring(i + delimiter.length());
            if (rest.startsWith("--") || rest.replaceFirst("^[ \t]*", "").startsWith("\r\n")) {
                return content(r, boundary);
            }
        }
        return content;
    }

    private static List<Part> read(String contentType, byte[] entity) throws Exception {
        var reader = new JaxRsMultipartMessageBodyReader();
        @SuppressWarnings({"unchecked", "rawtypes"})
        List<EntityPart> parts = reader.readFrom((Class) List.class, LIST, new Annotation[0], MediaType.valueOf(contentType),
            new MultivaluedHashMap<>(), new ByteArrayInputStream(entity));
        List<Part> out = new ArrayList<>();
        for (EntityPart p : parts) {
            out.add(new Part(p.getName(), p.getFileName().orElse(null), p.getMediaType().toString(), p.getContent().readAllBytes()));
        }
        return out;
    }

    @Test
    void seededWriteThenRead() throws Exception {
        long seed = 0x4d554c54L;
        Random r = new Random(seed);
        Map<String, String> failures = new LinkedHashMap<>();
        int cases = 600;
        int known = 0;
        for (int i = 0; i < cases; i++) {
            String boundary = boundary(r);
            List<Part> parts = new ArrayList<>();
            List<EntityPart> entityParts = new ArrayList<>();
            int n = r.nextInt(4);
            for (int k = 0; k < n; k++) {
                String name = name(r);
                String fileName = r.nextBoolean() ? null : name(r) + ".txt";
                String mediaType = new String[]{"text/plain", "application/octet-stream", "application/json;charset=UTF-8", "image/png"}[r.nextInt(4)];
                byte[] content = content(r, boundary);
                parts.add(new Part(name, fileName, mediaType, content));
            }
            String category = "write-read";
            String input = "boundary=" + escape(boundary) + " parts=" + parts;
            try {
                for (Part p : parts) {
                    EntityPart.Builder b = EntityPart.withName(p.name).mediaType(p.mediaType).content(p.content);
                    if (p.fileName != null) {
                        b.fileName(p.fileName);
                    }
                    entityParts.add(b.build());
                }
                String contentType = "multipart/form-data; boundary=\"" + boundary + "\"";
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                new JaxRsMultipartMessageBodyWriter().writeTo(entityParts, List.class, LIST, new Annotation[0], MediaType.valueOf(contentType),
                    new MultivaluedHashMap<>(), out);
                List<Part> back = read(contentType, out.toByteArray());
                if (!back.equals(parts) && parts.stream().anyMatch(p -> p.name.contains(";") || p.fileName != null && p.fileName.contains(";"))) {
                    // BUG: Content-Disposition parameters split on ';' inside quoted strings
                    // (ClientFindingsTest.multipartDispositionParametersAreQuotedStrings)
                    known++;
                } else if (!back.equals(parts)) {
                    category = "write-read mismatch " + differingField(parts, back);
                    failures.putIfAbsent(category, input + "\n    read back " + back);
                }
            } catch (Exception e) {
                failures.putIfAbsent(category + " " + e.getClass().getSimpleName(), input + "\n    " + e);
            }
        }
        System.out.println("known Content-Disposition quoting mismatches: " + known);
        report(seed, cases, failures);
    }

    private static String differingField(List<Part> expected, List<Part> actual) {
        if (expected.size() != actual.size()) {
            return "count";
        }
        for (int i = 0; i < expected.size(); i++) {
            Part a = expected.get(i);
            Part b = actual.get(i);
            if (!a.name.equals(b.name)) {
                return "name";
            }
            if (!java.util.Objects.equals(a.fileName, b.fileName)) {
                return "fileName";
            }
            if (!Arrays.equals(a.content, b.content)) {
                return "content";
            }
        }
        return "mediaType";
    }

    /**
     * An independent serializer with the freedoms of RFC 2046: a preamble, an epilogue, transport padding after
     * the delimiters, any case for the header names, the parameters in any order, and a quoted boundary.
     */
    @Test
    void seededRawEntities() throws Exception {
        long seed = 0x52415721L;
        Random r = new Random(seed);
        Map<String, String> failures = new LinkedHashMap<>();
        int cases = 1500;
        String[] safeNames = {"a", "file", "a b", "é", "a=b", "a,b"};
        for (int i = 0; i < cases; i++) {
            String boundary = boundary(r);
            int n = r.nextInt(4);
            List<Part> parts = new ArrayList<>();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            List<String> features = new ArrayList<>();
            if (r.nextInt(4) == 0) {
                String preamble = new String[]{"preamble\r\n", "\r\n", "x--" + boundary + "\r\n", "--" + boundary + "x\r\n"}[r.nextInt(4)];
                features.add("preamble=" + escape(preamble));
                out.writeBytes(preamble.getBytes(StandardCharsets.ISO_8859_1));
            }
            for (int k = 0; k < n; k++) {
                String name = safeNames[r.nextInt(safeNames.length)];
                String fileName = r.nextBoolean() ? null : safeNames[r.nextInt(safeNames.length)];
                String mediaType = r.nextInt(3) == 0 ? null : new String[]{"text/plain", "application/json", "image/png;x=y"}[r.nextInt(3)];
                byte[] content = content(r, boundary);
                parts.add(new Part(name, fileName, mediaType == null ? "text/plain" : mediaType, content));
                out.writeBytes(("--" + boundary).getBytes(StandardCharsets.ISO_8859_1));
                if (r.nextInt(5) == 0) {
                    features.add("padding");
                    out.writeBytes(" \t ".getBytes(StandardCharsets.ISO_8859_1));
                }
                out.writeBytes("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                List<String> headers = new ArrayList<>();
                String cd = new String[]{"Content-Disposition", "content-disposition", "CONTENT-DISPOSITION"}[r.nextInt(3)];
                List<String> params = new ArrayList<>();
                params.add(r.nextBoolean() ? "name=\"" + name + "\"" : "NAME=\"" + name + "\"");
                if (fileName != null) {
                    params.add("filename=\"" + fileName + "\"");
                }
                if (r.nextBoolean()) {
                    java.util.Collections.reverse(params);
                    features.add("reversed-params");
                }
                headers.add(cd + ":" + (r.nextBoolean() ? " " : "") + "form-data; " + String.join(r.nextBoolean() ? "; " : ";", params));
                if (mediaType != null) {
                    headers.add((r.nextBoolean() ? "Content-Type" : "content-type") + ": " + mediaType);
                }
                if (r.nextInt(4) == 0) {
                    headers.add("X-Extra: v");
                }
                java.util.Collections.shuffle(headers, r);
                for (String h : headers) {
                    out.writeBytes((h + "\r\n").getBytes(StandardCharsets.UTF_8));
                }
                out.writeBytes("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.writeBytes(content);
                out.writeBytes("\r\n".getBytes(StandardCharsets.ISO_8859_1));
            }
            out.writeBytes(("--" + boundary + "--").getBytes(StandardCharsets.ISO_8859_1));
            if (r.nextInt(4) == 0) {
                String epilogue = new String[]{"", "\r\n", "\r\nepilogue\r\n--" + boundary + "\r\n", " \r\n"}[r.nextInt(4)];
                features.add("epilogue=" + escape(epilogue));
                out.writeBytes(epilogue.getBytes(StandardCharsets.ISO_8859_1));
            }
            boolean quotedBoundary = r.nextBoolean() || boundary.chars().anyMatch(c -> "'()+,/:=? ".indexOf(c) >= 0);
            String contentType = "multipart/form-data; boundary=" + (quotedBoundary ? "\"" + boundary + "\"" : boundary);
            String input = "contentType=" + escape(contentType) + " features=" + features + " parts=" + parts;
            if (n == 0) {
                // RFC 2046: a multipart body has one part at least; any outcome is fine
                continue;
            }
            try {
                List<Part> back = read(contentType, out.toByteArray());
                if (!back.equals(parts)) {
                    failures.putIfAbsent("raw mismatch " + differingField(parts, back) + " " + features.stream().map(f -> f.split("=")[0]).toList(),
                        input + "\n    read back " + back);
                }
            } catch (Exception e) {
                failures.putIfAbsent("raw " + e.getClass().getSimpleName() + " " + features.stream().map(f -> f.split("=")[0]).toList(),
                    input + "\n    " + e);
            }
        }
        report(seed, cases, failures);
    }

    private static void report(long seed, int cases, Map<String, String> failures) {
        StringBuilder sb = new StringBuilder("seed=" + seed + " cases=" + cases + " failure kinds=" + failures.size() + "\n");
        failures.forEach((k, v) -> sb.append("== ").append(k).append("\n    ").append(v).append('\n'));
        System.out.println(sb);
        assertTrue(failures.isEmpty(), sb::toString);
    }
}
