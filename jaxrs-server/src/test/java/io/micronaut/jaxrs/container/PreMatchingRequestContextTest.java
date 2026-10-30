package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A pre-matching filter changes the method and the URI the resource method is matched with, reads
 * and replaces the entity, and sees the request and its security context, and no matched URIs.
 */
@MicronautTest
@Property(name = "spec.name", value = "PreMatchingRequestContextTest")
class PreMatchingRequestContextTest {

    @Inject
    @Client("/api/pre-matching")
    HttpClient client;

    private String retrieve(MutableHttpRequest<?> request, String operation) {
        return client.toBlocking().retrieve(request.header("X-Operation", operation));
    }

    @Test
    void setMethod() {
        assertEquals("put", retrieve(HttpRequest.GET("/resource"), "method"));
    }

    @Test
    void setRequestUri() {
        assertEquals("target", retrieve(HttpRequest.GET("/resource"), "uri"));
    }

    @Test
    void setRequestUriWithBase() {
        assertEquals("target", retrieve(HttpRequest.GET("/resource"), "uri2"));
    }

    @Test
    void setRequestUriResolvesARelativeUriAgainstTheBaseUri() {
        assertEquals("target", retrieve(HttpRequest.GET("/resource"), "relative"));
    }

    @Test
    void aPropertySetToNullIsRemoved() {
        assertEquals("false []", retrieve(HttpRequest.GET("/resource"), "null-property"));
    }

    @Test
    void thePropertyNamesAreImmutable() {
        assertEquals("immutable [x]", retrieve(HttpRequest.GET("/resource"), "property-names"));
    }

    @Test
    void thePropertyNamesOfAnInterceptorAreImmutable() {
        assertEquals("immutable", client.toBlocking().exchange(HttpRequest.GET("/resource").header("X-Operation", "interceptor-names"), String.class)
            .getHeaders().get("X-Names"));
    }

    @Test
    void setRequestUriReplacesTheBaseUriOfUriInfo() {
        assertEquals("/api/pre-matching/|uri-info", retrieve(HttpRequest.GET("/resource"), "base"));
    }

    @Test
    void getEntityStreamLeavesTheEntity() {
        assertEquals("entity", retrieve(HttpRequest.POST("/resource", "entity").contentType(MediaType.TEXT_PLAIN), "entity"));
        assertEquals("echo:entity", retrieve(HttpRequest.POST("/resource", "entity").contentType(MediaType.TEXT_PLAIN), "none"));
    }

    @Test
    void setEntityStream() {
        assertEquals("echo:replaced", retrieve(HttpRequest.POST("/resource", "entity").contentType(MediaType.TEXT_PLAIN), "replace"));
    }

    @Test
    void hasEntity() {
        assertEquals("true", retrieve(HttpRequest.POST("/resource", "entity").contentType(MediaType.TEXT_PLAIN), "has"));
        assertEquals("false", retrieve(HttpRequest.GET("/resource"), "has"));
    }

    @Test
    void getRequest() {
        assertEquals("GET", retrieve(HttpRequest.GET("/resource"), "request"));
    }

    @Test
    void requestHasTheReplacedMethod() {
        assertEquals("PUT 412", retrieve(HttpRequest.GET("/resource").header("If-None-Match", "\"x\""), "precondition"));
    }

    @Test
    void noPathParametersBeforeTheMatching() {
        assertEquals("{}", retrieve(HttpRequest.GET("/resource"), "path-parameters"));
    }

    @Test
    void retainedUriInfoHasTheReplacedBaseUri() {
        assertEquals("/api/pre-matching/|uri-info", retrieve(HttpRequest.GET("/resource"), "retained"));
    }

    @Test
    void nothingIsMatchedBeforeTheMatching() {
        assertEquals("[]", retrieve(HttpRequest.GET("/resource"), "matched"));
    }

    @Test
    void securityContextWithoutAUser() {
        assertEquals("null", retrieve(HttpRequest.GET("/resource"), "security"));
    }

