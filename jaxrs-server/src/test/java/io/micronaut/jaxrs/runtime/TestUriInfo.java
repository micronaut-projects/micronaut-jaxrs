package io.micronaut.jaxrs.runtime;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;

public class TestUriInfo {

    @GET
    @Path("/uri-info")
    @Produces(MediaType.TEXT_PLAIN)
    public String test(@Context UriInfo uriInfo) {
        return "test path: " + uriInfo.getPath();
    }
}
