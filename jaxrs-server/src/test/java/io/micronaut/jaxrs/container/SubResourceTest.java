package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Sub-resource locators, resources with request values in fields, and bean parameters.
 */
@MicronautTest
@Property(name = "spec.name", value = "SubResourceTest")
class SubResourceTest {

    @Inject
    @Client("/api/sub-resources")
    HttpClient client;

    @Test
    void locatorReturningAnInstance() {
        assertEquals("item 7 of shelf a", client.toBlocking().retrieve(HttpRequest.GET("/shelves/a/items/7")));
    }

    @Test
    void locatorReturningItself() {
        assertEquals("self q=x", client.toBlocking().retrieve(HttpRequest.GET("/self?q=x")));
    }

    @Test
    void locatorReturningAClass() {
        assertEquals("created h=v", client.toBlocking().retrieve(HttpRequest.GET("/created").header("X-Value", "v")));
    }

    @Test
    void locatorReturningNullIsNotFound() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.GET("/missing/anything")));
        assertEquals(404, e.getStatus().getCode());
    }

    @Test
    void beanParam() {
        assertEquals("bean 3 q=y h=none", client.toBlocking().retrieve(HttpRequest.GET("/bean/3?q=y")));
    }

    @Test
    void locatorReturningItselfInASubclassOfAResourceInAnotherPackage() {
        assertEquals("base field=f q=x", client.toBlocking().retrieve(HttpRequest.GET("/inherited/subresource?q=x&field=f")));
    }

    @Requires(property = "spec.name", value = "SubResourceTest")
    @Path("/sub-resources/inherited")
    public static class InheritedResource extends io.micronaut.jaxrs.container.base.BaseParamResource {
        @Path("subresource")
        public InheritedResource subresource() {
            return this;
        }
    }

    @Requires(property = "spec.name", value = "SubResourceTest")
    @Path("/sub-resources")
    public static class RootResource {

        @QueryParam("q")
        @DefaultValue("none")
        String query;

        @Path("/shelves/{shelf}")
        public Shelf shelf(@PathParam("shelf") String shelf) {
            return new Shelf(shelf);
        }

        @Path("/self")
        public RootResource self() {
            return this;
        }

        @GET
        @Produces("text/plain")
        public String get() {
            return "self q=" + query;
        }

        @Path("/created")
        public Class<Created> created() {
            return Created.class;
        }

        @Path("/missing")
        public Shelf missing() {
            return null;
        }

        @GET
        @Path("/bean/{id}")
        @Produces("text/plain")
        public String bean(@BeanParam Values values) {
            return "bean " + values.id + " q=" + values.query + " h=" + values.header;
        }
    }

    public static class Shelf {
        private final String name;

        Shelf(String name) {
            this.name = name;
        }

        @GET
        @Path("/items/{id}")
        @Produces("text/plain")
        public String item(@PathParam("id") int id) {
            return "item " + id + " of shelf " + name;
        }

        @GET
        @Path("/anything")
        @Produces("text/plain")
        public String anything() {
            return "anything";
        }
    }

    public static class Created {
        @HeaderParam("X-Value")
        String header;

        @GET
        @Produces("text/plain")
        public String get() {
            return "created h=" + header;
        }
    }

    public static class Values {
        @PathParam("id")
        int id;

        @QueryParam("q")
        String query;

        @HeaderParam("X-Value")
        @DefaultValue("none")
        String header;
    }
}
