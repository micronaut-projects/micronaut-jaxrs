package io.micronaut.jaxrs.runtime;

import javax.ws.rs.*;

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