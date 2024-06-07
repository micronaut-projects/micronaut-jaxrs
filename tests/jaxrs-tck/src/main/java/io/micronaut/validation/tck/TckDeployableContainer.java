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

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.Internal;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.validation.tck.runtime.TestClassVisitor;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.context.annotation.DeploymentScoped;
import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.core.api.InstanceProducer;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.test.spi.TestClass;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.container.LibraryContainer;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Objects;

@Internal
public final class TckDeployableContainer implements DeployableContainer<TckContainerConfiguration> {

    private static final Logger LOGGER = LoggerFactory.getLogger(TckDeployableContainer.class);

    static ClassLoader old;

    public static ThreadLocal<ApplicationContext> APP = new ThreadLocal<>();

    @Inject
    @DeploymentScoped
    private InstanceProducer<ApplicationContext> runningApplicationContext;

    @Inject
    @DeploymentScoped
    private InstanceProducer<ClassLoader> applicationClassLoader;

    @Inject
    @DeploymentScoped
    private InstanceProducer<DeploymentDir> deploymentDir;

    @Inject
    private Instance<TestClass> testClass;

    static Object testInstance;

    @Override
    public void deploy(Descriptor descriptor) {
        throw new UnsupportedOperationException("Container does not support deployment of Descriptors");

    }

    @Override
    public void undeploy(Descriptor descriptor) {
        throw new UnsupportedOperationException("Container does not support deployment of Descriptors");

    }

    @Override
    public Class<TckContainerConfiguration> getConfigurationClass() {
        return TckContainerConfiguration.class;
    }

    @Override
    public void setup(TckContainerConfiguration configuration) {
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("Micronaut");
    }

    private static JavaArchive buildSupportLibrary() {
        return ShrinkWrap.create(JavaArchive.class, "micronaut-jaxrs-tck-support.jar")
            .addAsManifestResource("META-INF/services/io.micronaut.inject.visitor.TypeElementVisitor")
            .addPackage(TestClassVisitor.class.getPackage());
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) {
        if (archive instanceof LibraryContainer<?> libraryContainer) {
            libraryContainer.addAsLibrary(buildSupportLibrary());
        } else {
            throw new IllegalStateException("Expected library container!");
        }
        old = Thread.currentThread().getContextClassLoader();
        if (testClass.get() == null) {
            throw new IllegalStateException("Test class not available");
        }
        Class<?> testJavaClass = testClass.get().getJavaClass();
        Objects.requireNonNull(testJavaClass);

        try {
            DeploymentDir deploymentDir = new DeploymentDir();
            this.deploymentDir.set(deploymentDir);

            new ArchiveCompiler(deploymentDir, archive).compile();

            ClassLoader classLoader = new DeploymentClassLoader(deploymentDir);
            applicationClassLoader.set(classLoader);

            ApplicationContext applicationContext = ApplicationContext.builder()
                .properties(Map.of(
                    "micronaut.server.port", 0,
                    "micronaut.server.context-path", archive.getName().replaceAll("\\.war$", "")
                ))
                .classLoader(classLoader)
                .build()
                .start();

            EmbeddedServer embeddedServer = applicationContext.getBean(EmbeddedServer.class);
            embeddedServer.start();
            System.setProperty("webServerHost", embeddedServer.getHost());
            System.setProperty("webServerPort", String.valueOf(embeddedServer.getPort()));
            //testInstance = applicationContext.getBean(classLoader.loadClass(testJavaClass.getName()));

            runningApplicationContext.set(applicationContext);
            APP.set(applicationContext);
            Thread.currentThread().setContextClassLoader(classLoader);

        } catch (Throwable e) {
            throw new RuntimeException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(old);
        }

        return new ProtocolMetaData();
    }

    @Override
    public void undeploy(Archive<?> archive) {
        try {
            ApplicationContext appContext = runningApplicationContext.get();
            if (appContext != null) {
                Thread.currentThread().setContextClassLoader(runningApplicationContext.get().getClassLoader());
                appContext.stop();
            }
            testInstance = null;

            DeploymentDir deploymentDir = this.deploymentDir.get();
            if (deploymentDir != null) {
                deleteDirectory(deploymentDir.root);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    private static void deleteDirectory(Path dir) {
        try {
            Files.walkFileTree(dir, new FileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Unable to delete directory: " + dir, e);
        }
    }
}
