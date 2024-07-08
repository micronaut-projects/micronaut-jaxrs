package io.micronaut.jaxrs.runtime;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/greeter")
public interface GreeterService {
    @GET
    @Path("/string")
    @Produces(MediaType.TEXT_PLAIN)
    String greet();
}
