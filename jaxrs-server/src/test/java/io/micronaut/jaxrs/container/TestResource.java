package io.micronaut.jaxrs.container;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;

@Path("/test-method")
public class TestResource {


    @PUT
    @Path("/put")
    public String put() {
        return "put";
    }

    @GET
    @Path("/get")
    public String get() {
        return "get";
    }

    @DELETE
    @Path("/delete")
    public String delete() {
        return "delete";
    }

    @POST
    @Path("/post")
    public String post() {
        return "post";
    }

    @OPTIONS
    @Path("/options")
    public String options() {
        return "options";
    }

}
