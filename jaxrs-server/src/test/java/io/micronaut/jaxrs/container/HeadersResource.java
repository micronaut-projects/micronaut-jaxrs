package io.micronaut.jaxrs.container;

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
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.RuntimeDelegate;

@Path("/headers")
public class HeadersResource {

    @POST
    @Path("/content-type")
    @Produces("text/plain")
    public Response contentType(@HeaderParam("Content-Type") MediaType mediaType) {
        return Response.ok()
            .type(MediaType.valueOf("text/plain"))
            .entity(mediaType.toString())
            .type(mediaType)
            .build();
    }

    @GET
    @Path("/cookie")
    @Produces("text/plain")
    public Response cookie(@CookieParam("foo") Cookie cookie) {
        return Response.ok()
            .entity(cookie.getValue())
            .cookie(new NewCookie.Builder(cookie).build())
            .build();
    }

    @GET
    @Path("/etag")
    @Produces("text/plain")
    public Response etag(@HeaderParam(HttpHeaders.ETAG) EntityTag entityTag) {
        return Response.ok()
            .entity(entityTag.getValue())
            .tag(entityTag)
            .build();
    }

    @GET
    @Path("/link")
    @Produces("text/plain")
    public Response link(@HeaderParam(HttpHeaders.LINK) Link link) {
        return Response.ok()
            .entity(link.toString())
            .links(Link.fromUri("/blah").rel("friend").build())
            .build();
    }

    @GET
    @Path("/cache-control")
    @Produces("text/plain")
    public Response cacheControl(@HeaderParam(HttpHeaders.CACHE_CONTROL) CacheControl cacheControl) {
        return Response.ok()
            .entity(RuntimeDelegate.getInstance().createHeaderDelegate(CacheControl.class).toString(cacheControl))
            .cacheControl(cacheControl)
            .build();
    }

    @GET
    @Produces("text/plain")
    public Response headers(HttpHeaders httpHeaders) {
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
