package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ResourceContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ResourceContext} initializes the resources it creates or is given in the context of the
 * current request: their context members and their values of the request.
 */
@MicronautTest
@Property(name = "spec.name", value = "ResourceContextTest")
class ResourceContextTest {

    @Inject
    @Client("/api/resource-context")
    HttpClient client;

    @Test
    void getResourceInitializesTheResource() {
        assertEquals("a q1 h1 /resource-context/get/a",
            client.toBlocking().retrieve(HttpRequest.GET("/get/a?q=q1").header("X-H", "h1")));
    }

    @Test
    void getResourceCreatesAResourceWithRequestValuesInItsConstructor() {
        assertEquals("b q2", client.toBlocking().retrieve(HttpRequest.GET("/constructor/b?q=q2")));
    }

    @Test
    void getResourceGivesAnEncodedPathParamMemberTheRawValue() {
        assertEquals("a%2Fb", client.toBlocking().retrieve(HttpRequest.GET("/encoded/a%2Fb")));
    }

    @Test
    void initResourceInitializesTheResource() {
        assertEquals("c q3 h3 /resource-context/init/c",
            client.toBlocking().retrieve(HttpRequest.GET("/init/c?q=q3").header("X-H", "h3")));
    }

    @Test
    void formMembersAreReadFromTheEntity() {
        assertEquals("body body", client.toBlocking().retrieve(HttpRequest.POST("/form?x=query", "x=body")
            .contentType(io.micronaut.http.MediaType.APPLICATION_FORM_URLENCODED_TYPE)));
    }

    @Requires(property = "spec.name", value = "ResourceContextTest")
    @Path("/resource-context")
    public static class RootResource {
        @POST
        @Path("/form")
        @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
        public String form(@FormParam("x") String x) {
            return resourceContext.getResource(FormMember.class).x + " " + resourceContext.initResource(new FormMember()).x;
        }

        @Context
        ResourceContext resourceContext;

        @GET
        @Path("/encoded/{id}")
        public String encoded() {
            return resourceContext.getResource(EncodedMember.class).id;
        }

        @GET
        @Path("/get/{id}")
        public String get() {
            return resourceContext.getResource(Initialized.class).describe();
        }

        @GET
        @Path("/constructor/{id}")
        public String constructor() {
            return resourceContext.getResource(Constructed.class).describe();
        }

        @GET
        @Path("/init/{id}")
        public String init() {
            return resourceContext.initResource(new Initialized()).describe();
        }
    }

    /**
     * A resource with a form member.
     */
    public static class FormMember {
        @FormParam("x")
        String x;
    }

    @Requires(property = "spec.name", value = "ResourceContextTest")
    @Path("/initialized")
    public static class Initialized {
        @PathParam("id")
        String id;

        @QueryParam("q")
        String query;

        @Context
        UriInfo uriInfo;

        private String header;

        @HeaderParam("X-H")
        public void setHeader(String header) {
            this.header = header;
        }

        @GET
        public String get() {
            return describe();
        }

        String describe() {
            return id + " " + query + " " + header + " " + uriInfo.getPath();
        }
    }

    @Requires(property = "spec.name", value = "ResourceContextTest")
    @Path("/constructed")
    public static class Constructed {
        private final String id;
        private final String query;

        public Constructed(@PathParam("id") String id, @QueryParam("q") String query) {
            this.id = id;
            this.query = query;
        }

        @GET
        public String get() {
            return describe();
        }

        String describe() {
            return id + " " + query;
        }
    }

    @Requires(property = "spec.name", value = "ResourceContextTest")
    @Path("/encoded-member")
    public static class EncodedMember {
        @Encoded
        @PathParam("id")
        String id;

        @GET
        public String get() {
            return id;
        }
    }
}
