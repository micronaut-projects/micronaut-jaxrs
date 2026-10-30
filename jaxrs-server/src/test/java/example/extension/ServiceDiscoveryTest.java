package example.extension;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The features of the service loader (JAX-RS 4.1.2): discovered through the context class loader,
 * registered whether they are enabled or not, and the features a dynamic feature enables.
 */
class ServiceDiscoveryTest {

    @Test
    void aDynamicFeatureOfTheContextClassLoaderIsDiscovered(@TempDir Path directory) throws IOException {
        Path services = Files.createDirectories(directory.resolve("META-INF/services"));
        Files.writeString(services.resolve(DynamicFeature.class.getName()), ContextLoaderDynamicFeature.class.getName());
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, previous)) {
            thread.setContextClassLoader(loader);
            ContextLoaderDynamicFeature.CONFIGURED.set(0);
            try (ApplicationContext context = ApplicationContext.run(EmbeddedServer.class,
                Map.of("spec.name", "ServiceDiscoveryTest.loader")).getApplicationContext()) {
                assertTrue(ContextLoaderDynamicFeature.CONFIGURED.get() > 0);
            }
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void aFeatureOfTheServiceLoaderThatIsNotEnabledIsRegistered() {
        try (ApplicationContext context = ApplicationContext.run(EmbeddedServer.class,
            Map.of("spec.name", "ServiceDiscoveryTest.registered")).getApplicationContext()) {
            Configuration configuration = context.getBean(Configuration.class);
            assertTrue(configuration.isRegistered(ServiceFeature.class));
            assertTrue(configuration.getInstances().stream().anyMatch(ServiceFeature.class::isInstance));
        }
    }

    @Test
    void aFeatureADynamicFeatureEnablesIsEnabledForTheResourceMethod() {
        NestedEnablingFeature.RESULT = null;
        try (ApplicationContext ignored = ApplicationContext.run(EmbeddedServer.class,
            Map.of("spec.name", "ServiceDiscoveryTest.enabled")).getApplicationContext()) {
            assertEquals("true true", NestedEnablingFeature.RESULT);
        }
    }

    @Requires(property = "spec.name", value = "ServiceDiscoveryTest.enabled")
    @Singleton
    @jakarta.ws.rs.ext.Provider
    public static class NestedEnablingFeature implements DynamicFeature {
        static volatile String RESULT;

        @Override
        public void configure(ResourceInfo resourceInfo, FeatureContext context) {
            if (resourceInfo.getResourceClass() != DiscoveryResource.class) {
                return;
            }
            EnabledFeature feature = new EnabledFeature();
            context.register(feature);
            RESULT = context.getConfiguration().isEnabled(feature) + " " + context.getConfiguration().isEnabled(EnabledFeature.class);
        }
    }

    /**
     * A feature that is enabled.
     */
    public static class EnabledFeature implements Feature {
        @Override
        public boolean configure(FeatureContext context) {
            return true;
        }
    }

    @Requires(property = "spec.name", value = "ServiceDiscoveryTest.enabled")
    @jakarta.ws.rs.Path("/service-discovery")
    public static class DiscoveryResource {
        @GET
        public String get() {
            return "ok";
        }
    }
}
