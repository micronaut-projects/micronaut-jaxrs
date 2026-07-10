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
package io.micronaut.validation.tck;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArchiveCompilerTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesArchiveEntriesInsideTargetDirectory() throws Exception {
        Path resolved = ArchiveCompiler.resolveInside(tempDir, "WEB-INF/classes/Test.class");

        assertEquals(tempDir.resolve("WEB-INF/classes/Test.class").toAbsolutePath().normalize(), resolved);
    }

    @Test
    void rejectsArchiveEntriesOutsideTargetDirectory() {
        assertThrows(IOException.class, () -> ArchiveCompiler.resolveInside(tempDir, "../escape.txt"));
    }

    @Test
    void quotesGeneratedJavaStringLiteralsIncludingControls() {
        assertEquals("\"a\\\"b\\\\c\\n\\u007f\"", ArchiveCompiler.quote("a\"b\\c\n\u007f"));
    }
}
