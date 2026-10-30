package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The selection of the root resource class and of the resource method by the path, and the
 * negotiated response type (JAX-RS 3.7, 3.8).
 */
@MicronautTest
@Property(name = "spec.name", value = "ServerRoutingTest")
class ServerRoutingTest {

    @Inject
    @Client("/api")
    HttpClient client;

    private int status(MutableHttpRequest<?> request) {
        try {
            return client.toBlocking().exchange(request, String.class).getStatus().getCode();
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    private String get(String path) {
        return client.toBlocking().retrieve(HttpRequest.GET(path), String.class);
    }

    @Test
    void anEmptyRestSelectsTheResourceMethodsOfAnyHttpMethod() {
        // JAX-RS 3.7.2, step 2(a): the POST resource method is the only candidate
        assertEquals(405, status(HttpRequest.GET("/emptyverb/")));
        HttpResponse<String> options = client.toBlocking().exchange(HttpRequest.OPTIONS("/emptyverb/"), String.class);
        assertFalse(options.getHeaders().get("Allow").contains("GET"), options.getHeaders().get("Allow"));
        assertEquals("sub:a", get("/emptyverb/a"));
    }

    @Test
    void anEmptyRestOfALocatedTargetSelectsItsResourceMethods() {
        assertEquals(406, status(HttpRequest.GET("/emptyloc/loc").accept("application/xml")));
        assertEquals("located-sub:a", client.toBlocking().retrieve(HttpRequest.GET("/emptyloc/loc/a").accept("application/xml"), String.class));
    }

    @Test
    void theRootMatchIsNotBacktrackedForTheMethodPath() {
        assertEquals(404, status(HttpRequest.GET("/greedy/a/tail")));
    }

    @Test
    void anEmptyTrailingCaptureCommitsToTheRoot() {
        assertEquals(404, status(HttpRequest.GET("/emptyroot/")));
    }

    @Test
    void equivalentEncodedRootsAreCandidatesTogether() {
        assertEquals("get", get("/unicode/%C3%A9"));
        assertEquals("post", client.toBlocking().retrieve(HttpRequest.POST("/unicode/%C3%A9", "x").contentType("text/plain"), String.class));
    }

    @Test
    void aTrailingSlashIsKeptInARegexCapture() {
        assertEquals("x=a/", get("/trailing/a/"));
        assertEquals("loc=a/", get("/trailingloc/a/"));
    }

    @Test
    void explicitlyEmptyMediaTypesDoNotInheritTheClassOnes() {
        assertEquals(200, status(HttpRequest.GET("/emptymedia").accept("application/json")));
        assertEquals(200, status(HttpRequest.POST("/emptymedia", "{}").contentType("application/json")));
    }

    @Test
    void octetStreamFallbackHonorsRejectedRanges() {
        assertEquals(406, status(HttpRequest.GET("/fallback").header("Accept", "text/*;q=1, application/*;q=0, */*;q=0.5")));
    }

    @Test
    void parameterValuesAreCaseSensitive() {
        assertEquals(200, status(HttpRequest.GET("/profile")
            .header("Accept", "application/ld+json;profile=\"https://example.test/a\";q=0, application/ld+json")));
    }

    @Test
    void anUppercaseApplicationWildcardFallsBackToOctetStream() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/upper").accept("*/*"), String.class);
        assertEquals(200, response.getStatus().getCode());
        assertTrue(response.getHeaders().get("Content-Type").startsWith("application/octet-stream"), response.getHeaders().get("Content-Type"));
    }

    @Requires(property = "spec.name", value = "ServerRoutingTest")
    @Path("/emptyverb")
    public static class EmptyVerbResource {
        @POST
        public String post() {
            return "post";
        }

        @GET
        @Path("{x:.*}")
        @Produces("text/plain")
        public String sub(@PathParam("x") String x) {
            return "sub:" + x;
        }
    }

    @Requires(property = "spec.name", value = "ServerRoutingTest")
    @Path("/emptyloc")
    public static class EmptyRestLocatorResource {
        @Path("loc")
        public EmptyRestTarget locate() {
            return new EmptyRestTarget();
        }
    }

    public static class EmptyRestTarget {
        @GET
        @Produces("text/plain")
        public String get() {
            return "located";
        }

        @GET
        @Path("{x:.*}")
        @Produces("application/xml")
        public String sub(@PathParam("x") String x) {
            return "located-sub:" + x;
        }
    }

    @Requires(property = "spec.name", value = "ServerRoutingTest")
    @Path("/greedy/{x:.+}")
    public static class GreedyResource {
        @GET
        @Path("tail")
        @Produces("text/plain")
        public String tail(@PathParam("x") String x) {
            return "x=" + x;
        }
    }

    @Requires(property = "spec.name", value = "ServerRoutingTest")
    @Path("/emptyroot/{x:.*}")
    public static class EmptyRootResource {
        @GET
        @Path("only")
        @Produces("text/plain")
        public String only(@PathParam("x") String x) {
            return "only:" + x;
        }
    }

    @Requires(property = "spec.name", value = "ServerRoutingTest")
    @Path("{any:.*}")
    public static class CatchAllResource {
        @GET
        @Produces("text/plain")
        public String get() {
            return "catch-all";
        }
    }

    @Requires(property = "spec.name", value = "ServerRoutingTest")
    @Path("/unicode/é")
    public static class UnencodedResource {
        @GET
        @Produces("text/plain")
        public String get() {
            return "get";
        }
    }

    @Requires(property = "spec.name", value = "ServerRoutingTest")
    @Path("/unicode/%C3%A9")
    public static class EncodedResource {
        @POST
        @Produces("text/plain")
        public String post(String body) {
            return "post";
        }
    }

    @Requires(property = "spec.name", value = "ServerRoutingTest")
    @Path("/trailing")
    public static class TrailingResource {
        @GET
        @Path("{x:.*}")
        @Produces("text/plain")
        public String get(@PathParam("x") String x) {
            return "x=" + x;
        }
    }

    @Requires(property = "spec.name", value = "ServerRoutingTest")
    @Path("/trailingloc")
    public static class TrailingLocatorResource {
        @Path("{x:.*}")
        public TrailingTarget locate(@PathParam("x") String x) {
            return new TrailingTarget(x);
        }
    }

    public static class TrailingTarget {
        private final String x;

        TrailingTarget(String x) {
            this.x = x;
        }

        @GET
        @Produces("text/plain")
        public String get() {
            return "loc=" + x;
        }
    }

    @Requires(property = "spec.name", value = "ServerRoutingTest")
    @Path("/emptymedia")
    @Produces("text/plain")
    @Consumes("text/plain")
    public static class EmptyMediaResource {
        @GET
        @Produces({})
        public String get() {
            return "\"get\"";
        }

        @POST
        @Consumes({})
        public String post(String body) {
            return "post";
        }
    }

    @Requires(property = "spec.name", value = "ServerRoutingTest")
    @Path("/fallback")
    public static class FallbackResource {
        @GET
        @Produces({"text/*", "application/*"})
        public String get() {
            return "fallback";
        }
    }

    @Requires(property = "spec.name", value = "ServerRoutingTest")
    @Path("/profile")
    public static class ProfileResource {
        @GET
        @Produces("application/ld+json;profile=\"https://example.test/A\"")
        public String get() {
            return "{}";
        }
    }

    @Requires(property = "spec.name", value = "ServerRoutingTest")
    @Path("/upper")
    public static class UppercaseWildcardResource {
        @GET
        @Produces("APPLICATION/*")
        public String get() {
            return "upper";
        }
    }
}
