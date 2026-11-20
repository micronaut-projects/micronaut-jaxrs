package io.micronaut.jaxrs.providers;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The temporary file of a {@link File} entity is deleted once the {@link File} is no longer
 * reachable, and nothing is kept for it until the JVM exits, unlike {@link File#deleteOnExit()}.
 */
class FileProviderCleanupTest {

    @Test
    void theFileIsDeletedOnceUnreachable() throws Exception {
        int before = JaxRsFileMessageBodyReaderWriter.temporaryFiles();
        Path path = read();
        assertTrue(Files.exists(path));
        for (int i = 0; i < 100 && Files.exists(path); i++) {
            System.gc();
            Thread.sleep(50);
        }
        assertFalse(Files.exists(path));
        assertTrue(JaxRsFileMessageBodyReaderWriter.temporaryFiles() <= before);
    }

    private static Path read() throws IOException {
        File file = new JaxRsFileMessageBodyReaderWriter().readFrom(File.class, File.class, new Annotation[0],
            MediaType.TEXT_PLAIN_TYPE, new MultivaluedHashMap<>(), new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)));
        return file.toPath();
    }
}
