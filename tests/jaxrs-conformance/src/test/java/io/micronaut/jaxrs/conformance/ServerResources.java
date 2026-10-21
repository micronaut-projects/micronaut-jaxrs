package io.micronaut.jaxrs.conformance;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.core.Variant;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The resources deployed both on Micronaut and on Jersey. Every method answers a deterministic text
 * describing what it received, so the bodies can be compared.
 */
public final class ServerResources {
    /** Last-Modified of the conditional resources: Sun, 01 Jan 2023 00:00:00 GMT. */
    public static final Date LAST_MODIFIED = new Date(1672531200000L);
    public static final EntityTag STRONG = new EntityTag("v1");
    public static final EntityTag WEAK = new EntityTag("v1", true);

    public static final List<Class<?>> CLASSES = List.of(Root.class, Deep.class, PTemplate.class, PLiteral.class);

    private ServerResources() {
    }

    static String sorted(MultivaluedMap<String, String> map) {
        return new TreeMap<>(map).toString();
    }

    static String segments(List<PathSegment> segments) {
        List<String> out = new ArrayList<>();
        for (PathSegment s : segments) {
            out.add(s.getPath() + sorted(s.getMatrixParameters()));
        }
        return out.toString();
    }

    @Path("/r")
    public static class Root {
        @GET
        @Path("t/{id: [0-9]+}")
        @Produces(MediaType.TEXT_PLAIN)
        public String num(@PathParam("id") String id) {
            return "num:" + id;
        }

        @GET
        @Path("t/{name}")
        @Produces(MediaType.TEXT_PLAIN)
        public String name(@PathParam("name") String name, @Encoded @PathParam("name") String raw) {
            return "name:" + name + "|" + raw;
        }

        @GET
        @Path("t/{a}-{b}")
        @Produces(MediaType.TEXT_PLAIN)
        public String dash(@PathParam("a") String a, @PathParam("b") String b) {
            return "dash:" + a + "|" + b;
        }

        @GET
        @Path("t/x{c: [a-z]*}y")
        @Produces(MediaType.TEXT_PLAIN)
        public String xy(@PathParam("c") String c) {
            return "xy:" + c;
        }

        @GET
        @Path("t/lit")
        @Produces(MediaType.TEXT_PLAIN)
        public String lit() {
            return "lit";
        }

        @GET
        @Path("rest/{p: .+}")
        @Produces(MediaType.TEXT_PLAIN)
        public String rest(@PathParam("p") String p, @Encoded @PathParam("p") String raw, @Context UriInfo ui) {
            return "rest:" + p + "|" + raw + "|" + ui.getPath() + "|" + ui.getPath(false) + "|" + segments(ui.getPathSegments())
                + "|" + ui.getMatchedURIs();
        }

        @GET
        @Path("m/{seg}")
        @Produces(MediaType.TEXT_PLAIN)
        public String matrix(@MatrixParam("a") String a, @MatrixParam("b") List<String> b, @Encoded @MatrixParam("a") String rawA,
                             @PathParam("seg") PathSegment seg, @PathParam("seg") String segString) {
            return "m:" + a + "|" + b + "|" + rawA + "|" + seg.getPath() + sorted(seg.getMatrixParameters()) + "|" + segString;
        }

        @GET
        @Path("q")
        @Produces(MediaType.TEXT_PLAIN)
        public String query(@QueryParam("x") List<String> x, @QueryParam("y") @DefaultValue("dflt") String y,
                            @Encoded @QueryParam("x") List<String> rawX, @Context UriInfo ui) {
            return "q:" + x + "|" + y + "|" + rawX + "|" + sorted(ui.getQueryParameters()) + "|" + sorted(ui.getQueryParameters(false));
        }

        @POST
        @Path("form")
        @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
        @Produces(MediaType.TEXT_PLAIN)
        public String form(@FormParam("x") List<String> x, @Encoded @FormParam("x") List<String> raw,
                           @FormParam("y") @DefaultValue("d") String y) {
            return "form:" + x + "|" + raw + "|" + y;
        }

        @POST
        @Path("formmap")
        @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
        @Produces(MediaType.TEXT_PLAIN)
        public String formMap(MultivaluedMap<String, String> form) {
            return "formmap:" + sorted(form);
        }

        @GET
        @Path("neg")
        @Produces({"text/plain;qs=0.5", "application/json", "application/xml;qs=0.8", "text/html"})
        public String neg() {
            return "neg";
        }

        @GET
        @Path("wild")
        @Produces("text/*")
        public String wild() {
            return "wild";
        }

        @GET
        @Path("none")
        public String none() {
            return "none";
        }

        @POST
        @Path("echo")
        @Consumes("text/plain")
        @Produces("text/plain")
        public String echoText(String body) {
            return "text:" + body.length() + ":" + (body.length() > 64 ? body.hashCode() : body);
        }

        @POST
        @Path("echo")
        @Consumes("application/*+json")
        @Produces("application/json")
        public String echoJson(String body) {
            return "json:" + body.length() + ":" + (body.length() > 64 ? body.hashCode() : body);
        }

