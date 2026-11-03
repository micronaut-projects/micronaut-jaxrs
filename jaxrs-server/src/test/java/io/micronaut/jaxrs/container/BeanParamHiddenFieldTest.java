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
 * A field of a bean parameter and the field of a super class it hides, of the same name, are both
 * read from the request.
 */
@MicronautTest
@Property(name = "spec.name", value = "BeanParamHiddenFieldTest")
class BeanParamHiddenFieldTest {

    @Inject
    @Client("/api/hidden-field")
    HttpClient client;

    @Test
    void hiddenFieldIsSet() {
        assertEquals("a b", client.toBlocking().retrieve(HttpRequest.GET("/?x=a&y=b")));
    }

    @Test
    void hiddenFieldOfAResourceIsSet() {
        assertEquals("a b", client.toBlocking().retrieve(HttpRequest.GET("/resource?x=a&y=b")));
    }

    public static class BaseBean {
        @QueryParam("y")
        String value;
    }

    public static class Bean extends BaseBean {
        @QueryParam("x")
        String value;
    }

    public static class BaseResource {
        @QueryParam("y")
        String value;
    }

    @Requires(property = "spec.name", value = "BeanParamHiddenFieldTest")
    @Path("/hidden-field/resource")
    public static class HidingResource extends BaseResource {
        @QueryParam("x")
        String value;

        @GET
        @Produces("text/plain")
        public String get() {
            return value + " " + ((BaseResource) this).value;
        }
    }

    @Requires(property = "spec.name", value = "BeanParamHiddenFieldTest")
    @Path("/hidden-field")
    public static class BeanResource {
        @GET
        @Produces("text/plain")
        public String get(@BeanParam Bean bean) {
            return bean.value + " " + ((BaseBean) bean).value;
        }
    }
}
