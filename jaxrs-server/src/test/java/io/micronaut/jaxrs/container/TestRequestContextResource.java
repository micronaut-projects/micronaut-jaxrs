package io.micronaut.jaxrs.container;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Variant;
import jakarta.ws.rs.ext.Provider;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@Path("/request-context")
final class TestRequestContextResource {

    static final String REROUTE_HEADER = "X-JaxRs-Reroute";
    static final String METHOD_OVERRIDE_HEADER = "X-JaxRs-Method";

    @GET
    @Path("/method")
    @Produces(MediaType.TEXT_PLAIN)
    String method(@Context Request request) {
        return request.getMethod();
    }

    @GET
    @Path("/variant-null")
    @Produces(MediaType.TEXT_PLAIN)
    String variantNull(@Context Request request) {
        try {
            request.selectVariant(null);
            return "failed";
        } catch (IllegalArgumentException e) {
            return "ok";
        }
    }

    @GET
    @Path("/variant")
    Response variant(@Context Request request) {
        List<Variant> variants = Variant.encodings("CP1250", "UTF-8")
            .languages(Locale.ENGLISH)
            .mediaTypes(MediaType.APPLICATION_JSON_TYPE)
            .add()
            .build();
        Variant selected = request.selectVariant(variants);
        return selected == null ? Response.notAcceptable(variants).build() : Response.ok("ok").build();
    }

    @GET
    @Path("/preconditions/entity-tag")
    Response entityTag(@Context Request request) {
        return response(request.evaluatePreconditions(EntityTag.valueOf("\"AAA\"")) == null);
    }

    @GET
    @Path("/preconditions/entity-tag-null")
    Response entityTagNull(@Context Request request) {
        try {
            request.evaluatePreconditions((EntityTag) null);
            return Response.status(Response.Status.NOT_ACCEPTABLE).build();
        } catch (IllegalArgumentException e) {
            return Response.ok().build();
        }
    }

    @GET
    @Path("/preconditions/date-old")
    Response dateOld(@Context Request request) {
        return response(request.evaluatePreconditions(year1900()) == null);
    }

    @GET
    @Path("/preconditions/date-now")
    Response dateNow(@Context Request request) {
        return response(request.evaluatePreconditions(new Date()) == null);
    }

    @GET
    @Path("/reroute/source")
    @Produces(MediaType.TEXT_PLAIN)
    String rerouteSource() {
        return "source";
    }

    @GET
    @Path("/reroute/target")
    @Produces(MediaType.TEXT_PLAIN)
    String rerouteTarget() {
        return "target";
    }

    @GET
    @Path("/method-switch")
    @Produces(MediaType.TEXT_PLAIN)
    String methodSwitchGet() {
        return "GET";
    }

    @OPTIONS
    @Path("/method-switch")
    @Produces(MediaType.TEXT_PLAIN)
    String methodSwitchOptions() {
        return "OPTIONS";
    }

    private static Response response(boolean ok) {
        return Response.status(ok ? Response.Status.OK : Response.Status.PRECONDITION_FAILED).build();
    }

    private static Date year1900() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.YEAR, 1900);
        return calendar.getTime();
    }
}

@Provider
@PreMatching
final class TestRequestContextRerouteFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (requestContext.getHeaderString(TestRequestContextResource.REROUTE_HEADER) != null) {
            requestContext.setRequestUri(
                requestContext.getUriInfo()
                    .getBaseUriBuilder()
                    .path("request-context/reroute/target")
                    .build()
            );
        }
        String method = requestContext.getHeaderString(TestRequestContextResource.METHOD_OVERRIDE_HEADER);
        if (method != null) {
            requestContext.setMethod(method);
        }
    }
}