        @POST
        @Path("echo")
        @Consumes("application/octet-stream")
        @Produces("application/octet-stream")
        public byte[] echoBytes(byte[] body) {
            return ("bytes:" + body.length).getBytes();
        }

        @PUT
        @Path("echo")
        @Consumes({"text/*", "application/json"})
        @Produces({"text/plain", "application/json"})
        public String put(String body, @HeaderParam("Content-Type") String ct) {
            return "put:" + body.length() + ":" + ct;
        }

        @GET
        @Path("cond")
        @Produces("text/plain")
        public Response cond(@Context Request request) {
            Response.ResponseBuilder b = request.evaluatePreconditions(LAST_MODIFIED, STRONG);
            if (b != null) {
                return b.build();
            }
            return Response.ok("fresh").tag(STRONG).lastModified(LAST_MODIFIED).build();
        }

        @PUT
        @Path("cond")
        @Consumes("*/*")
        @Produces("text/plain")
        public Response condPut(@Context Request request, String body) {
            Response.ResponseBuilder b = request.evaluatePreconditions(LAST_MODIFIED, STRONG);
            if (b != null) {
                return b.build();
            }
            return Response.ok("updated").tag(STRONG).build();
        }

        @GET
        @Path("cond/weak")
        @Produces("text/plain")
        public Response condWeak(@Context Request request) {
            Response.ResponseBuilder b = request.evaluatePreconditions(WEAK);
            if (b != null) {
                return b.build();
            }
            return Response.ok("fresh").tag(WEAK).build();
        }

        @GET
        @Path("cond/date")
        @Produces("text/plain")
        public Response condDate(@Context Request request) {
            Response.ResponseBuilder b = request.evaluatePreconditions(LAST_MODIFIED);
            if (b != null) {
                return b.build();
            }
            return Response.ok("fresh").lastModified(LAST_MODIFIED).build();
        }

        @DELETE
        @Path("cond/none")
        public Response condNone(@Context Request request) {
            Response.ResponseBuilder b = request.evaluatePreconditions();
            if (b != null) {
                return b.build();
            }
            return Response.noContent().build();
        }

        @GET
        @Path("variant")
        public Response variant(@Context Request request) {
            List<Variant> variants = Variant.mediaTypes(MediaType.TEXT_PLAIN_TYPE, MediaType.APPLICATION_JSON_TYPE)
                .languages(java.util.Locale.ENGLISH, java.util.Locale.GERMAN).encodings("gzip", "identity").add().build();
            Variant v = request.selectVariant(variants);
            if (v == null) {
                return Response.notAcceptable(variants).build();
            }
            return Response.ok("variant:" + v.getMediaType() + "|" + v.getLanguageString() + "|" + v.getEncoding()).build();
        }

        @GET
        @Path("headers")
        @Produces("text/plain")
        public String headers(@Context HttpHeaders headers, @Context UriInfo uriInfo) {
            List<String> types = new ArrayList<>();
            for (MediaType t : headers.getAcceptableMediaTypes()) {
                types.add("<" + t.getType() + ">/<" + t.getSubtype() + ">" + new TreeMap<>(t.getParameters()));
            }
            return "headers:" + types + "|" + headers.getAcceptableLanguages() + "|" + headers.getMediaType()
                + "|" + headers.getLanguage() + "|" + uriInfo.getBaseUri().getPath() + "|" + uriInfo.getPath() + "|" + uriInfo.getRequestUri().getRawPath();
        }

        @Path("loc/{x}")
        public Sub loc(@PathParam("x") String x, @MatrixParam("k") String k) {
            return new Sub(x + "/" + k);
        }
    }

    public static class Sub {
        private final String prefix;

        public Sub(String prefix) {
            this.prefix = prefix;
        }

        @GET
        @Produces("text/plain")
        public String get() {
            return "sub:" + prefix;
        }

        @GET
        @Path("{y}")
        @Produces("text/plain")
        public String child(@PathParam("y") String y, @PathParam("x") String x, @MatrixParam("k") String k, @Context UriInfo ui) {
            return "subchild:" + prefix + "|" + y + "|" + x + "|" + k + "|" + ui.getMatchedURIs() + "|" + ui.getPathParameters().keySet();
        }

        @POST
        @Consumes("text/plain")
        @Produces("text/plain")
        public String post(String body) {
            return "subpost:" + prefix + ":" + body.length();
        }

        @Path("deeper")
        public Sub deeper() {
            return new Sub(prefix + "+");
        }
    }

    @Path("/r/t/{any}/z")
    public static class Deep {
        @GET
        @Produces("text/plain")
        public String get(@PathParam("any") String any) {
            return "deep:" + any;
        }
    }

    @Path("/p/{a}")
    public static class PTemplate {
        @GET
        @Produces("text/plain")
        public String get(@PathParam("a") String a) {
            return "ptemplate:" + a;
        }

        @DELETE
        public void delete() {
        }
    }

    @Path("/p/lit")
    public static class PLiteral {
        @GET
        @Produces("text/plain")
        public String get() {
            return "pliteral";
        }

        @Path("{s}")
        @GET
        @Produces("text/plain")
        public Map<String, String> unwritable(@PathParam("s") String s) {
            return Map.of("s", s);
        }
    }
}
