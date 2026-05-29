/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.jaxrs.common;

import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Creates temporary files in a private directory instead of the shared system temp directory.
 */
@Internal
public final class JaxRsTemporaryFiles {
    public static final String TEMP_DIRECTORY_PROPERTY = "micronaut.jaxrs.temp-directory";

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");

    private JaxRsTemporaryFiles() {
    }

    /**
     * Create a temporary file under the configured directory, or under a private default directory.
     *
     * @param prefix The file prefix
     * @param suffix The file suffix
     * @param configuredDirectory The configured temporary directory
     * @return The temporary file
     * @throws IOException If the file cannot be created safely
     */
    public static File createTempFile(String prefix, String suffix, @Nullable Path configuredDirectory) throws IOException {
        Path directory = ensurePrivateDirectory(configuredDirectory == null ? defaultDirectory() : configuredDirectory);
        Path file = createTempFile(directory, prefix, suffix, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
        return file.toFile();
    }

    /**
     * Resolve a configured temporary directory value.
     *
     * @param value The configured value
     * @return The resolved directory path
     */
    public static @Nullable Path configuredDirectory(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Path path) {
            return path;
        }
        if (value instanceof File file) {
            return file.toPath();
        }
        if (value instanceof CharSequence path) {
            return Path.of(path.toString());
        }
        throw new IllegalArgumentException("Unsupported JAX-RS temporary directory value: " + value.getClass().getName());
    }

    private static Path defaultDirectory() {
        return Path.of(System.getProperty("user.home", "."), ".micronaut-jaxrs", "tmp");
    }

    private static Path ensurePrivateDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("JAX-RS temporary directory must not be a symbolic link: " + directory);
        }
        createDirectories(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory)) {
            throw new IOException("JAX-RS temporary directory is not a directory: " + directory);
        }
        try {
            Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(directory);
            if (permissions.contains(PosixFilePermission.GROUP_WRITE) || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new IOException("JAX-RS temporary directory must not be group or world writable: " + directory);
            }
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX file systems do not expose Unix-style public-write permissions.
        }
        return directory;
    }

    private static void createDirectories(Path directory, FileAttribute<Set<PosixFilePermission>> permissions) throws IOException {
        try {
            Files.createDirectories(directory, permissions);
        } catch (UnsupportedOperationException e) {
            Files.createDirectories(directory);
        }
    }

    private static Path createTempFile(Path directory,
                                       String prefix,
                                       String suffix,
                                       FileAttribute<Set<PosixFilePermission>> permissions) throws IOException {
        try {
            return Files.createTempFile(directory, prefix, suffix, permissions);
        } catch (UnsupportedOperationException e) {
            return Files.createTempFile(directory, prefix, suffix);
        }
    }
}
