package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.ResourceContext;
import jakarta.ws.rs.container.Suspended;
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
        assertEquals("a q1 h1 resource-context/get/a",
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
        assertEquals("c q3 h3 resource-context/init/c",
            client.toBlocking().retrieve(HttpRequest.GET("/init/c?q=q3").header("X-H", "h3")));
    }

    @Test
    void formMembersAreReadFromTheEntity() {
        assertEquals("body body", client.toBlocking().retrieve(HttpRequest.POST("/form?x=query", "x=body")
            .contentType(io.micronaut.http.MediaType.APPLICATION_FORM_URLENCODED_TYPE)));
    }

    @Test
    void formMembersAreReadFromTheEntityOfAMethodWithoutFormParameters() {
        assertEquals("body body", client.toBlocking().retrieve(HttpRequest.POST("/context-form", "x=body")
            .contentType(io.micronaut.http.MediaType.APPLICATION_FORM_URLENCODED_TYPE)));
    }

    @Test
    void formMembersAreReadFromTheEntityOfAMethodWithAnEntityParameter() {
        assertEquals("body body x=body", client.toBlocking().retrieve(HttpRequest.POST("/context-form-entity", "x=body")
            .contentType(io.micronaut.http.MediaType.APPLICATION_FORM_URLENCODED_TYPE)));
    }

    @Test
    void encodedFormMembersHaveTheRawValues() {
        assertEquals("a%20b! a%20b! a b!", client.toBlocking().retrieve(HttpRequest.POST("/encoded-form", "x=a%20b!")
            .contentType(io.micronaut.http.MediaType.APPLICATION_FORM_URLENCODED_TYPE)));
    }

    @Test
    void encodedFormMembersOfAMethodWithAnEntityParameterHaveTheRawValues() {
        assertEquals("a%20b! x=a%20b!", client.toBlocking().retrieve(HttpRequest.POST("/encoded-form-entity", "x=a%20b!")
            .contentType(io.micronaut.http.MediaType.APPLICATION_FORM_URLENCODED_TYPE)));
    }

    @Test
    void getResourceInALocatorReadsThePathParametersOfTheLocator() {
        assertEquals("abc abc", client.toBlocking().retrieve(HttpRequest.GET("/lc/abc/get/leaf")));
    }

    @Test
    void initResourceInALocatorReadsThePathParametersOfTheLocator() {
        assertEquals("abc abc", client.toBlocking().retrieve(HttpRequest.GET("/lc/abc/init/leaf")));
    }

    @Test
    void initResourceSetsTheMembersBeforePostConstruct() {
        assertEquals("value|value", client.toBlocking().retrieve(HttpRequest.GET("/post-construct?x=value")));
    }

    @Test
    void encodedFormMembersOfAnAsynchronousMethodHaveTheRawValues() {
        assertEquals("a%20b! a b!", client.toBlocking().retrieve(HttpRequest.POST("/encoded-form-async", "x=a%20b!")
            .contentType(io.micronaut.http.MediaType.APPLICATION_FORM_URLENCODED_TYPE)));
    }

    @Test
    void formMembersAreReadFromTheEntityForAResourceContextOfABeanParameter() {
        assertEquals("body", client.toBlocking().retrieve(HttpRequest.POST("/bean-context-form", "x=body")
            .contentType(io.micronaut.http.MediaType.APPLICATION_FORM_URLENCODED_TYPE)));
    }

    @Test
    void formMembersAreReadFromTheEntityForAResourceContextOfABeanParameterOfAMethodWithAnEntity() {
        assertEquals("body x=body", client.toBlocking().retrieve(HttpRequest.POST("/bean-context-form-entity", "x=body")
            .contentType(io.micronaut.http.MediaType.APPLICATION_FORM_URLENCODED_TYPE)));
    }

    @Test
    void formMembersAreReadFromTheEntityForAResourceContextOfABeanParameterField() {
        assertEquals("body", client.toBlocking().retrieve(HttpRequest.POST("/bean-field/form", "x=body")
            .contentType(io.micronaut.http.MediaType.APPLICATION_FORM_URLENCODED_TYPE)));
    }

    @Requires(property = "spec.name", value = "ResourceContextTest")
    @Path("/resource-context/bean-field")
    public static class BeanFieldResource {
        @BeanParam
        ContextBean bean;

        @POST
        @Path("/form")
        public String form() {
            return bean.resourceContext.getResource(FormMember.class).x;
        }
    }

    @Requires(property = "spec.name", value = "ResourceContextTest")
    @Path("/resource-context")
    public static class RootResource {
        @POST
        @Path("/bean-context-form-entity")
        public String beanContextFormEntity(@BeanParam ContextBean bean, String body) {
            return bean.resourceContext.getResource(FormMember.class).x + " " + body;
        }

        @Path("/lc/{id}/get")
        public LocatedChild locateGet(@PathParam("id") String id) {
            return resourceContext.getResource(LocatedChild.class).located(id);
        }

        @Path("/lc/{id}/init")
        public LocatedChild locateInit(@PathParam("id") String id) {
            return resourceContext.initResource(new LocatedChild()).located(id);
        }

        @GET
        @Path("/post-construct")
        public String postConstruct() {
            return resourceContext.initResource(new PostConstructed()).describe();
        }

        @POST
        @Path("/encoded-form-async")
        @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
        public void encodedFormAsync(@FormParam("x") String x, @Suspended AsyncResponse response) {
            response.resume(resourceContext.getResource(EncodedFormMember.class).x + " " + x);
        }

        @POST
        @Path("/bean-context-form")
        public String beanContextForm(@BeanParam ContextBean bean) {
            return bean.resourceContext.getResource(FormMember.class).x;
        }
        @POST
        @Path("/encoded-form-entity")
        public String encodedFormEntity(String body) {
            return resourceContext.getResource(EncodedFormMember.class).x + " " + body;
        }

        @POST
        @Path("/encoded-form")
        @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
        public String encodedForm(@FormParam("x") String x) {
            return resourceContext.getResource(EncodedFormMember.class).x + " " + resourceContext.initResource(new EncodedFormMember()).x
                + " " + x;
        }

        @POST
        @Path("/form")
        @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
        public String form(@FormParam("x") String x) {
            return resourceContext.getResource(FormMember.class).x + " " + resourceContext.initResource(new FormMember()).x;
        }

        @POST
        @Path("/context-form")
        public String contextForm() {
            return resourceContext.getResource(FormMember.class).x + " " + resourceContext.initResource(new FormMember()).x;
        }

        @POST
        @Path("/context-form-entity")
        public String contextFormEntity(String body) {
            return resourceContext.getResource(FormMember.class).x + " " + resourceContext.initResource(new FormMember()).x + " " + body;
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
     * A bean parameter with the resource context.
     */
    public static class ContextBean {
        @Context
        ResourceContext resourceContext;
    }

    /**
     * A sub-resource with a path parameter of its locator.
     */
    public static class LocatedChild {
        @PathParam("id")
        String id;

        private String locatedId;

        LocatedChild located(String locatedId) {
            this.locatedId = locatedId;
            return this;
        }

        @GET
        @Path("/leaf")
        public String leaf() {
            return id + " " + locatedId;
        }
    }

    @Requires(property = "spec.name", value = "ResourceContextTest")
    @Path("/post-constructed")
    public static class PostConstructed {
        @QueryParam("x")
        String x;

        private String copy;

        @PostConstruct
        void init() {
            copy = x;
        }

        @GET
        public String get() {
            return describe();
        }

        String describe() {
            return x + "|" + copy;
        }
    }

    /**
     * A resource with an encoded form member.
     */
    public static class EncodedFormMember {
        @Encoded
        @FormParam("x")
        String x;
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
