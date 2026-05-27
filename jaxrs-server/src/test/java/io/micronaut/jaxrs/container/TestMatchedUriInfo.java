package io.micronaut.jaxrs.container;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;

import java.util.stream.Collectors;

@Path("/matched")
public class TestMatchedUriInfo {

    @GET
    @Path("/uris")
    @Produces(MediaType.TEXT_PLAIN)
    public String uris(@Context UriInfo uriInfo) {
        return String.join(",", uriInfo.getMatchedURIs());
    }

    @GET
    @Path("/resources")
    @Produces(MediaType.TEXT_PLAIN)
    public String resources(@Context UriInfo uriInfo) {
        return uriInfo.getMatchedResources()
            .stream()
            .map(resource -> resource.getClass().getName())
            .collect(Collectors.joining(","));
    }

    @GET
    @Path("/template/{id:[a-z]+}")
    @Produces(MediaType.TEXT_PLAIN)
    public String template(@Context UriInfo uriInfo) {
        return uriInfo.getMatchedResourceTemplate();
    }

    @Path("/subtemplate/{id:[a-z]+}")
    public TemplateSubResource subtemplate() {
        return new TemplateSubResource();
    }

    static class TemplateSubResource {

        @GET
        @Path("{child:[a-z]+}")
        @Produces(MediaType.TEXT_PLAIN)
        public String template(@Context UriInfo uriInfo) {
            return uriInfo.getMatchedResourceTemplate();
        }
    }
}
