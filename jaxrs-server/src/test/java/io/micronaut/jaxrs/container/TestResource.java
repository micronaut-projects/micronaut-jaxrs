package io.micronaut.jaxrs.container;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

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

    @QUERY
    @Path("/query")
    public String query(String body) {
        return "query " + body;
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @HttpMethod("QUERY")
    @Documented
    public @interface QUERY {
    }

}
