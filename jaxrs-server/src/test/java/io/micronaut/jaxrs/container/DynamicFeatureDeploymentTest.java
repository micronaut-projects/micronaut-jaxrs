package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The dynamic features are configured for the resource methods at deployment, not when a method
 * is first requested.
 */
@MicronautTest
@Property(name = "spec.name", value = "DynamicFeatureDeploymentTest")
class DynamicFeatureDeploymentTest {

    static final Set<String> CONFIGURED = ConcurrentHashMap.newKeySet();

    @Test
    void configuredAtStartup() {
        // no request was sent
        assertEquals(Set.of("first", "second"), CONFIGURED);
    }

    @Requires(property = "spec.name", value = "DynamicFeatureDeploymentTest")
    @Path("/dynamic-deployment")
    public static class DeploymentResource {
        @GET
        @Path("first")
        @Produces("text/plain")
        public String first() {
            return "first";
        }

        @GET
        @Path("second")
        @Produces("text/plain")
        public String second() {
            return "second";
        }
    }

    @Requires(property = "spec.name", value = "DynamicFeatureDeploymentTest")
    @Provider
    public static class DeploymentFeature implements DynamicFeature {
        @Override
        public void configure(ResourceInfo resourceInfo, FeatureContext context) {
            if (resourceInfo.getResourceClass() == DeploymentResource.class) {
                CONFIGURED.add(resourceInfo.getResourceMethod().getName());
            }
        }
    }
}
