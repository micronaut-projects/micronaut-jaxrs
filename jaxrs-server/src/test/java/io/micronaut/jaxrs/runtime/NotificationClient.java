package io.micronaut.jaxrs.runtime;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.client.annotation.Client;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Client("/api/notifications")
public interface NotificationClient {
    @GET
    @Path("/ping")
    HttpResponse<String> ping();

    @GET
    @Path("/get/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    Notification getNotification(@PathParam("id") int id);

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
}
