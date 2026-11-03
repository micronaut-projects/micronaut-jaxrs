package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Requires;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The paths of resource classes and methods in a {@link UriBuilder} and a {@link Link}, read from
 * the generated metadata, also without the micronaut-reflection module.
 */
class UriBuilderResourceTest {

    @Test
    void pathOfAResourceClass() {
        assertEquals("/a/TestPath", UriBuilder.fromPath("/a").path(TestPath.class).build().toString());
        assertEquals("/TestPath", UriBuilder.fromResource(TestPath.class).build().toString());
    }

    @Test
    void pathOfAResourceClassWithoutMethods() {
        assertEquals("empty", UriBuilder.fromResource(EmptyResource.class).build().toString());
    }

    @Test
    void pathOfAClassWithoutPath() {
        assertThrows(IllegalArgumentException.class, () -> UriBuilder.fromPath("/").path(NoPath.class));
    }

    @Test
    void pathOfAMethodByName() {
        assertEquals("/TestPath/sub", UriBuilder.fromPath("/TestPath").path(TestPath.class, "headSub").build().toString());
        assertThrows(IllegalArgumentException.class, () -> UriBuilder.fromPath("/TestPath").path(TestPath.class, "getPlain"));
        // two methods with the same name and a @Path
        assertThrows(IllegalArgumentException.class, () -> UriBuilder.fromPath("/TestPath").path(TestPath.class, "test1"));
    }

    @Test
    void pathOfAnInheritedMethodByName() {
        assertEquals("child/inherited", UriBuilder.fromResource(ChildResource.class).path(ChildResource.class, "inherited").build().toString());
    }

    @Test
    void pathOfALocatorClassMethodByName() {
        assertEquals("/x/item", UriBuilder.fromPath("/x").path(SubResource.class, "item").build().toString());
    }

    @Test
    void pathOfAMethod() throws NoSuchMethodException {
        assertEquals("/TestPath/sub", UriBuilder.fromPath("/TestPath").path(TestPath.class.getMethod("headSub")).build().toString());
        assertEquals("/TestPath/sub1", UriBuilder.fromPath("/TestPath").path(TestPath.class.getMethod("test1")).build().toString());
        assertEquals("/TestPath/sub2", UriBuilder.fromPath("/TestPath").path(TestPath.class.getMethod("test1", String.class)).build().toString());
        Method getPlain = TestPath.class.getMethod("getPlain");
        assertThrows(IllegalArgumentException.class, () -> UriBuilder.fromPath("/TestPath").path(getPlain));
    }

    @Test
    void pathOfAnInheritedMethod() throws NoSuchMethodException {
        assertEquals("child/inherited", UriBuilder.fromResource(ChildResource.class)
            .path(ChildResource.class.getMethod("inherited")).build().toString());
    }

    @Test
    void links() {
        assertEquals("resource", Link.fromResource(LinkResource.class).build().getUri().toString());
        assertEquals("get", Link.fromMethod(LinkResource.class, "get").build().getUri().toString());
    }

    @Requires(property = "spec.name", value = "UriBuilderResourceTest")
    @Path("/TestPath")
    public static class TestPath {
        @GET
        public Response getPlain() {
            return Response.ok().build();
        }

        @Path("/sub")
        public Response headSub() {
            return Response.ok().build();
        }

        @Path("sub1")
        public Response test1() {
            return Response.ok().build();
        }

        @Path("/sub2")
        public Response test1(@QueryParam("testName") String test) {
            return Response.ok(test).build();
        }
    }

    @Requires(property = "spec.name", value = "UriBuilderResourceTest")
    @Path("empty")
    public static class EmptyResource {
    }

    public static class NoPath {
    }

    public abstract static class ParentResource {
        @GET
        @Path("inherited")
        public String inherited() {
            return "inherited";
        }
    }

    @Requires(property = "spec.name", value = "UriBuilderResourceTest")
    @Path("child")
    public static class ChildResource extends ParentResource {
    }

    public static class SubResource {
        @GET
        @Path("item")
        public String item() {
            return "item";
        }
    }

    @Requires(property = "spec.name", value = "UriBuilderResourceTest")
    @Path("resource")
    public static class LinkResource {
        @GET
        @Path("get")
        public String get() {
            return "GET";
        }

        @POST
        @Produces("text/plain")
        @Path("post")
        public String post() {
            return "POST";
        }
    }
}
