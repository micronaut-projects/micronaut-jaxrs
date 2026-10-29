package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The matching of sub-resource locators in stages (JAX-RS 3.7.2).
 */
@MicronautTest
@Property(name = "spec.name", value = "LocatorMatchingTest")
class LocatorMatchingTest {

    @Inject
    @Client("/api")
    HttpClient client;

    @Test
    void locatorParameterIsTheValueOfItsOwnTemplate() {
        // PathParam: the latest use within the scope of the locator
        assertEquals("two/three", client.toBlocking().retrieve(HttpRequest.GET("/repeat/one/sub/two/three")));
    }

    @Test
    void routesOfTheRuntimeClassOfTheTarget() {
        // JAX-RS 3.4.1: the class of the returned object, not the declared type
        assertEquals("child", client.toBlocking().retrieve(HttpRequest.GET("/poly/sub/child")));
        assertEquals("base", client.toBlocking().retrieve(HttpRequest.GET("/poly/sub/base")));
    }

    @Test
    void matchingCommitsToTheMostSpecificLocator() {
        // JAX-RS 3.7.2, step 2: locator a is selected, its target has no x/long
        assertEquals("a", client.toBlocking().retrieve(HttpRequest.GET("/branch/a/x")));
        assertEquals("id", client.toBlocking().retrieve(HttpRequest.GET("/branch/b/x/long")));
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.GET("/branch/a/x/long")));
        assertEquals(404, e.getStatus().getCode());
    }

    @Test
    void resourceMethodIsSelectedBeforeALocatorWithTheSamePath() {
        // JAX-RS 3.7.2, step 2(f): a sub-resource method before a locator
        assertEquals("method", client.toBlocking().retrieve(HttpRequest.GET("/prec/locator")));
        // the path selects the resource method, whatever the HTTP method
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.DELETE("/prec/locator")));
        assertEquals(405, e.getStatus().getCode());
    }

    @Test
    void formLocatorParameterIsTheValueOfItsOwnTemplate() {
        // a locator that reads the form, whose target reads it too
        assertEquals("two/three/f/f", post("/formrepeat/one/sub/two/three"));
    }

    @Test
    void formLocatorRoutesOfTheRuntimeClassOfTheTarget() {
        assertEquals("child/f", post("/formpoly/sub/child"));
        assertEquals("base/f", post("/formpoly/sub/base"));
    }

    @Test
    void formLocatorMatchingCommitsToTheMostSpecificLocator() {
        assertEquals("a/f", post("/formbranch/a/x"));
        assertEquals("id/f", post("/formbranch/b/x/long"));
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class, () -> post("/formbranch/a/x/long"));
        assertEquals(404, e.getStatus().getCode());
    }

    @Test
    void locatorUnderAResourceThatReadsTheForm() {
        // the root resource reads the form in a field: its locator reads the form, and so its target
        assertEquals("f/sub/f", post("/formfield/sub"));
    }

    @Test
    void formLocatorWithoutAForm() {
        // a request that is not a form is located with the empty form
        assertEquals("a/null", client.toBlocking().retrieve(HttpRequest.GET("/formbranch/a/get")));
    }

    @Test
    void nestedLocatorsWithTheSameRegexVariable() {
        // the matrix parameters of a nested locator are the ones of the last segment of its own
        // prefix, whose variable has the name of one of the prefix of the outer locator, with
        // values that span other numbers of segments
        assertEquals("c:2", client.toBlocking().retrieve(HttpRequest.GET("/nest/a/b/x/c/end;m=2")));
        assertEquals("c/d:2", client.toBlocking().retrieve(HttpRequest.GET("/nest/a/x/c/d/end;m=2")));
        assertEquals("c/d:2", client.toBlocking().retrieve(HttpRequest.GET("/nest/a/b/c/x/c/d/end;m=2")));
        assertEquals("c:2", client.toBlocking().retrieve(HttpRequest.GET("/nest/a/b/c/x/c/end;m=2")));
    }

    private String post(String uri) {
        return client.toBlocking().retrieve(HttpRequest.POST(uri, Map.of("f", "f")).contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE));
    }

    @Requires(property = "spec.name", value = "LocatorMatchingTest")
    @Path("/prec")
    public static class PrecedenceResource {
        @Path("locator")
        public PrecedenceTarget locator() {
            return new PrecedenceTarget();
        }

        @GET
        @Path("locator")
        @Produces("text/plain")
        public String method() {
            return "method";
        }
    }

    public static class PrecedenceTarget {
        @GET
        @Produces("text/plain")
        public String get() {
            return "located";
        }
    }

    @Requires(property = "spec.name", value = "LocatorMatchingTest")
    @Path("/repeat/{id}")
    public static class RepeatResource {
        @Path("sub/{id}")
        public RepeatLeaf sub(@PathParam("id") String id) {
            return new RepeatLeaf(id);
        }
    }

    public static class RepeatLeaf {
        private final String located;

        public RepeatLeaf(String located) {
            this.located = located;
        }

        @GET
        @Path("{id}")
        @Produces("text/plain")
        public String get(@PathParam("id") String id) {
            return located + "/" + id;
        }
    }

    @Requires(property = "spec.name", value = "LocatorMatchingTest")
    @Path("/poly")
    public static class PolyResource {
        @Path("sub")
        public PolyBase sub() {
            return new PolyChild();
        }
    }

    public static class PolyBase {
        @GET
        @Path("base")
        @Produces("text/plain")
        public String base() {
            return "base";
        }
    }

    public static class PolyChild extends PolyBase {
        @GET
        @Path("child")
        @Produces("text/plain")
        public String child() {
            return "child";
        }
    }

    @Requires(property = "spec.name", value = "LocatorMatchingTest")
    @Path("/branch")
    public static class BranchResource {
        @Path("a")
        public BranchA a() {
            return new BranchA();
        }

        @Path("{id}")
        public BranchId id() {
            return new BranchId();
        }
    }

    public static class BranchA {
        @GET
        @Path("x")
        @Produces("text/plain")
        public String x() {
            return "a";
        }
    }

    public static class BranchId {
        @GET
        @Path("x/long")
        @Produces("text/plain")
        public String x() {
            return "id";
        }
    }

    @Requires(property = "spec.name", value = "LocatorMatchingTest")
    @Path("/formrepeat/{id}")
    public static class FormRepeatResource {
        @Path("sub/{id}")
        public FormRepeatLeaf sub(@PathParam("id") String id, @FormParam("f") String f) {
            return new FormRepeatLeaf(id + "/" + f);
        }
    }

    public static class FormRepeatLeaf {
        private final String located;

        public FormRepeatLeaf(String located) {
            this.located = located;
        }

        @POST
        @Path("{id}")
        @Produces("text/plain")
        public String post(@PathParam("id") String id, @FormParam("f") String f) {
            return first(located) + "/" + id + "/" + second(located) + "/" + f;
        }

        private static String first(String value) {
            return value.substring(0, value.indexOf('/'));
        }

        private static String second(String value) {
            return value.substring(value.indexOf('/') + 1);
        }
    }

    @Requires(property = "spec.name", value = "LocatorMatchingTest")
    @Path("/formpoly")
    public static class FormPolyResource {
        @Path("sub")
        public FormPolyBase sub(@FormParam("f") String f) {
            return new FormPolyChild();
        }
    }

    public static class FormPolyBase {
        @POST
        @Path("base")
        @Produces("text/plain")
        public String base(@FormParam("f") String f) {
            return "base/" + f;
        }
    }

    public static class FormPolyChild extends FormPolyBase {
        @POST
        @Path("child")
        @Produces("text/plain")
        public String child(@FormParam("f") String f) {
            return "child/" + f;
        }
    }

    @Requires(property = "spec.name", value = "LocatorMatchingTest")
    @Path("/formbranch")
    public static class FormBranchResource {
        @Path("a")
        public FormBranchA a(@FormParam("f") String f) {
            return new FormBranchA(f);
        }

        @Path("{id}")
        public FormBranchId id(@FormParam("f") String f) {
            return new FormBranchId();
        }
    }

    public static class FormBranchA {
        private final String f;

        public FormBranchA(String f) {
            this.f = f;
        }

        @POST
        @Path("x")
        @Produces("text/plain")
        public String x(@FormParam("f") String form) {
            return "a/" + form;
        }

        @GET
        @Path("get")
        @Produces("text/plain")
        public String get() {
            return "a/" + f;
        }
    }

    public static class FormBranchId {
        @POST
        @Path("x/long")
        @Produces("text/plain")
        public String x(@FormParam("f") String f) {
            return "id/" + f;
        }
    }

    @Requires(property = "spec.name", value = "LocatorMatchingTest")
    @Path("/formfield")
    public static class FormFieldResource {
        @FormParam("f")
        String f;

        @Path("sub")
        public FormFieldLeaf sub() {
            return new FormFieldLeaf(f);
        }
    }

    public static class FormFieldLeaf {
        private final String located;

        public FormFieldLeaf(String located) {
            this.located = located;
        }

        @POST
        @Produces("text/plain")
        public String post(@FormParam("f") String f) {
            return located + "/sub/" + f;
        }
    }

    @Requires(property = "spec.name", value = "LocatorMatchingTest")
    @Path("/nest")
    public static class NestResource {
        @Path("{id: .+}/x")
        public NestInner inner() {
            return new NestInner();
        }
    }

    public static class NestInner {
        @Path("{id: .+}/end")
        public NestLeaf leaf(@PathParam("id") String id, @MatrixParam("m") String m) {
            return new NestLeaf(id + ":" + m);
        }
    }

    public static class NestLeaf {
        private final String located;

        public NestLeaf(String located) {
            this.located = located;
        }

        @GET
        @Produces("text/plain")
        public String get() {
            return located;
        }
    }
}
