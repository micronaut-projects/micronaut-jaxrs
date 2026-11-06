package io.micronaut.jaxrs.providers;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The File provider keeps its temporary files private and removes failed copies.
 */
class FileProviderTest {

    private static final Annotation[] NO_ANNOTATIONS = new Annotation[0];

    @Test
    void fileKeepsOwnerOnlyPermissions() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        File file = new JaxRsFileMessageBodyReaderWriter().readFrom(File.class, File.class, NO_ANNOTATIONS,
            MediaType.TEXT_PLAIN_TYPE, new MultivaluedHashMap<>(), new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)));
        try {
            assertArrayEquals("content".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file.toPath()));
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file.toPath()));
        } finally {
            Files.delete(file.toPath());
        }
    }

    @Test
    void failedFileCopyIsDeleted() throws Exception {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        Set<Path> before = entityFiles(tmp);
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("broken");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("broken");
            }
        };
        assertThrows(IOException.class, () -> new JaxRsFileMessageBodyReaderWriter().readFrom(File.class, File.class,
            NO_ANNOTATIONS, MediaType.TEXT_PLAIN_TYPE, new MultivaluedHashMap<>(), failing));
        assertEquals(before, entityFiles(tmp));
    }

    private static Set<Path> entityFiles(Path tmp) throws IOException {
        try (var files = Files.list(tmp)) {
            return Set.copyOf(files.filter(p -> p.getFileName().toString().startsWith("jaxrs-entity")).toList());
        }
    }
}
