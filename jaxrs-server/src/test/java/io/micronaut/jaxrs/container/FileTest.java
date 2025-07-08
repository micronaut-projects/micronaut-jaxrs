package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

@MicronautTest
@Property(name = "spec.name", value = "FileTest")
class FileTest {

    @Inject
    @Client("/api/file")
    HttpClient client;

    @Inject
    MyController controller;

    @Test
    void testFile() throws IOException {
        controller.file = Files.createTempFile("jaxrs-FileTest", null).toFile();
        try {
            Files.writeString(controller.file.toPath(), "foo");
            Assertions.assertEquals("foo", client.toBlocking().retrieve("/file"));
        } finally {
            controller.file.delete();
        }
    }

    @Requires(property = "spec.name", value = "FileTest")
    @Path("/file")
    static class MyController {
        File file;

        @GET
        @Path("/file")
        @Produces("text/plain")
        public File get() throws IOException {
            return file;
        }
    }
}
