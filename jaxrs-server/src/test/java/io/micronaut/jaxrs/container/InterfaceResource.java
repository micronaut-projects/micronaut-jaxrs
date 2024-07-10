package io.micronaut.jaxrs.container;

import io.micronaut.http.HttpResponse;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;

public interface InterfaceResource {

    @GET
    @Path("/ping/{v}")
    @Produces("text/plain")
    @Consumes("text/plain")
    HttpResponse<String> ping(@PathParam("v") String value);

    @GET
    @Produces("text/plain")
    @Consumes("text/plain")
    HttpResponse<String> noPathPing();

}
