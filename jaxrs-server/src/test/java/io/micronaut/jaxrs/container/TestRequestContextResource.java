package io.micronaut.jaxrs.container;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Variant;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@Path("/request-context")
final class TestRequestContextResource {

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

    private static Response response(boolean ok) {
        return Response.status(ok ? Response.Status.OK : Response.Status.PRECONDITION_FAILED).build();
    }

    private static Date year1900() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.YEAR, 1900);
        return calendar.getTime();
    }
}
