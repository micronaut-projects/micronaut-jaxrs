package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NameBinding;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A sub-resource whose methods inherit their annotations from an interface or a superclass
 * (JAX-RS 3.6) is routed.
 */
@MicronautTest
@Property(name = "spec.name", value = "InheritedSubResourceMethodTest")
class InheritedSubResourceMethodTest {

    @Inject
    @Client("/api/inherited-sub-resource")
    HttpClient client;

    @Test
    void methodOfAnInterface() {
        assertEquals("interface", client.toBlocking().retrieve(HttpRequest.GET("/interface")));
    }

    @Test
    void methodOfASuperclass() {
        assertEquals("superclass", client.toBlocking().retrieve(HttpRequest.GET("/superclass/get")));
    }

    @Test
    void methodOfAnInterfaceWithANameBinding() {
        assertEquals("bound", client.toBlocking().retrieve(HttpRequest.GET("/bound")));
    }

    @Test
    void customMethodOfAnInterface() {
        assertEquals("custom", client.toBlocking().retrieve(HttpRequest.create(HttpMethod.CUSTOM, "/custom", "REPORT")));
    }

    @Test
    void customMethodDirect() {
        assertEquals("direct", client.toBlocking().retrieve(HttpRequest.create(HttpMethod.CUSTOM, "/direct", "REPORT")));
    }

    public static class DirectChild {
        @Report
        public String get() {
            return "direct";
        }
    }

    @jakarta.ws.rs.HttpMethod("REPORT")
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface Report {
    }

    @NameBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Marked {
    }

    @Requires(property = "spec.name", value = "InheritedSubResourceMethodTest")
    @Provider
    @Marked
    public static class MarkingFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            responseContext.setEntity("bound");
        }
    }

    public interface Api {
        @GET
        String get();
    }

    public interface BoundApi {
        @GET
        @Marked
        String get();
    }

    public interface CustomApi {
        @Report
        String get();
    }

    public static class BoundChild implements BoundApi {
        @Override
        public String get() {
            return "unbound";
        }
    }

    public static class CustomChild implements CustomApi {
        @Override
        public String get() {
            return "custom";
        }
    }

    public static class InterfaceChild implements Api {
        @Override
        public String get() {
            return "interface";
        }
    }

    public abstract static class Base {
        @GET
        @Path("get")
        public abstract String get();
    }

    public static class SuperclassChild extends Base {
        @Override
        public String get() {
            return "superclass";
        }
    }

    @Requires(property = "spec.name", value = "InheritedSubResourceMethodTest")
    @Path("/inherited-sub-resource")
    public static class Locators {
        @Path("interface")
        public InterfaceChild interfaceChild() {
            return new InterfaceChild();
        }

        @Path("bound")
        public BoundChild boundChild() {
            return new BoundChild();
        }

        @Path("direct")
        public DirectChild directChild() {
            return new DirectChild();
        }

        @Path("custom")
        public CustomChild customChild() {
            return new CustomChild();
        }

        @Path("superclass")
        public SuperclassChild superclassChild() {
            return new SuperclassChild();
        }
    }
}
