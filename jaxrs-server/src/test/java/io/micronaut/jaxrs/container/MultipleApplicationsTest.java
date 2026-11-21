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
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NameBinding;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Several {@link Application} beans in one context: each one serves the resources it registers
 * under its own path, with its own instances, properties, filters, interceptors and exception
 * mappers, which do not apply to the other ones.
 */
@MicronautTest
@Property(name = "spec.name", value = "MultipleApplicationsTest")
class MultipleApplicationsTest {

    @Inject
    @Client("/")
    HttpClient client;

    private HttpResponse<String> get(String uri) {
        try {
            return client.toBlocking().exchange(HttpRequest.GET(uri), String.class);
        } catch (HttpClientResponseException e) {
            return (HttpResponse<String>) e.getResponse();
        }
    }

    @Test
    void eachApplicationHasItsPathPropertiesAndInstances() {
        HttpResponse<String> one = get("/one/res");
        assertEquals(200, one.code());
        // the properties of the Configuration of the application
        assertEquals("one", one.body());
        // a distinct instance of the same provider class in each application
        assertEquals("one", one.header("X-Name"));
        HttpResponse<String> two = get("/two/res");
        assertEquals(200, two.code());
        assertEquals("two", two.body());
        assertEquals("two", two.header("X-Name"));
    }

    @Test
    void subResourceOfTheApplicationOfItsLocator() {
        HttpResponse<String> one = get("/one/res/sub");
        assertEquals("sub one", one.body());
        assertEquals("yes", one.header("X-One"));
        HttpResponse<String> two = get("/two/res/sub");
        assertEquals("sub two", two.body());
        assertNull(two.header("X-One"));
    }

    @Test
    void contextApplication() {
        assertEquals("AppOne", get("/one/res/app").body());
        assertEquals("AppTwo", get("/two/res/app").body());
    }

    @Test
    void exceptionMappersDoNotLeak() {
        assertEquals(418, get("/one/res/fail").code());
        assertEquals(500, get("/two/res/fail").code());
    }

    @Test
    void filtersDoNotLeak() {
        // the filter only the first application lists
        assertEquals("yes", get("/one/res").header("X-One"));
        assertNull(get("/two/res").header("X-One"));
        // its pre-matching filter, for the requests under its path only
        assertEquals("yes", get("/one/res").header("X-Pre"));
        assertNull(get("/two/res").header("X-Pre"));
    }

    @Test
    void nameBoundFilterOfOneApplication() {
        assertEquals("yes", get("/one/res/tagged").header("X-Bound"));
        assertNull(get("/one/res").header("X-Bound"));
        assertNull(get("/two/res/tagged").header("X-Bound"));
    }

    @Test
    void beanValidationInEachApplication() {
        assertEquals(400, get("/one/res/valid").code());
        assertEquals(400, get("/two/res/valid").code());
        assertEquals("ok", get("/two/res/valid?q=ok").body());
    }

    @Requires(property = "spec.name", value = "MultipleApplicationsTest")
    @ApplicationPath("/one")
    public static class AppOne extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(MultiResource.class, OneFilter.class, OnePreMatchingFilter.class, OneMapper.class, BoundFilter.class);
        }

        @Override
        public Set<Object> getSingletons() {
            return Set.of(new NameFilter("one"));
        }

        @Override
        public Map<String, Object> getProperties() {
            return Map.of("name", "one");
        }
    }

    @Requires(property = "spec.name", value = "MultipleApplicationsTest")
    @ApplicationPath("/two")
    public static class AppTwo extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(MultiResource.class);
        }

        @Override
        public Set<Object> getSingletons() {
            return Set.of(new NameFilter("two"));
        }

        @Override
        public Map<String, Object> getProperties() {
            return Map.of("name", "two");
        }
    }

    @NameBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Tagged {
    }

    @Path("/res")
    @Produces("text/plain")
    public static class MultiResource {

        @Context
        Configuration configuration;

        @GET
        public String get() {
            return String.valueOf(configuration.getProperty("name"));
        }

        @Path("sub")
        public SubResource sub() {
            return new SubResource();
        }

        @GET
        @Path("app")
        public String app(@Context Application application) {
            return application.getClass().getSimpleName();
        }

        @GET
        @Path("fail")
        public String fail() {
            throw new IllegalStateException("fail");
        }

        @GET
        @Path("tagged")
        @Tagged
        public String tagged() {
            return "tagged";
        }

        @GET
        @Path("valid")
        public String valid(@NotBlank @QueryParam("q") String q) {
            return q;
        }
    }

    /**
     * A sub-resource a locator returns: served with the application of its locator.
     */
    @Produces("text/plain")
    public static class SubResource {
        @GET
        public String get(@Context Configuration configuration) {
            return "sub " + configuration.getProperty("name");
        }
    }

    /**
     * Added as an instance by each application: one per application.
     */
    public static class NameFilter implements ContainerResponseFilter {
        private final String name;

        public NameFilter(String name) {
            this.name = name;
        }

        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            responseContext.getHeaders().putSingle("X-Name", name);
            Object pre = requestContext.getProperty("pre");
            if (pre != null) {
                responseContext.getHeaders().putSingle("X-Pre", pre);
            }
        }
    }

    public static class OneFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            responseContext.getHeaders().putSingle("X-One", "yes");
        }
    }

    @PreMatching
    public static class OnePreMatchingFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            requestContext.setProperty("pre", "yes");
        }
    }

    @Tagged
    public static class BoundFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            responseContext.getHeaders().putSingle("X-Bound", "yes");
        }
    }

    public static class OneMapper implements ExceptionMapper<IllegalStateException> {
        @Override
        public Response toResponse(IllegalStateException exception) {
            return Response.status(418).build();
        }
    }
}
