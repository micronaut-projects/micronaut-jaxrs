package io.micronaut.jaxrs.conformance;

import io.micronaut.jaxrs.conformance.Servers.Req;
import io.micronaut.jaxrs.conformance.Servers.Res;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/** Ad-hoc probe: -Dprobe.file=lines, each "METHOD target | Header: v | BODY=x". */
class ProbeTest {
    @Test
    void probe() throws Exception {
        String file = System.getenv("PROBE");
        org.junit.jupiter.api.Assumptions.assumeTrue(file != null);
        try (Servers s = new Servers(ServerResources.CLASSES)) {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(file))) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(" \\| ");
                String[] first = parts[0].split(" ", 2);
                List<String[]> headers = new ArrayList<>();
                byte[] body = null;
                for (int i = 1; i < parts.length; i++) {
                    if (parts[i].startsWith("BODY=")) {
                        body = parts[i].substring(5).getBytes();
                    } else {
                        headers.add(parts[i].split(": ", 2));
                    }
                }
                Req req = new Req(first[0], first[1], headers, body);
                Res a = s.micronaut(req);
                Res b = s.jersey(req);
                System.out.println("REQ " + req + "\n  ours   " + a.status() + " ct=" + a.header("Content-Type") + " allow=" + a.header("Allow")
                    + " body=" + ServerConformanceTest.abbreviate(a.text())
                    + "\n  jersey " + b.status() + " ct=" + b.header("Content-Type") + " allow=" + b.header("Allow")
                    + " body=" + ServerConformanceTest.abbreviate(b.text()));
            }
        }
    }
}
