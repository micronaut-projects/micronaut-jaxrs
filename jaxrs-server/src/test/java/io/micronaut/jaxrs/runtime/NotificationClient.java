package io.micronaut.jaxrs.runtime;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.client.annotation.Client;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

@Client("/api/notifications")
public interface NotificationClient {
    @GET
    @Path("/ping")
    HttpResponse<String> ping();

    @GET
    @Path("/get/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    Notification getNotification(@PathParam("id") int id);

    /**
     * PathParam annotation has value `newId` equal to path variable `newId`,
     * but variable `int id` is not equal to path variable `newId`
     */
    @GET
    @Path("/get/v2/{newId}")
    @Consumes(MediaType.APPLICATION_JSON)
    Notification getNotificationV2(@PathParam("newId") int id);

    @GET
    @Path("/query")
    @Consumes(MediaType.APPLICATION_JSON)
    Notification getDefault();

    @GET
    @Path("/query")
    @Consumes(MediaType.APPLICATION_JSON)
    Notification getDefault(@QueryValue("id") int id);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    HttpResponse<Notification> postNotification(Notification notification);

    @GET
    @Path("/bad-request")
    @Produces(MediaType.APPLICATION_JSON)
    HttpResponse<Notification> badRequest();

    @GET
    @Path("/forbidden")
    @Produces(MediaType.APPLICATION_JSON)
    HttpResponse<Notification> forbidden();

    @GET
    @Path("/not-acceptable")
    @Produces(MediaType.APPLICATION_JSON)
    HttpResponse<Notification> notAcceptable();

    @GET
    @Path("/not-allowed")
    @Produces(MediaType.APPLICATION_JSON)
    HttpResponse<Notification> notAllowed();

    @GET
    @Path("/not-authorized")
    @Produces(MediaType.APPLICATION_JSON)
    HttpResponse<Notification> notAuthorized();

    @GET
    @Path("/not-found")
    @Produces(MediaType.APPLICATION_JSON)
    HttpResponse<Notification> notFound();

    @GET
    @Path("/not-supported")
    @Produces(MediaType.APPLICATION_JSON)
    HttpResponse<Notification> notSupported();
}
