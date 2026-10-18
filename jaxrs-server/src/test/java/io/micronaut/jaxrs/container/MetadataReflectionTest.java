package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Creator;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.UriBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JaxRsReflection of the generated metadata, without the micronaut-reflection module.
 */
@MicronautTest
@Property(name = "spec.name", value = "MetadataReflectionTest")
@EnabledIfSystemProperty(named = "micronaut.jaxrs.test.reflection", matches = "false")
class MetadataReflectionTest {

    @Inject
    @Client("/api/metadata-reflection")
    HttpClient client;

    @Test
    void annotationsOfAClassWithoutIntrospectionNeedReflection() {
        // e.g. of a client provider registered by class without @Provider: the annotations of a
        // bean are the ones of its bean definition, which respects its @Requires
        assertEquals(List.of(),
            List.of(JaxRsReflection.get().annotationMetadata(ProducingBean.class).stringValues(Produces.class)));
    }

    @Test
    void methodAnnotationsOfAClassWithoutIntrospectionNeedReflection() {
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
            () -> JaxRsReflection.get().methodAnnotations(ProducingBean.class, "toString"));
        assertTrue(e.getMessage().contains("io.micronaut:micronaut-reflection"));
    }

    @Test
    void pathOfAClassWithoutAnnotationsNeedsReflection() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> UriBuilder.fromPath("/").path(Object.class));
        assertTrue(e.getMessage().contains("io.micronaut:micronaut-reflection"));
    }

    @Test
    void parameterOfAStaticFactoryCreator() {
        assertEquals("v:1", client.toBlocking().retrieve(HttpRequest.GET("/version?v=1"), String.class));
    }

    @Requires(property = "spec.name", value = "MetadataReflectionTest.ProducingBean")
    @Singleton
    @Produces("text/x-bean")
    public static class ProducingBean {
    }

    /**
     * A parameter type with a static factory method, which the introspection calls when it is
     * its creator.
     */
    @Introspected
    public static final class Version {
        private final String value;

        private Version(String value) {
            this.value = value;
        }

        @Creator
        public static Version valueOf(String value) {
            return new Version("v:" + value);
        }
    }

    @Requires(property = "spec.name", value = "MetadataReflectionTest")
    @Path("/metadata-reflection")
    public static class Resource {
        @GET
        @Path("version")
        public String version(@QueryParam("v") Version version) {
            return version.value;
        }
    }
}
