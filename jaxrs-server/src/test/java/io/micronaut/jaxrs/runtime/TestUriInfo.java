package io.micronaut.jaxrs.runtime;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;

public class TestUriInfo {

    @GET
    @Path("/uri-info")
    @Produces(MediaType.TEXT_PLAIN)
    public String test(@Context UriInfo uriInfo) {
        return "test path: " + uriInfo.getPath();
    }
}
