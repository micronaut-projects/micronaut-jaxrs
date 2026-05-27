package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.jaxrs.common.JaxRsApplicationResources;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Application;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MicronautTest
@Property(name = "spec.name", value = "ApplicationResourceSelectionTest")
class ApplicationResourceSelectionTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void applicationClassesRestrictRootResources() {
        HttpStatus status = status(HttpRequest.POST("/selected/super/post", "")
            .contentType(MediaType.TEXT_XML_TYPE)
            .accept(MediaType.TEXT_XML_TYPE));

        assertEquals(HttpStatus.NOT_FOUND, status);
    }

    @Test
    void subclassOverrideBindsMatrixParameter() {
        String response = client.toBlocking().retrieve(HttpRequest.PUT("/selected/resource/put;ijk=hello", "")
            .contentType(MediaType.TEXT_HTML_TYPE)
            .accept(MediaType.TEXT_HTML_TYPE), String.class);

        assertEquals("hello", response);
    }

    private HttpStatus status(HttpRequest<?> request) {
        try {
            return client.toBlocking().exchange(request, String.class).getStatus();
        } catch (HttpClientResponseException e) {
            return e.getStatus();
        }
    }

    @Requires(property = "spec.name", value = "ApplicationResourceSelectionTest")
    @Primary
    @Singleton
    @JaxRsApplicationResources("io.micronaut.jaxrs.container.ApplicationResourceSelectionTest$SelectedResource")
    static final class SelectedApplication extends Application {

        @Override
        public Set<Class<?>> getClasses() {
            throw new AssertionError("Generated application resource metadata should be used");
        }
    }

    @Requires(property = "spec.name", value = "ApplicationResourceSelectionTest")
    @Path("/selected/resource")
    static class SelectedResource extends SelectedSuper implements SelectedInterface {

        @PUT
        @Path("put")
        @Consumes(jakarta.ws.rs.core.MediaType.TEXT_HTML)
        @Produces(jakarta.ws.rs.core.MediaType.TEXT_HTML)
        public String get(@DefaultValue("subclass") @MatrixParam("ijk") String param) {
            return param;
        }
    }

    @Path("/selected/super")
    static class SelectedSuper {

        @POST
        @Path("post")
        @Consumes(jakarta.ws.rs.core.MediaType.TEXT_XML)
        @Produces(jakarta.ws.rs.core.MediaType.TEXT_XML)
        public String get(@DefaultValue("default") @QueryParam("pqr") String param) {
            return param;
        }
    }

    interface SelectedInterface {

        @GET
        @Path("get")
        @Consumes(jakarta.ws.rs.core.MediaType.TEXT_PLAIN)
        @Produces(jakarta.ws.rs.core.MediaType.TEXT_PLAIN)
        String get(@DefaultValue("interface") @FormParam("xyz") String param);
    }
}
