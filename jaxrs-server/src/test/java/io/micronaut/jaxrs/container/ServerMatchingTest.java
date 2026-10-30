package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.core.Variant;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The matching of requests to resource methods, the values injected into them and the content
 * negotiation of the server (JAX-RS 3.7, 3.8).
 */
@MicronautTest
@Property(name = "spec.name", value = "ServerMatchingTest")
class ServerMatchingTest {

    @Inject
    @Client("/api")
    HttpClient client;

    private String get(String path) {
        return client.toBlocking().retrieve(HttpRequest.GET(path), String.class);
    }

    @Test
    void defaultTemplateRegexIsReluctant() {
        // JAX-RS 3.7.3: ([^/]+?)
        assertEquals("a|b-c", get("/split/a-b-c"));
    }

    @Test
    void explicitHeadMethodIsSelectedBeforeGet() {
        HttpResponse<?> response = client.toBlocking().exchange(HttpRequest.HEAD("/head"));
        assertEquals("head", response.getHeaders().get("Chosen"));
    }

    @Test
    void wildcardProducesTakesTheTypeOfTheResponse() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/wild/text").accept("*/*"), String.class);
        assertEquals(200, response.getStatus().getCode());
        assertTrue(response.getHeaders().get("Content-Type").startsWith("text/plain"), response.getHeaders().get("Content-Type"));
    }

    @Test
    void wildcardNegotiationFallsBackToOctetStream() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/wild/any").accept("*/*"), String.class);
        assertEquals(200, response.getStatus().getCode());
        assertEquals("application/octet-stream", response.getHeaders().get("Content-Type"));
    }

    @Test
    void weightsWithWhitespaceSelectAVariant() {
        assertEquals("en", client.toBlocking().retrieve(HttpRequest.GET("/variants/language").header("Accept-Language", "en; q=1"), String.class));
        assertEquals("gzip", client.toBlocking().retrieve(HttpRequest.GET("/variants/encoding").header("Accept-Encoding", "gzip; q=1"), String.class));
    }

    @Test
    void zeroQualityAfterAQuotedCommaRejectsTheVariant() {
        assertEquals("null", client.toBlocking().retrieve(HttpRequest.GET("/variants/profile")
            .header("Accept", "text/plain;profile=\"a,b\";q=0"), String.class));
    }

    @Test
    void moreSpecificVariantIsSelectedAtAnEqualScore() {
        assertEquals("text/plain", client.toBlocking().retrieve(HttpRequest.GET("/variants/specific")
            .header("Accept", "text/plain"), String.class));
    }

    @Test
    void varyOfTheResponseIsMergedWithTheNegotiatedFields() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/variants/vary")
            .header("Accept-Language", "fr"), String.class);
        Set<String> vary = response.getHeaders().getAll("Vary").stream()
            .flatMap(value -> Arrays.stream(value.split(",")))
            .map(String::trim)
            .collect(Collectors.toSet());
        assertTrue(vary.containsAll(Set.of("Origin", "Accept", "Accept-Language")), vary.toString());
    }

    @Test
    void preconditionBuilderHasTheVaryOfTheSelectedVariant() {
        assertEquals("Accept-Language", client.toBlocking().retrieve(HttpRequest.GET("/variants/precondition")
            .header("Accept-Language", "en").header("If-None-Match", "\"e\""), String.class));
    }

    @Test
    void pathParametersOfEachMatchingStageAreKept() {
        assertEquals(Set.of("one", "two"), new HashSet<>(Arrays.asList(get("/dups/one/sub/two").split(","))));
    }

    @Test
    void rootResourceFieldsTakeTheValuesOfTheRootPath() {
        assertEquals("parent:C", get("/scope/parent;m=R/child;m=C"));
    }

    @Test
    void encodedMatrixParameterOfALocatorHasADecodedName() {
        assertEquals("x%20y", get("/enc/sub;a%20b=x%20y/leaf"));
    }

    @Test
    void encodedConstructorKeepsTheParametersEncoded() {
        assertEquals("a%2Fb", get("/ctor?q=a%2Fb"));
    }

    @Test
    void cookieCollectionElementsHaveTheNameOfTheCookie() {
        assertEquals("id=one", client.toBlocking().retrieve(HttpRequest.GET("/cookies").header("Cookie", "id=one"), String.class));
    }

    @Test
    void lessSpecificTemplateServesAnAcceptedType() {
        assertEquals("template:fixed", client.toBlocking().retrieve(HttpRequest.GET("/neg/fixed").accept("application/xml"), String.class));
        assertEquals("fixed", client.toBlocking().retrieve(HttpRequest.GET("/neg/fixed").accept("text/plain"), String.class));
    }

    @Test
    void rootPathSegmentFieldHasTheSegmentOfTheRootPath() {
        assertEquals("parent:P", get("/psscope/parent;m=P/child;m=C"));
    }

    @Test
    void emptyPathSegmentIsASegment() {
        assertEquals("catch||b", get("/catch/;/b"));
    }

    @Test
    void encodedUnreservedLiteralIsMatched() {
        assertEquals("tilde", get("/lit/encoded/%7e"));
    }

    @Test
    void trailingEmptyRegexCaptureMatches() {
        assertEquals("x=", get("/semi/"));
    }

    @Test
    void emptyRegexCaptureOfALocatorMatches() {
        assertEquals("located", get("/semiloc/loc/"));
    }

    @Test
    void repeatedLocatorValuesAreKept() {
        assertEquals("same,same", get("/dups/same/sub/same"));
    }

    @Test
    void matchedUrisOfALocatorEndWithItsPrefix() {
        assertEquals("[mu/one/sub, mu/one]", get("/mu/one/sub/two"));
    }

    @Test
    void injectedSetsAreReadOnly() {
        assertEquals("read-only read-only", get("/sets?s=a&t=b"));
    }

    @Requires(property = "spec.name", value = "ServerMatchingTest")
    @Path("/split")
    public static class SplitResource {
        @GET
        @Path("{a}-{b}")
        @Produces("text/plain")
        public String split(@PathParam("a") String a, @PathParam("b") String b) {
            return a + "|" + b;
        }
    }

    @Requires(property = "spec.name", value = "ServerMatchingTest")
    @Path("/head")
    public static class HeadResource {
        @GET
        @Produces("text/plain;qs=1")
        public String get() {
            return "get";
        }

        @HEAD
        @Produces("text/plain;qs=0.1")
        public Response head() {
            return Response.ok().header("Chosen", "head").build();
        }
    }

    @Requires(property = "spec.name", value = "ServerMatchingTest")
    @Path("/wild")
    public static class WildcardResource {
        @GET
        @Path("text")
        @Produces("text/*")
        public Response text() {
            return Response.ok("x", "text/plain").build();
        }

        @GET
        @Path("any")
        @Produces({"text/*", "*/*"})
        public String any() {
            return "x";
        }
    }

    @Requires(property = "spec.name", value = "ServerMatchingTest")
    @Path("/variants")
    public static class VariantResource {
        @GET
        @Path("language")
        @Produces("text/plain")
        public String language(@Context Request request) {
            Variant variant = request.selectVariant(Variant.languages(Locale.ENGLISH).build());
            return variant == null ? "null" : variant.getLanguageString();
        }

        @GET
        @Path("encoding")
        @Produces("text/plain")
        public String encoding(@Context Request request) {
            Variant variant = request.selectVariant(Variant.encodings("gzip").build());
            return variant == null ? "null" : variant.getEncoding();
        }

        @GET
        @Path("profile")
        @Produces("text/plain")
        public String profile(@Context Request request) {
            Variant variant = request.selectVariant(Variant.mediaTypes(MediaType.valueOf("text/plain;profile=\"a,b\"")).build());
            return variant == null ? "null" : variant.getMediaType().toString();
        }

        @GET
        @Path("specific")
        @Produces("text/plain")
        public String specific(@Context Request request) {
            Variant variant = request.selectVariant(Variant.mediaTypes(MediaType.WILDCARD_TYPE, MediaType.TEXT_PLAIN_TYPE).build());
            return variant == null ? "null" : variant.getMediaType().toString();
        }

        @GET
        @Path("vary")
        public Response vary(@Context Request request) {
            List<Variant> variants = Variant.mediaTypes(MediaType.TEXT_PLAIN_TYPE).languages(Locale.ENGLISH, Locale.FRENCH).build();
            Variant variant = request.selectVariant(variants);
            return Response.ok(variant.getLanguageString(), variant).header("Vary", "Origin").build();
        }

        @GET
        @Path("precondition")
        @Produces("text/plain")
        public String precondition(@Context Request request) {
            request.selectVariant(Variant.languages(Locale.ENGLISH, Locale.FRENCH).build());
            Response.ResponseBuilder builder = request.evaluatePreconditions(new EntityTag("e"));
            return builder == null ? "no builder" : String.valueOf(builder.build().getHeaderString("Vary"));
        }
    }

    @Requires(property = "spec.name", value = "ServerMatchingTest")
    @Path("/dups/{id}")
    public static class DuplicateResource {
        @Path("sub")
        public DuplicateChild sub() {
            return new DuplicateChild();
        }
    }

    public static class DuplicateChild {
        @GET
        @Path("{id}")
        @Produces("text/plain")
        public String get(@Context UriInfo uriInfo) {
            return String.join(",", uriInfo.getPathParameters().get("id"));
        }
    }

    @Requires(property = "spec.name", value = "ServerMatchingTest")
    @Path("/scope/{id}")
    public static class ScopeResource {
        @PathParam("id")
        String id;

        @MatrixParam("m")
        String m;

        @GET
        @Path("{id}")
        @Produces("text/plain")
        public String get() {
            return id + ":" + m;
        }
    }

    @Requires(property = "spec.name", value = "ServerMatchingTest")
    @Path("/enc")
    public static class EncodedLocatorResource {
        @Path("sub")
        public EncodedLeaf sub(@Encoded @MatrixParam("a b") String value) {
            return new EncodedLeaf(value);
        }
    }

    public static class EncodedLeaf {
        private final String value;

        EncodedLeaf(String value) {
            this.value = value;
        }

        @GET
        @Path("leaf")
        @Produces("text/plain")
        public String leaf() {
            return value;
        }
    }

    @Requires(property = "spec.name", value = "ServerMatchingTest")
    @Path("/ctor")
    public static class EncodedConstructorResource {
        private final String q;

        @Encoded
        public EncodedConstructorResource(@QueryParam("q") String q) {
            this.q = q;
        }

        @GET
        @Produces("text/plain")
        public String get() {
            return q;
        }
    }

    @Requires(property = "spec.name", value = "ServerMatchingTest")
    @Path("/neg")
    public static class NegotiationResource {
        @GET
        @Path("fixed")
        @Produces("text/plain")
        public String fixed() {
            return "fixed";
        }

        @GET
        @Path("{x}")
        @Produces("application/xml")
        public String template(@PathParam("x") String x) {
            return "template:" + x;
        }
    }

    @Requires(property = "spec.name", value = "ServerMatchingTest")
    @Path("/psscope/{id}")
    public static class PathSegmentScopeResource {
        @PathParam("id")
        PathSegment id;

        @GET
        @Path("{id}")
        @Produces("text/plain")
        public String get() {
            return id.getPath() + ":" + id.getMatrixParameters().getFirst("m");
        }
    }

    @Requires(property = "spec.name", value = "ServerMatchingTest")
    @Path("/catch")
    public static class CatchAllResource {
        @GET
        @Path("{p:.*}")
        @Produces("text/plain")
        public String segments(@Context UriInfo uriInfo) {
            return uriInfo.getPathSegments().stream().map(PathSegment::getPath).collect(Collectors.joining("|"));
        }
    }

    @Requires(property = "spec.name", value = "ServerMatchingTest")
    @Path("/lit")
    public static class EncodedLiteralResource {
        @GET
        @Path("encoded/%7e")
        @Produces("text/plain")
        public String tilde() {
            return "tilde";
        }
    }

    @Requires(property = "spec.name", value = "ServerMatchingTest")
    @Path("/semiloc")
    public static class EmptyLocatorCaptureResource {
        @Path("loc/{x:.*}")
        public EmptyLocatorCaptureChild locate() {
            return new EmptyLocatorCaptureChild();
        }
    }

    public static class EmptyLocatorCaptureChild {
        @GET
        @Produces("text/plain")
        public String get() {
            return "located";
        }
    }

    @Requires(property = "spec.name", value = "ServerMatchingTest")
    @Path("/semi")
    public static class EmptyCaptureResource {
        @GET
        @Path("{x:.*}")
        @Produces("text/plain")
        public String get(@PathParam("x") String x) {
            return "x=" + x;
        }
    }

    @Requires(property = "spec.name", value = "ServerMatchingTest")
    @Path("/mu/{id}")
    public static class MatchedUrisLocatorResource {
        @Path("sub")
        public MatchedUrisChild sub(@Context UriInfo uriInfo) {
            return new MatchedUrisChild(uriInfo.getMatchedURIs().toString());
        }
    }

    public static class MatchedUrisChild {
        private final String uris;

        MatchedUrisChild(String uris) {
            this.uris = uris;
        }

        @GET
        @Path("{x}")
        @Produces("text/plain")
        public String get() {
            return uris;
        }
    }

    @Requires(property = "spec.name", value = "ServerMatchingTest")
    @Path("/cookies")
    public static class CookieListResource {
        @GET
        @Produces("text/plain")
        public String cookies(@CookieParam("id") List<Cookie> cookies) {
            return cookies.stream().map(c -> c.getName() + "=" + c.getValue()).collect(Collectors.joining(","));
        }
    }

    @Requires(property = "spec.name", value = "ServerMatchingTest")
    @Path("/sets")
    public static class SetResource {
        @GET
        @Produces("text/plain")
        public String sets(@QueryParam("s") Set<String> s, @QueryParam("t") SortedSet<String> t) {
            return readOnly(s) + " " + readOnly(t);
        }

        private static String readOnly(Set<String> set) {
            try {
                set.add("changed");
                return "writable";
            } catch (UnsupportedOperationException e) {
                return "read-only";
            }
        }
    }
}
