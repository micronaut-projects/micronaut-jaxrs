package io.micronaut.jaxrs.runtime;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;

import io.micronaut.http.HttpResponse;

public interface InterfaceResource {

    @GET
    @Path("/ping/{v}")
    @Produces("text/plain")
    @Consumes("text/plain")
    HttpResponse<String> ping(@PathParam("v") String value);

}
