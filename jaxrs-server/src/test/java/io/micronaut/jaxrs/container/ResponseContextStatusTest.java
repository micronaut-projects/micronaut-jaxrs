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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The status of a response a response filter reads and sets: a custom code and a reason phrase.
 */
@MicronautTest
@Property(name = "spec.name", value = "ResponseContextStatusTest")
class ResponseContextStatusTest {

    @Inject
    @Client("/api/response-context-status")
    HttpClient client;

    @Test
    void aFilterReadsACustomStatusCode() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().exchange(HttpRequest.GET("/custom"), String.class));
        assertEquals(499, e.getResponse().code());
        assertEquals("499", e.getResponse().getHeaders().get("X-Status"));
    }

    @Test
    void theStatusInfoAFilterSetsKeepsItsReasonPhrase() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/special"), String.class);
        assertEquals(200, response.code());
        assertEquals("Special", response.getHeaders().get("X-Reason"));
    }

    @Requires(property = "spec.name", value = "ResponseContextStatusTest")
    @Provider
    public static class StatusFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            if (!requestContext.getUriInfo().getPath().contains("response-context-status")) {
                return;
            }
            responseContext.getHeaders().putSingle("X-Status", String.valueOf(responseContext.getStatus()));
            if (requestContext.getUriInfo().getPath().endsWith("/special")) {
                responseContext.setStatusInfo(new Response.StatusType() {
                    @Override
                    public int getStatusCode() {
                        return 200;
                    }

                    @Override
                    public Response.Status.Family getFamily() {
                        return Response.Status.Family.SUCCESSFUL;
                    }

                    @Override
                    public String getReasonPhrase() {
                        return "Special";
                    }
                });
                responseContext.getHeaders().putSingle("X-Reason", responseContext.getStatusInfo().getReasonPhrase());
            }
        }
    }

    @Requires(property = "spec.name", value = "ResponseContextStatusTest")
    @Path("/response-context-status")
    @Produces(MediaType.TEXT_PLAIN)
    public static class StatusResource {
        @GET
        @Path("/custom")
        public Response custom() {
            return Response.status(499).entity("custom").build();
        }

        @GET
        @Path("/special")
        public String special() {
            return "special";
        }
    }
}
