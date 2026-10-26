package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.PathSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Matrix parameters of the last path segment are bound to method and constructor parameters, and
 * are not part of the path the route matches.
 */
@MicronautTest
@Property(name = "spec.name", value = "MatrixParamTest")
class MatrixParamTest {

    @Inject
    @Client("/api/matrix-param")
    HttpClient client;

    @Test
    void matrixParamsAreBound() {
        assertEquals("red [a, b c] 2 owner=fred",
            client.toBlocking().retrieve(HttpRequest.GET("/cars;color=red;tags=a;tags=b%20c;size=2;owner=fred")));
    }

    @Test
    void missingMatrixParamsHaveTheirDefaults() {
        assertEquals("none [] 0 owner=nobody", client.toBlocking().retrieve(HttpRequest.GET("/cars")));
    }

    @Test
    void matrixParamsOnAMiddleSegmentDoNotChangeTheRoute() {
        assertEquals("details of 7", client.toBlocking().retrieve(HttpRequest.GET("/cars;color=red/7;trim=gt/details")));
    }

    @Test
    void pathSegmentsAreLocatedByTheirPosition() {
        assertEquals("same x=1 | same x=2", client.toBlocking().retrieve(HttpRequest.GET("/segments/same;x=1/same;x=2")));
        assertEquals("[same x=3, same x=4] | same x=5",
            client.toBlocking().retrieve(HttpRequest.GET("/segments/many/same;x=3/same;x=4/last/same;x=5")));
    }

    @Test
    void locatorMatrixParamsAreTheOnesOfItsLastSegment() {
        assertEquals("red details", client.toBlocking().retrieve(HttpRequest.GET("/located/1;color=red/details;color=blue")));
    }

    @Test
    void locatorMatrixParamsAreTheOnesOfTheLastSegmentOfARegexVariable() {
        assertEquals("red details", client.toBlocking().retrieve(HttpRequest.GET("/located/rest/a;color=blue/b;color=red/details")));
    }

    @Test
    void pathSegmentOfAVariableInPartOfASegment() {
        assertEquals("file-a.txt x=1", client.toBlocking().retrieve(HttpRequest.GET("/segments/file-a.txt;x=1")));
    }

    @Test
    void pathSegmentOfAVariableOverSeveralSegmentsIsTheLastOne() {
        assertEquals("b x=1", client.toBlocking().retrieve(HttpRequest.GET("/segments/last/a/b;x=1")));
    }

    @Test
    void encodedSlashIsNotASegmentSeparator() {
        assertEquals("[a/b x=null, c x=1] | d x=null",
            client.toBlocking().retrieve(HttpRequest.GET("/segments/many/a%2Fb/c;x=1/last/d")));
    }

    @Requires(property = "spec.name", value = "MatrixParamTest")
    @Path("/matrix-param/located")
    public static class LocatorResource {
        @Path("{id}")
        public Details details(@MatrixParam("color") String color) {
            return new Details(color);
        }

        // two segments: a greedy .+ would take the rest of the path too (JAX-RS 3.7.2, step 2)
        @Path("rest/{rest: [^/]+/[^/]+}")
        public Details rest(@MatrixParam("color") String color) {
            return new Details(color);
        }
    }

    /**
     * The sub-resource of {@link LocatorResource}.
     */
    public static class Details {
        private final String color;

        Details(String color) {
            this.color = color;
        }

        @GET
        @Path("details")
        @Produces("text/plain")
        public String get() {
            return color + " details";
        }
    }

    @Requires(property = "spec.name", value = "MatrixParamTest")
    @Path("/matrix-param/segments")
    public static class SegmentsResource {
        @GET
        @Path("{a}/{b}")
        @Produces("text/plain")
        public String segments(@PathParam("a") PathSegment a, @PathParam("b") PathSegment b) {
            return describe(a) + " | " + describe(b);
        }

        @GET
        @Path("file-{id}.txt")
        @Produces("text/plain")
        public String file(@PathParam("id") PathSegment id) {
            return describe(id);
        }

        @GET
        @Path("last/{p: .+}")
        @Produces("text/plain")
        public String last(@PathParam("p") PathSegment p) {
            return describe(p);
        }

        @GET
        @Path("many/{a: .+}/last/{b}")
        @Produces("text/plain")
        public String many(@PathParam("a") List<PathSegment> a, @PathParam("b") PathSegment b) {
            return a.stream().map(SegmentsResource::describe).toList() + " | " + describe(b);
        }

        private static String describe(PathSegment segment) {
            return segment.getPath() + " x=" + segment.getMatrixParameters().getFirst("x");
        }
    }

    @Requires(property = "spec.name", value = "MatrixParamTest")
    @Path("/matrix-param/cars")
    public static class CarsResource {
        private final String owner;

        public CarsResource(@MatrixParam("owner") @DefaultValue("nobody") String owner) {
            this.owner = owner;
        }

        @GET
        @Produces("text/plain")
        public String cars(@MatrixParam("color") @DefaultValue("none") String color,
                           @MatrixParam("tags") List<String> tags,
                           @MatrixParam("size") int size) {
            return color + " " + tags + " " + size + " owner=" + owner;
        }

        @GET
        @Path("{id}/details")
        @Produces("text/plain")
        public String details(@PathParam("id") int id) {
            return "details of " + id;
        }
    }
}
