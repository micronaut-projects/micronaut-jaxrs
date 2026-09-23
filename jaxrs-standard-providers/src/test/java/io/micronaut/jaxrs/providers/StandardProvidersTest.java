package io.micronaut.jaxrs.providers;

import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.activation.DataSource;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import org.junit.jupiter.api.Test;

import javax.xml.namespace.QName;
import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMSource;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The standard entity providers of JAX-RS 4.2.4 read and write their types.
 */
@MicronautTest
class StandardProvidersTest {

    @Inject
    EmbeddedServer server;

    private HttpResponse<String> post(String path, String contentType, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.getURL() + "/providers/" + path))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void file() throws Exception {
        HttpResponse<String> response = post("file", "text/plain", "file content");
        assertEquals(200, response.statusCode());
        assertEquals("file content", response.body());
    }

    @Test
    void dataSource() throws Exception {
        HttpResponse<String> response = post("datasource", "text/plain", "data");
        assertEquals(200, response.statusCode());
        assertEquals("text/plain:data", response.body());
    }

    @Test
    void source() throws Exception {
        HttpResponse<String> response = post("source", "application/xml", "<a>b</a>");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("<a>b</a>"), response.body());
    }

    @Test
    void domSource() throws Exception {
        HttpResponse<String> response = post("dom", "text/xml", "<a>b</a>");
        assertEquals(200, response.statusCode());
        assertEquals("a", response.body());
    }

    @Test
    void jaxbRootElement() throws Exception {
        HttpResponse<String> response = post("jaxb", "application/xml", "<item><name>one</name></item>");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("<name>one!</name>"), response.body());
    }

    @Test
    void jaxbElement() throws Exception {
        HttpResponse<String> response = post("element", "application/xml", "<value>text</value>");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains(">text!<"), response.body());
    }

    @XmlRootElement(name = "item")
    public static class Item {
        public String name;
    }

    @Path("/providers")
    public static class ProvidersResource {

        @POST
        @Path("file")
        public File file(File file) throws IOException {
            File copy = Files.createTempFile("copy", null).toFile();
            Files.copy(file.toPath(), copy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return copy;
        }

        @POST
        @Path("datasource")
        @Produces(MediaType.TEXT_PLAIN)
        public String dataSource(DataSource dataSource) throws IOException {
            try (InputStream in = dataSource.getInputStream()) {
                return dataSource.getContentType() + ":" + new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        @POST
        @Path("source")
        @Consumes(MediaType.APPLICATION_XML)
        @Produces(MediaType.APPLICATION_XML)
        public Source source(Source source) {
            return source;
        }

        @POST
        @Path("dom")
        @Produces(MediaType.TEXT_PLAIN)
        public String dom(DOMSource source) {
            return source.getNode().getFirstChild().getNodeName();
        }

        @POST
        @Path("jaxb")
        @Consumes(MediaType.APPLICATION_XML)
        @Produces(MediaType.APPLICATION_XML)
        public Item jaxb(Item item) {
            item.name = item.name + "!";
            return item;
        }

        @POST
        @Path("element")
        @Consumes(MediaType.APPLICATION_XML)
        @Produces(MediaType.APPLICATION_XML)
        public JAXBElement<String> element(JAXBElement<String> element) {
            return new JAXBElement<>(new QName("value"), String.class, element.getValue() + "!");
        }
    }
}
