package io.micronaut.jaxrs.container;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.client.annotation.Client;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

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
    HttpResponse<Notification> badRequest();

    @GET
    @Path("/forbidden")
    HttpResponse<Notification> forbidden();

    @GET
    @Path("/not-acceptable")
    HttpResponse<Notification> notAcceptable();

    @GET
    @Path("/not-allowed")
    HttpResponse<Notification> notAllowed();

    @GET
    @Path("/not-authorized")
    HttpResponse<Notification> notAuthorized();

    @GET
    @Path("/not-found")
    HttpResponse<Notification> notFound();

    @GET
    @Path("/not-supported")
    HttpResponse<Notification> notSupported();

    @GET
    @Path("/bad-request-without-response")
    HttpResponse<Notification> badRequestWithoutResponse();

    @GET
    @Path("/forbidden-without-response")
    HttpResponse<Notification> forbiddenWithoutResponse();

    @GET
    @Path("/not-acceptable-without-response")
    HttpResponse<Notification> notAcceptableWithoutResponse();

    @GET
    @Path("/not-allowed-without-response")
    HttpResponse<Notification> notAllowedWithoutResponse();

    @GET
    @Path("/not-authorized-without-response")
    HttpResponse<Notification> notAuthorizedWithoutResponse();

    @GET
    @Path("/not-found-without-response")
    HttpResponse<Notification> notFoundWithoutResponse();

    @GET
    @Path("/not-supported-without-response")
    HttpResponse<Notification> notSupportedWithoutResponse();
}
