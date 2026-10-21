package io.micronaut.jaxrs.conformance;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.glassfish.jersey.internal.MapPropertiesDelegate;
import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ContainerResponse;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * The two servers under comparison: an embedded Micronaut server, reached over a raw socket so the request
 * target is sent byte for byte, and Jersey's in-memory {@link ApplicationHandler}.
 */
final class Servers implements AutoCloseable {

    record Req(String method, String target, List<String[]> headers, byte[] body) {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(method).append(' ').append(target);
            for (String[] h : headers) {
                sb.append(" [").append(h[0]).append(": ").append(h[1]).append(']');
            }
            if (body != null) {
                sb.append(" body(").append(body.length).append(")=")
                    .append(body.length > 60 ? "<large>" : new String(body, StandardCharsets.ISO_8859_1));
            }
            return sb.toString();
        }
    }

    record Res(int status, Map<String, List<String>> headers, byte[] body) {
        String header(String name) {
            List<String> v = headers.get(name.toLowerCase(Locale.ROOT));
            return v == null ? null : String.join(",", v);
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    final ApplicationContext context;
    final EmbeddedServer server;
    final ApplicationHandler jersey;

    Servers(List<Class<?>> classes) {
        context = ApplicationContext.run(Map.of("micronaut.server.port", -1));
        server = context.getBean(EmbeddedServer.class).start();
        ResourceConfig config = new ResourceConfig();
        classes.forEach(config::register);
        config.property(ServerProperties.WADL_FEATURE_DISABLE, true);
        jersey = new ApplicationHandler(config);
    }

    @Override
    public void close() {
        context.close();
    }

    Res micronaut(Req req) throws IOException {
        try (Socket socket = new Socket("localhost", server.getPort())) {
            socket.setSoTimeout(20000);
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            write(head, req.method + " " + req.target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n");
            for (String[] h : req.headers) {
                write(head, h[0] + ": " + h[1] + "\r\n");
            }
            if (req.body != null) {
                write(head, "Content-Length: " + req.body.length + "\r\n");
            }
            write(head, "\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(head.toByteArray());
            if (req.body != null) {
                out.write(req.body);
            }
            out.flush();
            return parse(socket.getInputStream().readAllBytes(), req.method.equals("HEAD"));
        }
    }

    Res jersey(Req req) throws Exception {
        URI requestUri;
        try {
            requestUri = new URI("http://localhost" + req.target);
        } catch (Exception e) {
            return new Res(-400, Map.of(), ("jersey: invalid request URI " + e.getMessage()).getBytes());
        }
        ContainerRequest request = new ContainerRequest(URI.create("http://localhost/"), requestUri, req.method, null,
            new MapPropertiesDelegate(), jersey.getConfiguration());
        for (String[] h : req.headers) {
            request.header(h[0], h[1]);
        }
        if (req.body != null) {
            request.header("Content-Length", String.valueOf(req.body.length));
            request.setEntityStream(new ByteArrayInputStream(req.body));
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ContainerResponse response;
        // the resources and Jersey use the RuntimeDelegate of Jersey, not the Micronaut one found first
        jakarta.ws.rs.ext.RuntimeDelegate previous = jakarta.ws.rs.ext.RuntimeDelegate.getInstance();
        jakarta.ws.rs.ext.RuntimeDelegate.setInstance(Oracles.JERSEY);
        try {
            response = jersey.apply(request, body).get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            // the in-memory handler propagates what a container turns into an error response
            Throwable cause = e.getCause();
            int status = cause instanceof IllegalArgumentException || String.valueOf(cause).contains("Malformed") ? -400 : -500;
            return new Res(status, Map.of(), ("jersey threw " + cause).getBytes());
        } finally {
            jakarta.ws.rs.ext.RuntimeDelegate.setInstance(previous);
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        response.getStringHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), new ArrayList<>(v)));
        return new Res(response.getStatus(), headers, body.toByteArray());
    }

    private static void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static Res parse(byte[] raw, boolean head) throws IOException {
        int end = indexOf(raw, "\r\n\r\n".getBytes(), 0);
        if (end < 0) {
            return new Res(-1, Map.of(), raw);
        }
        String[] lines = new String(raw, 0, end, StandardCharsets.ISO_8859_1).split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            headers.computeIfAbsent(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                .add(lines[i].substring(colon + 1).trim());
        }
        byte[] rest = java.util.Arrays.copyOfRange(raw, end + 4, raw.length);
        List<String> te = headers.get("transfer-encoding");
        if (!head && te != null && te.toString().contains("chunked")) {
            rest = dechunk(rest);
        }
        return new Res(status, headers, head ? new byte[0] : rest);
    }

    private static byte[] dechunk(byte[] in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream s = new ByteArrayInputStream(in);
        while (true) {
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = s.read()) != '\n') {
                if (c < 0) {
                    return out.toByteArray();
                }
                if (c != '\r') {
                    line.append((char) c);
                }
            }
            int size = Integer.parseInt(line.toString().split(";")[0].trim(), 16);
            if (size == 0) {
                return out.toByteArray();
            }
            out.write(s.readNBytes(size));
            s.read();
            s.read();
        }
    }

    private static int indexOf(byte[] a, byte[] b, int from) {
        outer:
        for (int i = from; i <= a.length - b.length; i++) {
            for (int j = 0; j < b.length; j++) {
                if (a[i + j] != b[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
