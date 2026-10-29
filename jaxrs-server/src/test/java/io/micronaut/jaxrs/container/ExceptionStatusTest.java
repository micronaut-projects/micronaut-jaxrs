package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The status of unmapped exceptions, and the responses of exception mappers and web application
 * exceptions that are null or of an application {@link Response} subclass.
 */
@MicronautTest
@Property(name = "spec.name", value = "ExceptionStatusTest")
class ExceptionStatusTest {

    @Inject
    @Client("/api/exception-status")
    HttpClient client;

    @Test
    void unmappedUncheckedExceptionIsServerError() {
        assertEquals(500, status("/unmapped"));
    }

    @Test
    void queryParamConversionFailureIsNotFound() {
        // JAX-RS 3.2: 404 for @QueryParam, @PathParam and @MatrixParam, 400 for @HeaderParam
        assertEquals(404, status("/query?n=abc"));
    }

    @Test
    void headerParamConversionFailureIsBadRequest() {
        assertEquals(400, status(HttpRequest.GET("/header").header("n", "abc")));
    }

    @Test
    void pathParamConversionFailureIsNotFound() {
        assertEquals(404, status("/path/abc"));
    }

    @Test
    void nullMapperResponseIsNoContent() {
        assertEquals(204, status("/null-mapped"));
    }

    @Test
    void mapperResponseSubclass() {
        assertEquals(409, status("/subclass-mapped"));
    }

    @Test
    void webApplicationExceptionResponseSubclass() {
        assertEquals(410, status("/subclass-wae"));
    }

    private int status(String uri) {
        return status(HttpRequest.GET(uri));
    }

    private int status(HttpRequest<?> request) {
        return TestResponses.status(client, request);
    }

    static class NullMappedException extends RuntimeException {
    }

    static class SubclassMappedException extends RuntimeException {
    }

    @Requires(property = "spec.name", value = "ExceptionStatusTest")
    @Provider
    public static class NullMapper implements ExceptionMapper<NullMappedException> {
        @Override
        public Response toResponse(NullMappedException exception) {
            return null;
        }
    }

    @Requires(property = "spec.name", value = "ExceptionStatusTest")
    @Provider
    public static class SubclassMapper implements ExceptionMapper<SubclassMappedException> {
        @Override
        public Response toResponse(SubclassMappedException exception) {
            return new DelegatingResponse(Response.status(409).build());
        }
    }

    @Requires(property = "spec.name", value = "ExceptionStatusTest")
    @Path("/exception-status")
    public static class ExceptionStatusResource {

        @GET
        @Path("/unmapped")
        public String unmapped() {
            throw new IllegalStateException("unmapped");
        }

        @GET
        @Path("/query")
        public String query(@QueryParam("n") int n) {
            return "n " + n;
        }

        @GET
        @Path("/header")
        public String header(@HeaderParam("n") int n) {
            return "n " + n;
        }

        @GET
        @Path("/path/{n}")
        public String path(@PathParam("n") int n) {
            return "n " + n;
        }

        @GET
        @Path("/null-mapped")
        public String nullMapped() {
            throw new NullMappedException();
        }

        @GET
        @Path("/subclass-mapped")
        public String subclassMapped() {
            throw new SubclassMappedException();
        }

        @GET
        @Path("/subclass-wae")
        public String subclassWebApplicationException() {
            throw new WebApplicationException(new DelegatingResponse(Response.status(410).build()));
        }
    }

    /**
     * A response of the application, not of the runtime delegate.
     */
    static final class DelegatingResponse extends Response {
        private final Response delegate;

        DelegatingResponse(Response delegate) {
            this.delegate = delegate;
        }

        @Override
        public int getStatus() {
            return delegate.getStatus();
        }

        @Override
        public StatusType getStatusInfo() {
            return delegate.getStatusInfo();
        }

        @Override
        public Object getEntity() {
            return delegate.getEntity();
        }

        @Override
        public <T> T readEntity(Class<T> entityType) {
            return delegate.readEntity(entityType);
        }

        @Override
        public <T> T readEntity(GenericType<T> entityType) {
            return delegate.readEntity(entityType);
        }

        @Override
        public <T> T readEntity(Class<T> entityType, Annotation[] annotations) {
            return delegate.readEntity(entityType, annotations);
        }

        @Override
        public <T> T readEntity(GenericType<T> entityType, Annotation[] annotations) {
            return delegate.readEntity(entityType, annotations);
        }

        @Override
        public boolean hasEntity() {
            return delegate.hasEntity();
        }

        @Override
        public boolean bufferEntity() {
            return delegate.bufferEntity();
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public MediaType getMediaType() {
            return delegate.getMediaType();
        }

        @Override
        public Locale getLanguage() {
            return delegate.getLanguage();
        }

        @Override
        public int getLength() {
            return delegate.getLength();
        }

        @Override
        public Set<String> getAllowedMethods() {
            return delegate.getAllowedMethods();
        }

        @Override
        public Map<String, NewCookie> getCookies() {
            return delegate.getCookies();
        }

        @Override
        public EntityTag getEntityTag() {
            return delegate.getEntityTag();
        }

        @Override
        public Date getDate() {
            return delegate.getDate();
        }

        @Override
        public Date getLastModified() {
            return delegate.getLastModified();
        }

        @Override
        public URI getLocation() {
            return delegate.getLocation();
        }

        @Override
        public Set<Link> getLinks() {
            return delegate.getLinks();
        }

        @Override
        public boolean hasLink(String relation) {
            return delegate.hasLink(relation);
        }

        @Override
        public Link getLink(String relation) {
            return delegate.getLink(relation);
        }

        @Override
        public Link.Builder getLinkBuilder(String relation) {
            return delegate.getLinkBuilder(relation);
        }

        @Override
        public MultivaluedMap<String, Object> getMetadata() {
            return delegate.getMetadata();
        }

        @Override
        public MultivaluedMap<String, String> getStringHeaders() {
            return delegate.getStringHeaders();
        }

        @Override
        public String getHeaderString(String name) {
            return delegate.getHeaderString(name);
        }
    }
}
