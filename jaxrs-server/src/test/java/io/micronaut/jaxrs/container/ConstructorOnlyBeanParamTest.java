package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A bean parameter whose only annotations are on the parameters of a constructor is created with
 * that constructor.
 */
@MicronautTest
@Property(name = "spec.name", value = "ConstructorOnlyBeanParamTest")
class ConstructorOnlyBeanParamTest {

    @Inject
    @Client("/api/constructor-only-bean")
    HttpClient client;

    @Test
    void beanParamIsCreatedWithItsAnnotatedConstructor() {
        assertEquals("value", client.toBlocking().retrieve(HttpRequest.GET("/?x=value")));
    }

    public static class ConstructorOnlyBean {
        private final String x;

        public ConstructorOnlyBean() {
            this("default");
        }

        public ConstructorOnlyBean(@QueryParam("x") String x) {
            this.x = x;
        }
    }

    @Requires(property = "spec.name", value = "ConstructorOnlyBeanParamTest")
    @Path("/constructor-only-bean")
    public static class ConstructorOnlyBeanResource {
        @GET
        @Produces("text/plain")
        public String get(@BeanParam ConstructorOnlyBean bean) {
            return bean.x;
        }
    }
}
