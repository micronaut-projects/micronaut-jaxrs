package io.micronaut.jaxrs.runtime;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotAcceptableException;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Assertions;

import javax.validation.constraints.Min;
import java.util.concurrent.ExecutorService;

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
        throw new BadRequestException("Testing bad-request", Response.status(Response.Status.BAD_REQUEST).build());
    }

    @GET
    @Path("/forbidden")
    @Produces(MediaType.APPLICATION_JSON)
    public Response forbidden() {
        throw new ForbiddenException("Testing forbidden", Response.status(Response.Status.FORBIDDEN).build());
    }

    @GET
    @Path("/not-acceptable")
    @Produces(MediaType.APPLICATION_JSON)
    public Response notAcceptable() {
        throw new NotAcceptableException("Testing not-acceptable", Response.status(Response.Status.NOT_ACCEPTABLE).build());
    }

    @GET
    @Path("/not-allowed")
    @Produces(MediaType.APPLICATION_JSON)
    public Response notAllowed(){
        throw new NotAllowedException("Testing not-allowed", Response.status(Response.Status.METHOD_NOT_ALLOWED).build());
    }

    @GET
    @Path("/not-authorized")
    @Produces(MediaType.APPLICATION_JSON)
    public Response notAuthorized(){
        throw new NotAuthorizedException("Testing not-authorized", Response.status(Response.Status.UNAUTHORIZED).build());
    }

    @GET
    @Path("/not-found")
    @Produces(MediaType.APPLICATION_JSON)
    public Response notFound() {
        throw new NotFoundException("Testing not-found", Response.status(Response.Status.NOT_FOUND).build());
    }

    @GET
    @Path("/not-supported")
    @Produces(MediaType.APPLICATION_JSON)
    public Response notSupported() {
        throw new NotSupportedException("Testing not-supported", Response.status(Response.Status.UNSUPPORTED_MEDIA_TYPE).build());
    }

    @GET
    @Path("/bad-request-without-response")
    @Produces(MediaType.APPLICATION_JSON)
    public Response badRequestWithoutResponse() {
        throw new BadRequestException();
    }

    @GET
    @Path("/forbidden-without-response")
    @Produces(MediaType.APPLICATION_JSON)
    public Response forbiddenWithoutResponse() {
        throw new ForbiddenException();
    }

    @GET
    @Path("/not-acceptable-without-response")
    @Produces(MediaType.APPLICATION_JSON)
    public Response notAcceptableWithoutResponse() {
        throw new NotAcceptableException();
    }

    @GET
    @Path("/not-found-without-response")
    @Produces(MediaType.APPLICATION_JSON)
    public Response notFoundWithoutResponse() {
        throw new NotFoundException();
    }

    @GET
    @Path("/not-supported-without-response")
    @Produces(MediaType.APPLICATION_JSON)
    public Response notSupportedWithoutResponse() {
        throw new NotSupportedException();
    }
}
