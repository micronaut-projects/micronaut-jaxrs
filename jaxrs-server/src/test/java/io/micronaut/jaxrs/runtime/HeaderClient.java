package io.micronaut.jaxrs.runtime;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.annotation.Client;
import javax.ws.rs.*;
import javax.ws.rs.core.*;

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
