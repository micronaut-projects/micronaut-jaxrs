package io.micronaut.jaxrs.container;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.annotation.Client;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;

@Client("/api/headers")
public interface HeaderClient {

    @POST
    @Path("/content-type")
    @Consumes("text/plain")
    String contentType(@HeaderParam("Content-Type") MediaType mediaType);

    @GET
    @Path("/cookie")
    @Consumes("text/plain")
    String cookie(@CookieParam("foo") Cookie cookie);

    @GET
    @Path("/etag")
    @Consumes("text/plain")
    String etag(@HeaderParam(HttpHeaders.ETAG) EntityTag entityTag);

    @GET
    @Path("/link")
    @Consumes("text/plain")
    HttpResponse<String> link(@HeaderParam(HttpHeaders.LINK) Link link);

    @GET
    @Path("/cache-control")
    @Produces("text/plain")
    @Consumes("text/plain")
    HttpResponse<String> cacheControl(@HeaderParam(HttpHeaders.CACHE_CONTROL) CacheControl cacheControl);
}
