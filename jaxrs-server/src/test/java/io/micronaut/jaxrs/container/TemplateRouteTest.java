package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MicronautTest
@Property(name = "spec.name", value = "TemplateRouteTest")
class TemplateRouteTest {

    @Inject
    @Client("/api/TemplateTest")
    HttpClient client;

    @Test
    void regexTemplateWinsOverDefaultTemplateForSingleSegment() {
        assertEquals("id1=xyz", client.toBlocking().retrieve("/xyz"));
    }

    @Test
    void regexTemplateWinsOverDefaultTemplateForNestedSegment() {
        assertEquals("id3=abc", client.toBlocking().retrieve("/xyz/abc"));
    }

    @Test
    void regexTemplateStillMatchesMultipleSegments() {
        assertEquals("id3=abc/def", client.toBlocking().retrieve("/xyz/abc/def"));
        assertEquals("id1=xy/abc/def", client.toBlocking().retrieve("/xy/abc/def"));
    }

    @Test
    void suffixTemplatesStillMatch() {
        assertEquals("id4=abc|name=test", client.toBlocking().retrieve("/abc/test.html"));
        assertEquals("id5=abc/def|name=test", client.toBlocking().retrieve("/abc/def/test.xml"));
    }

    @Requires(property = "spec.name", value = "TemplateRouteTest")
    @Path("/TemplateTest")
    static class TemplateResource {

        @GET
        @Path("{id}")
        String limited(@PathParam("id") String id) {
            return "id=" + id;
        }

        @GET
        @Path("xyz/{id}")
        String oneLimited(@PathParam("id") String id) {
            return "id2=" + id;
        }

        @GET
        @Path("{id}/{name}.html")
        String fileType(@PathParam("id") String id, @PathParam("name") String name) {
            return "id4=" + id + "|name=" + name;
        }

        @GET
        @Path("{id: .+}")
        String noLimits(@PathParam("id") String id) {
            return "id1=" + id;
        }

        @GET
        @Path("xyz/{id: .+}")
        String oneNoLimit(@PathParam("id") String id) {
            return "id3=" + id;
        }

        @GET
        @Path("{id: .+}/{name}.xml")
        String fileTypeXml(@PathParam("id") String id, @PathParam("name") String name) {
            return "id5=" + id + "|name=" + name;
        }
    }
}
