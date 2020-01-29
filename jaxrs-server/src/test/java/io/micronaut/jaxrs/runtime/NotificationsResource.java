package io.micronaut.jaxrs.runtime;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.annotation.QueryValue;
import org.junit.jupiter.api.Assertions;

import javax.validation.constraints.Min;
import javax.ws.rs.*;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path("/notifications")
public class NotificationsResource {

    private final String serviceName;

    public NotificationsResource(@Value("${service.name}") String serviceName) {
        this.serviceName = serviceName;
    }


    @GET
    @Path("/ping")
    public Response ping(@Context Application application) {
        final Object o = application.getProperties().get("service.name");
        Assertions.assertEquals(serviceName, o);
        return Response.ok().entity("Service online: " + o).build();
    }

    @GET
    @Path("/get/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getNotification(@Min(1) @PathParam("id") int id) {
        return Response.ok()
                .entity(new Notification(id, "john", "test notification"))
                .build();
    }

    @GET
    @Path("/query")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getDefault(@DefaultValue("10") @QueryValue("id") int id) {
        return Response.ok()
                .entity(new Notification(id, "john", "test notification"))
                .build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response postNotification(Notification notification) {
        return Response.status(201).entity(notification).build();
    }
}
