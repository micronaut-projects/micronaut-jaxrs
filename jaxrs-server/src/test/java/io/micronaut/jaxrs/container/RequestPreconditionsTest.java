package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Variant;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The preconditions and the variant selection of {@link Request}.
 */
@MicronautTest
@Property(name = "spec.name", value = "RequestPreconditionsTest")
class RequestPreconditionsTest {

    @Inject
    @Client("/api")
    HttpClient client;

    @Test
    void staleUnmodifiedSinceWithEntityTagFails() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().exchange(HttpRequest.PUT("/preconditions", "x")
                .header("If-Unmodified-Since", "Thu, 01 Jan 1970 00:00:01 GMT"), String.class));
        assertEquals(412, e.getStatus().getCode());
    }

    @Test
    void moreSpecificRejectedRangeIsNotSelected() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/preconditions/variant")
            .accept("text/plain;q=0, */*;q=1"), String.class);
        assertEquals("application/json", response.body());
    }

    @Test
    void variantRejectedWithParametersIsNotRevivedByTheBroaderRange() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/preconditions/flowed")
            .accept("text/plain;format=flowed;q=0, text/plain;q=1"), String.class);
        assertEquals("text/plain", response.body());
    }

    @Test
    void unmodifiedSinceIsIgnoredWhenIfMatchIsPresent() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.PUT("/preconditions", "x")
            .header("If-Match", "\"a\"")
            .header("If-Unmodified-Since", "Thu, 01 Jan 1970 00:00:01 GMT"), String.class);
        assertEquals("updated", response.body());
    }

    @Test
    void rejectedIdentityEncodingIsNotSelected() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/preconditions/encoding")
            .header("Accept-Encoding", "gzip, identity;q=0"), String.class);
        assertEquals("gzip", response.body());
    }

    @Test
    void unmodifiedSinceIsEvaluatedBeforeIfNoneMatch() {
        // RFC 9110, 13.2.2: step 2 before step 3
        assertEquals(412, status(HttpRequest.GET("/preconditions/cond")
            .header("If-None-Match", "\"v1\"")
            .header("If-Unmodified-Since", "Sat, 31 Dec 2022 23:59:59 GMT")));
    }

    @Test
    void emptyIfMatchFails() {
        // an empty list matches no entity tag
        assertEquals(412, status(HttpRequest.GET("/preconditions/cond")
            .header("If-Match", "")
            .header("If-Unmodified-Since", "Sat, 31 Dec 2022 23:59:59 GMT")));
    }

    @Test
    void obsoleteDateFormatsAreAccepted() {
        // RFC 9110, 5.6.7: RFC 850 and asctime too
        assertEquals(304, status(HttpRequest.GET("/preconditions/date").header("If-Modified-Since", "Sunday, 01-Jan-23 00:00:00 GMT")));
        assertEquals(304, status(HttpRequest.GET("/preconditions/date").header("If-Modified-Since", "Sun Jan  1 00:00:00 2023")));
        assertEquals(412, status(HttpRequest.GET("/preconditions/date").header("If-Unmodified-Since", "Sat Dec 31 00:00:00 2022")));
        assertEquals(200, status(HttpRequest.GET("/preconditions/date").header("If-Unmodified-Since", "Sun Jan  1 00:00:00 2023")));
    }

    @Test
    void identityEncodingIsAcceptableWhenNotExcluded() {
        // RFC 9110, 12.5.3
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/preconditions/encodings")
            .header("Accept-Encoding", "gzip;q=0"), String.class);
        assertEquals("identity", response.body());
    }

    @Test
    void emptyAcceptEncodingAcceptsTheIdentityCodingOnly() {
        // RFC 9110, 12.5.3: an empty field value, unlike an absent field
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/preconditions/gzip")
            .header("Accept-Encoding", ""), String.class);
        assertEquals("none", response.body());
    }

    private int status(HttpRequest<?> request) {
        try {
            return client.toBlocking().exchange(request, String.class).getStatus().getCode();
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    @Requires(property = "spec.name", value = "RequestPreconditionsTest")
    @Path("/preconditions")
    public static class PreconditionsResource {
        // Sun, 01 Jan 2023 00:00:00 GMT
        private static final Date LAST_MODIFIED = new Date(1672531200000L);

        @PUT
        public Response put(@Context Request request) {
            Response.ResponseBuilder builder = request.evaluatePreconditions(new Date(), new EntityTag("a"));
            return builder != null ? builder.build() : Response.ok("updated").build();
        }

        @GET
        @Path("cond")
        public Response cond(@Context Request request) {
            Response.ResponseBuilder builder = request.evaluatePreconditions(LAST_MODIFIED, new EntityTag("v1"));
            return builder != null ? builder.build() : Response.ok("ok").build();
        }

        @GET
        @Path("date")
        public Response date(@Context Request request) {
            Response.ResponseBuilder builder = request.evaluatePreconditions(LAST_MODIFIED);
            return builder != null ? builder.build() : Response.ok("ok").build();
        }

        @GET
        @Path("encodings")
        public String encodings(@Context Request request) {
            Variant variant = request.selectVariant(Variant.encodings("gzip", "identity").build());
            return variant == null ? "none" : String.valueOf(variant.getEncoding());
        }

        @GET
        @Path("gzip")
        public String gzip(@Context Request request) {
            Variant variant = request.selectVariant(Variant.encodings("gzip").build());
            return variant == null ? "none" : String.valueOf(variant.getEncoding());
        }

        @GET
        @Path("variant")
        public String variant(@Context Request request) {
            Variant variant = request.selectVariant(Variant.mediaTypes(MediaType.TEXT_PLAIN_TYPE, MediaType.APPLICATION_JSON_TYPE).build());
            return variant == null ? "none" : variant.getMediaType().toString();
        }

        @GET
        @Path("flowed")
        public String flowed(@Context Request request) {
            Variant variant = request.selectVariant(Variant.mediaTypes(
                MediaType.valueOf("text/plain;format=flowed"), MediaType.valueOf("text/plain")).build());
            return variant == null ? "none" : variant.getMediaType().toString();
        }

        @GET
        @Path("encoding")
        public String encoding(@Context Request request) {
            Variant variant = request.selectVariant(List.of(
                new Variant(MediaType.TEXT_PLAIN_TYPE, (String) null, null),
                new Variant(MediaType.TEXT_PLAIN_TYPE, (String) null, "gzip")));
            return variant == null ? "none" : String.valueOf(variant.getEncoding());
        }
    }
}