    @Requires(property = "spec.name", value = "PreMatchingRequestContextTest")
    @Provider
    @PreMatching
    public static class OperationFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext context) throws IOException {
            String operation = context.getHeaderString("X-Operation");
            if (operation == null) {
                return;
            }
            switch (operation) {
                case "method" -> context.setMethod("PUT");
                case "uri" -> context.setRequestUri(context.getUriInfo().getBaseUri().resolve("pre-matching/target"));
                case "relative" -> context.setRequestUri(java.net.URI.create("pre-matching/target"));
                case "null-property" -> {
                    context.setProperty("x", "value");
                    context.setProperty("x", null);
                    context.abortWith(Response.ok(context.hasProperty("x") + " " + context.getPropertyNames()).build());
                }
                case "property-names" -> {
                    context.setProperty("x", "value");
                    String result;
                    try {
                        context.getPropertyNames().clear();
                        result = "mutable";
                    } catch (UnsupportedOperationException e) {
                        result = "immutable";
                    }
                    context.abortWith(Response.ok(result + " " + context.getPropertyNames()).build());
                }
                case "interceptor-names" -> context.setProperty("interceptor-names", true);
                case "uri2" -> context.setRequestUri(context.getUriInfo().getBaseUri(), java.net.URI.create("pre-matching/target"));
                case "base" -> context.setRequestUri(context.getUriInfo().getBaseUri().resolve("pre-matching/"), java.net.URI.create("uri-info"));
                case "entity" -> context.abortWith(Response.ok(new String(context.getEntityStream().readAllBytes(), StandardCharsets.UTF_8)).build());
                case "replace" -> context.setEntityStream(new ByteArrayInputStream("replaced".getBytes(StandardCharsets.UTF_8)));
                case "has" -> context.abortWith(Response.ok(String.valueOf(context.hasEntity())).build());
                case "request" -> context.abortWith(Response.ok(context.getRequest().getMethod()).build());
                case "precondition" -> {
                    context.setMethod("PUT");
                    Response.ResponseBuilder builder = context.getRequest().evaluatePreconditions(new EntityTag("x"));
                    context.abortWith(Response.ok(context.getRequest().getMethod() + " " + (builder == null ? "none" : builder.build().getStatus())).build());
                }
                case "path-parameters" -> context.abortWith(Response.ok(context.getUriInfo().getPathParameters().toString()).build());
                case "retained" -> {
                    UriInfo uriInfo = context.getUriInfo();
                    context.setRequestUri(uriInfo.getBaseUri().resolve("pre-matching/"), java.net.URI.create("uri-info"));
                    context.abortWith(Response.ok(uriInfo.getBaseUri().getPath() + "|" + uriInfo.getPath()).build());
                }
                case "matched" -> context.abortWith(Response.ok(context.getUriInfo().getMatchedURIs().toString()).build());
                case "security" -> context.abortWith(Response.ok(String.valueOf(context.getSecurityContext().getUserPrincipal())).build());
                default -> {
                    // the resource method
                }
            }
        }
    }

    @Requires(property = "spec.name", value = "PreMatchingRequestContextTest")
    @Provider
    public static class PropertyNamesInterceptor implements jakarta.ws.rs.ext.WriterInterceptor {
        @Override
        public void aroundWriteTo(jakarta.ws.rs.ext.WriterInterceptorContext context) throws IOException {
            if (context.hasProperty("interceptor-names")) {
                String result;
                try {
                    context.getPropertyNames().clear();
                    result = context.hasProperty("interceptor-names") ? "cleared-view" : "mutable";
                } catch (UnsupportedOperationException e) {
                    result = "immutable";
                }
                context.getHeaders().putSingle("X-Names", result);
            }
            context.proceed();
        }
    }

    @Requires(property = "spec.name", value = "PreMatchingRequestContextTest")
    @Path("/pre-matching")
    public static class PreMatchingResource {

        @GET
        @Path("resource")
        public String get() {
            return "get";
        }

        @PUT
        @Path("resource")
        public String put() {
            return "put";
        }

        @POST
        @Path("resource")
        public String post(String entity) {
            return "echo:" + entity;
        }

        @GET
        @Path("uri-info")
        public String uriInfo(@jakarta.ws.rs.core.Context jakarta.ws.rs.core.UriInfo uriInfo) {
            return uriInfo.getBaseUri().getPath() + "|" + uriInfo.getPath();
        }

        @GET
        @Path("target")
        public String target() {
            return "target";
        }
    }
}
