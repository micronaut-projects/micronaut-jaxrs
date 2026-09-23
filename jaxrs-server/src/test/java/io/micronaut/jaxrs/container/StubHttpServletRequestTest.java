package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A {@code @Context} servlet request on a server that is not a servlet container is a stub of the
 * request, whose attributes are the properties of the request filters.
 */
@MicronautTest
@Property(name = "spec.name", value = "StubHttpServletRequestTest")
class StubHttpServletRequestTest {

    @Inject
    @Client("/api/servlet-request")
    HttpClient client;

    @Test
    void parameterIsTheStubOfTheRequest() {
        assertEquals("GET /api/servlet-request/parameter a=1&b=2 1 value",
            client.toBlocking().retrieve(HttpRequest.GET("/parameter?a=1&b=2").header("X-Test", "value")));
    }

    @Test
    void attributesAreThePropertiesOfTheFilters() {
        assertEquals("fromContext fromServletRequest",
            client.toBlocking().retrieve(HttpRequest.GET("/attributes").header("X-Servlet-Filter", "true")));
    }

    @Test
    void fieldOfTheResourceAndServletRequestParameter() {
        assertEquals("true /api/servlet-request/field",
            client.toBlocking().retrieve(HttpRequest.GET("/field")));
    }

    @Requires(property = "spec.name", value = "StubHttpServletRequestTest")
    @Provider
    public static class ServletRequestFilter implements ContainerRequestFilter {
        // a singleton: the servlet request of the current request
        @Context
        HttpServletRequest servletRequest;

        @Override
        public void filter(ContainerRequestContext requestContext) {
            if (requestContext.getHeaderString("X-Servlet-Filter") != null) {
                requestContext.setProperty("context", "fromContext");
                servletRequest.setAttribute("servlet", "fromServletRequest");
            }
        }
    }

    @Requires(property = "spec.name", value = "StubHttpServletRequestTest")
    @Path("/servlet-request")
    public static class ServletRequestResource {
        // created for every request
        @Context
        HttpServletRequest servletRequest;

        @GET
        @Path("parameter")
        public String parameter(@Context HttpServletRequest request) {
            return request.getMethod() + " " + request.getRequestURI() + " " + request.getQueryString() + " "
                + request.getParameter("a") + " " + request.getHeader("X-Test");
        }

        @GET
        @Path("attributes")
        public String attributes(@Context HttpServletRequest request) {
            return request.getAttribute("context") + " " + request.getAttribute("servlet");
        }

        @GET
        @Path("field")
        public String field(@Context ServletRequest request) {
            return (servletRequest == request) + " " + servletRequest.getRequestURI();
        }
    }
}
