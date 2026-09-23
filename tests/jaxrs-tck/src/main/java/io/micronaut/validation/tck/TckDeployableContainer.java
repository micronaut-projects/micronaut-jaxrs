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
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.jboss.arquillian.container.spi.context.annotation.DeploymentScoped;
import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.core.api.InstanceProducer;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.test.spi.TestClass;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.container.LibraryContainer;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Internal
public final class TckDeployableContainer implements DeployableContainer<TckContainerConfiguration> {

    private static final Pattern LOGIN_BASIC = Pattern.compile("<auth-method>\\s*BASIC\\s*</auth-method>");
    private static final Pattern SECURITY_CONSTRAINT = Pattern.compile("<security-constraint>(.*?)</security-constraint>", Pattern.DOTALL);
    private static final Pattern URL_PATTERN = Pattern.compile("<url-pattern>([^<]+)</url-pattern>");
    private static final Pattern ROLE_NAME = Pattern.compile("<role-name>([^<]+)</role-name>");
    private static final Pattern APPLICATION_PARAM = Pattern.compile(
        "<param-name>\\s*jakarta\\.ws\\.rs\\.Application\\s*</param-name>\\s*<param-value>([^<]+)</param-value>");

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
            .addAsResource("logback.xml")
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
        String ownArchive = ownArchiveName(testJavaClass);
        if (ownArchive != null && !ownArchive.equals(archive.getName())) {
            // a deployment inherited from the test class this one extends: every deployment gets its
            // own server, and the tests only know the port of one, so only the test class's own starts
            LOGGER.info("Skipping the inherited deployment {} of {}", archive.getName(), testJavaClass.getName());
            return new ProtocolMetaData();
        }

        try {
            DeploymentDir deploymentDir = new DeploymentDir();
            this.deploymentDir.set(deploymentDir);

            new ArchiveCompiler(deploymentDir, archive).compile();

            ClassLoader classLoader = new DeploymentClassLoader(deploymentDir);
            applicationClassLoader.set(classLoader);

            Map<String, Object> properties = new HashMap<>(Map.of(
                "micronaut.server.port", 0,
                "micronaut.server.dispatch-options-requests", true,
                "micronaut.server.not-found-on-missing-body", false,
                "micronaut.server.context-path", archive.getName().replaceAll("\\.war$", "")
            ));
            // the Application the servlet of the web.xml names
            applicationClass(archive).ifPresent(application -> properties.put("micronaut.jaxrs.application", application));
            // the security constraints of the web.xml, with the users of the TCK
            String contextPath = "/" + archive.getName().replaceAll("\\.war$", "");
            boolean secured = security(webXml(archive), contextPath, properties);
            ApplicationContext applicationContext = ApplicationContext.builder()
                .properties(properties)
                .singletons(secured ? new Object[] {new TckAuthenticationProvider<>()} : new Object[0])
                .classLoader(classLoader)
                .build()
                .start();

            EmbeddedServer embeddedServer = applicationContext.getBean(EmbeddedServer.class);
            embeddedServer.start();
            System.setProperty("webServerHost", embeddedServer.getHost());
            System.setProperty("webServerPort", String.valueOf(embeddedServer.getPort()));

            runningApplicationContext.set(applicationContext);
            APP.set(applicationContext);
            Thread.currentThread().setContextClassLoader(classLoader);

            // the URL of the deployment, which tests inject with @ArquillianResource
            String contextRoot = "/" + archive.getName().replaceAll("\\.war$", "");
            HTTPContext httpContext = new HTTPContext(embeddedServer.getHost(), embeddedServer.getPort());
            httpContext.add(new Servlet("default", contextRoot));
            return new ProtocolMetaData().addContext(httpContext);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    /**
     * @param testJavaClass The test class
     * @return The name of the archive of the {@code @Deployment} method the test class declares
     * itself, or {@code null}
     */
    private static String ownArchiveName(Class<?> testJavaClass) {
        for (java.lang.reflect.Method method : testJavaClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(org.jboss.arquillian.container.test.api.Deployment.class)
                && java.lang.reflect.Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0) {
                try {
                    method.setAccessible(true);
                    return ((Archive<?>) method.invoke(null)).getName();
                } catch (ReflectiveOperationException e) {
                    return null;
                }
            }
        }
        return null;
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
            LOGGER.warn("Unable to delete directory: {}", dir, e);
        }
    }

    /**
     * The {@code jakarta.ws.rs.Application} parameter of the servlet in the {@code web.xml} of a
     * deployment.
     */
    private static Optional<String> applicationClass(Archive<?> archive) {
        Matcher matcher = APPLICATION_PARAM.matcher(webXml(archive));
        return matcher.find() ? Optional.of(matcher.group(1).trim()) : Optional.empty();
    }

    /**
     * The {@code web.xml} of a deployment, empty if it has none.
     */
    private static String webXml(Archive<?> archive) {
        Node webXml = archive.get("WEB-INF/web.xml");
        if (webXml == null || webXml.getAsset() == null) {
            return "";
        }
        try (InputStream in = webXml.getAsset().openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Configure Micronaut Security like the servlet container would the {@code web.xml}: the BASIC
     * login, and the security constraints as the roles of the URL patterns, under the context path;
     * every other path is anonymous. Without a login configuration, security is disabled.
     *
     * @return Whether the deployment is secured
     */
    private static boolean security(String webXml, String contextPath, Map<String, Object> properties) {
        if (!LOGIN_BASIC.matcher(webXml).find()) {
            properties.put("micronaut.security.enabled", false);
            return false;
        }
        properties.put("micronaut.security.enabled", true);
        properties.put("micronaut.security.reject-not-found", false);
        List<Map<String, Object>> rules = new ArrayList<>();
        Matcher constraint = SECURITY_CONSTRAINT.matcher(webXml);
        while (constraint.find()) {
            String block = constraint.group(1);
            List<String> roles = new ArrayList<>();
            Matcher role = ROLE_NAME.matcher(block.substring(Math.max(0, block.indexOf("<auth-constraint>"))));
            while (role.find()) {
                roles.add(role.group(1).trim());
            }
            Matcher pattern = URL_PATTERN.matcher(block);
            while (pattern.find()) {
                String urlPattern = pattern.group(1).trim().replaceAll("/\\*$", "/**");
                List<String> access = roles.isEmpty() ? List.of("isAuthenticated()") : roles;
                // with and without the context path of the server
                rules.add(Map.of("pattern", contextPath + urlPattern, "access", access));
                rules.add(Map.of("pattern", urlPattern, "access", access));
            }
        }
        rules.add(Map.of("pattern", "/**", "access", List.of("isAnonymous()")));
        properties.put("micronaut.security.intercept-url-map", rules);
        return true;
    }
}
