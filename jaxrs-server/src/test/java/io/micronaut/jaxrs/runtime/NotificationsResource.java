package io.micronaut.jaxrs.runtime;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.hateoas.JsonError;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import java.util.concurrent.ExecutorService;
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
    public Response ping(
        @Context Application application,
        // tests named injection
        @Context @Named(TaskExecutors.IO) ExecutorService executorService) {
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

    /**
     * PathParam annotation has value `newId` equal to path variable `newId`,
     * but variable `int id` is not equal to path variable `newId`
     */
    @GET
    @Path("/get/v2/{newId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getNotificationV2(@Min(1) @PathParam("newId") int id) {
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

    @GET
    @Path("/bad-request")
    @Produces(MediaType.APPLICATION_JSON)
    public Response badRequest(){
        throw new BadRequestException(Response.status(Response.Status.BAD_REQUEST)
                .entity(new JsonError("Testing bad-request")).build());
    }

    @GET
    @Path("/forbidden")
    @Produces(MediaType.APPLICATION_JSON)
    public Response forbidden(){
        throw new ForbiddenException(Response.status(Response.Status.FORBIDDEN)
                .entity(new JsonError("Testing forbidden")).build());
    }

    @GET
    @Path("/not-acceptable")
    @Produces(MediaType.APPLICATION_JSON)
    public Response notAcceptable(){
        throw new NotAcceptableException(Response.status(Response.Status.NOT_ACCEPTABLE)
                .entity(new JsonError("Testing not-acceptable")).build());
    }

    @GET
    @Path("/not-allowed")
    @Produces(MediaType.APPLICATION_JSON)
    public Response notAllowed(){
        throw new NotAllowedException(Response.status(Response.Status.METHOD_NOT_ALLOWED)
                .entity(new JsonError("Testing not-allowed")).build());
    }

    @GET
    @Path("/not-authorized")
    @Produces(MediaType.APPLICATION_JSON)
    public Response notAuthorized(){
        throw new NotAuthorizedException(Response.status(Response.Status.UNAUTHORIZED)
                .entity(new JsonError("Testing not-authorized")).build());
    }

    @GET
    @Path("/not-found")
    @Produces(MediaType.APPLICATION_JSON)
    public Response notFound() {
        throw new NotFoundException(Response.status(Response.Status.NOT_FOUND)
                .entity(new JsonError("Testing not-found")).build());
    }

    @GET
    @Path("/not-supported")
    @Produces(MediaType.APPLICATION_JSON)
    public Response notSupported() {
        throw new NotSupportedException(Response.status(Response.Status.UNSUPPORTED_MEDIA_TYPE)
                .entity(new JsonError("Testing not-supported")).build());
    }
}
