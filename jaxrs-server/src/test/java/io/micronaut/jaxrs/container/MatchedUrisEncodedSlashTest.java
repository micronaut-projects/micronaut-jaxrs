package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The matched URIs are prefixes of the encoded path: an encoded slash is not a separator
 * (UriInfo.getMatchedURIs).
 */
@MicronautTest
@Property(name = "spec.name", value = "MatchedUrisEncodedSlashTest")
class MatchedUrisEncodedSlashTest {

    @Inject
    @Client("/api")
    HttpClient client;

    @Test
    void matchedUrisKeepAnEncodedSlashInASegment() {
        assertEquals("[uriseg/a/b/leaf, uriseg/a/b]|[uriseg/a%2Fb/leaf, uriseg/a%2Fb]",
            client.toBlocking().retrieve(HttpRequest.GET("/uriseg/a%2Fb/leaf"), String.class));
    }

    @Requires(property = "spec.name", value = "MatchedUrisEncodedSlashTest")
    @Path("/uriseg/{id}")
    public static class SegmentResource {
        @GET
        @Path("leaf")
        @Produces("text/plain")
        public String leaf(@PathParam("id") String id, @Context UriInfo uriInfo) {
            return uriInfo.getMatchedURIs() + "|" + uriInfo.getMatchedURIs(false);
        }
    }
}
