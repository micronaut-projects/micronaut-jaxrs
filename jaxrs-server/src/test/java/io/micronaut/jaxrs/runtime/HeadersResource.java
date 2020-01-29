package io.micronaut.jaxrs.runtime;

import javax.ws.rs.*;
import javax.ws.rs.core.*;

@Path("/headers")
public class HeadersResource {

    @POST
    @Path("/content-type")
    @Produces("text/plain")
    Response contentType(@HeaderParam("Content-Type") MediaType mediaType) {
        return Response.ok()
                .type(MediaType.valueOf("text/plain"))
                .entity(mediaType.toString())
                .type(mediaType)
                .build();
    }

    @GET
    @Path("/cookie")
    @Produces("text/plain")
    Response cookie(@CookieParam("foo") Cookie cookie) {
        return Response.ok()
                .entity(cookie.getValue())
                .cookie(new NewCookie(cookie))
                .build();
    }

    @GET
    @Path("/etag")
    @Produces("text/plain")
    Response etag(@HeaderParam(HttpHeaders.ETAG) EntityTag entityTag) {
        return Response.ok()
                .entity(entityTag.getValue())
                .tag(entityTag)
                .build();
    }

    @GET
    @Path("/link")
    @Produces("text/plain")
    Response link(@HeaderParam(HttpHeaders.LINK) Link link) {
        return Response.ok()
                .entity(link.toString())
                .links(Link.fromUri("/blah").rel("friend").build())
                .build();
    }

    @GET
    @Path("/cache-control")
    @Produces("text/plain")
    Response cacheControl(@HeaderParam(HttpHeaders.CACHE_CONTROL) CacheControl cacheControl) {
        return Response.ok()
                .entity(cacheControl.toString())
                .cacheControl(cacheControl)
                .build();
    }

    @GET
    @Produces("text/plain")
    Response headers(HttpHeaders httpHeaders) {
        final Response.ResponseBuilder ok = Response.ok();
        ok.entity("echo");
        httpHeaders.getRequestHeaders().forEach((s, strings) -> {
            for (String string : strings) {
                ok.header(s, string);
            }
        });
        return ok.build();
    }
}
