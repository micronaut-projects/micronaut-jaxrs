/*
 * Copyright 2017-2023 original authors
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

import io.micronaut.annotation.processing.AggregatingTypeElementVisitorProcessor;
import io.micronaut.annotation.processing.BeanDefinitionInjectProcessor;
import io.micronaut.annotation.processing.TypeElementVisitorProcessor;
import io.micronaut.core.annotation.Internal;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ArchivePath;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.spec.WebArchive;

import javax.annotation.processing.Processor;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * IMPORTANT: assumes that it is possible to iterate through the {@code Archive}
 * and for each {@code .class} file in there, find a corresponding {@code .java}
 * file in this class's classloader. In other words, the CDI TCK source JAR must
 * be on classpath.
 */
@Internal
final class ArchiveCompiler {
    private final DeploymentDir deploymentDir;
    private final Archive<?> deploymentArchive;

    ArchiveCompiler(DeploymentDir deploymentDir, Archive<?> deploymentArchive) {
        this.deploymentDir = deploymentDir;
        this.deploymentArchive = deploymentArchive;
    }

    void compile() throws ArchiveCompilationException, ArchiveCompilerException {
        try {
            if (deploymentArchive instanceof WebArchive) {
                compileWar();
            } else {
                throw new ArchiveCompilerException("Unknown archive type: " + deploymentArchive);
            }
        } catch (IOException e) {
            throw new ArchiveCompilerException("Compilation failed", e);
        }
    }

    private void compileWar() throws ArchiveCompilationException, IOException {
        Set<File> sourceFiles = new LinkedHashSet<>();
        for (Map.Entry<ArchivePath, Node> entry : deploymentArchive.getContent().entrySet()) {
            String path = entry.getKey().get();
            if (path.startsWith("/WEB-INF/classes") && path.endsWith(".class")) {
                addSource(path.substring("/WEB-INF/classes".length()), sourceFiles);
            } else if (path.startsWith("/WEB-INF/classes/") && entry.getValue().getAsset() != null) {
                // a resource of the deployment, e.g. a META-INF/services file of a JAX-RS feature
                Path resourcePath = deploymentDir.target.resolve(path.substring("/WEB-INF/classes/".length()));
                Files.createDirectories(resourcePath.getParent());
                try (InputStream in = entry.getValue().getAsset().openStream()) {
                    Files.copy(in, resourcePath);
                }
            } else if (path.startsWith("/WEB-INF/lib") && path.endsWith(".jar")) {
                String jarFile = path.replace("/WEB-INF/lib", "");
                Path jarFilePath = deploymentDir.lib.resolve(jarFile.substring(1)); // jarFile begins with `/`

                Files.createDirectories(jarFilePath.getParent()); // make sure the directory exists
                copyLibrary(entry.getValue(), jarFilePath);
            }
        }

        doCompile(sourceFiles, deploymentDir.target.toFile());
    }

    /**
     * Copies a library of the deployment without the classes of the TCK, which are loaded from
     * the TCK on the classpath, with the metadata of the annotation processors when it is the
     * processed one: the unprocessed classes of the jar would hide them. The jar keeps its other
     * classes and its resources. The classes of a library are not beans of the deployment.
     */
    private static void copyLibrary(Node library, Path jarFilePath) throws IOException {
        ClassLoader classLoader = ArchiveCompiler.class.getClassLoader();
        try (JarInputStream in = new JarInputStream(library.getAsset().openStream());
             JarOutputStream out = in.getManifest() == null ? new JarOutputStream(Files.newOutputStream(jarFilePath))
                 : new JarOutputStream(Files.newOutputStream(jarFilePath), in.getManifest())) {
            for (JarEntry jarEntry = in.getNextJarEntry(); jarEntry != null; jarEntry = in.getNextJarEntry()) {
                String name = jarEntry.getName();
                if (name.endsWith(".class") && name.startsWith("ee/jakarta/tck/") && classLoader.getResource(name) != null) {
                    continue;
                }
                out.putNextEntry(new JarEntry(name));
                in.transferTo(out);
                out.closeEntry();
            }
        }
    }

    /**
     * Adds the source of a class to the compilation, the source of the top level class for a nested class.
     *
     * @param classFile The path of the class file, beginning with {@code /}
     * @param sourceFiles The source files
     */
    private void addSource(String classFile, Set<File> sourceFiles) throws IOException {
        String className = classFile.substring(0, classFile.length() - ".class".length());
        int nested = className.indexOf('$', className.lastIndexOf('/'));
        String sourceFile = (nested == -1 ? className : className.substring(0, nested)) + ".java";
        Path sourceFilePath = deploymentDir.source.resolve(sourceFile.substring(1));
        if (sourceFiles.contains(sourceFilePath.toFile())) {
            return;
        }
        try (InputStream in = ArchiveCompiler.class.getResourceAsStream(sourceFile)) {
            if (in == null) {
                // a non-public top level class declared in the source of another class
                return;
            }
            Files.createDirectories(sourceFilePath.getParent());
            Files.copy(in, sourceFilePath);
        }
        sourceFiles.add(sourceFilePath.toFile());
    }

    private void doCompile(Collection<File> testSources, File outputDir) throws ArchiveCompilationException, IOException {
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager mgr = compiler.getStandardFileManager(diagnostics, null, null)) {
            final String targetDir = outputDir.getAbsolutePath();
            JavaCompiler.CompilationTask task = compiler.getTask(
                null,
                mgr,
                diagnostics,
                Arrays.asList("-d", targetDir, "-verbose", "-parameters"),
                null,
                mgr.getJavaFileObjectsFromFiles(
                    Stream.concat(
                        testSources.stream(),
                        Stream.of(
                            applicationClass(testSources).toFile()
                        )
                    ).toList()
                )
            );
            task.setProcessors(getAnnotationProcessors());
            Boolean success = task.call();
            if (!Boolean.TRUE.equals(success)) {
                outputDiagnostics(diagnostics);
            }
        }
    }

    private Path applicationClass(Collection<File> testSources) throws IOException {
        String annotations = "";
        String packageName = "ee.jakarta.tck.ws.rs";
        final Path packagePath = deploymentDir.target.resolve(
            packageName.replace('.', '/')
        );
        Files.createDirectories(packagePath);
        final Path applicationSource = packagePath.resolve("Application.java");
        String sourceCode = "package " + packageName + ";\n" +
            annotations + " class Application {}";
        Files.writeString(applicationSource, sourceCode);
        return applicationSource;
    }

    private void outputDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) throws ArchiveCompilationException {
        throw new ArchiveCompilationException("Compilation failed:\n" + diagnostics.getDiagnostics()
            .stream()
            .map(it -> {
                System.out.println(it);
                if (it.getSource() == null) {
                    return "- " + it.getMessage(Locale.US);
                }
                Path source = deploymentDir.source.relativize(Paths.get(it.getSource().toUri().getPath()));
                return "- " + source + ":" + it.getLineNumber() + " " + it.getMessage(Locale.US);
            })
            .collect(Collectors.joining("\n")));
    }

    private List<Processor> getAnnotationProcessors() {
        List<Processor> result = new ArrayList<>();
        result.add(new TypeElementVisitorProcessor());
        result.add(new AggregatingTypeElementVisitorProcessor());
        result.add(new BeanDefinitionInjectProcessor() {
            @Override
            protected boolean isProcessedAnnotation(String annotationName) {
                return true;
            }
        });
        return result;
    }

}
