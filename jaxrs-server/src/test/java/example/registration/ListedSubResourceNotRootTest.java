package example.registration;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.jaxrs.container.RestApplication;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Application;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A class without a {@code @Path} is a sub-resource also when the application lists it: it is
 * reached through its locator only, not from the application path.
 */
@MicronautTest
@Property(name = "spec.name", value = "ListedSubResourceNotRootTest")
class ListedSubResourceNotRootTest {

    @Inject
    @Client("/api")
    HttpClient client;

    @Test
    void listedSubResourceIsNotRoutedFromTheRoot() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.GET("/listed-secret")));
        assertEquals(404, e.getStatus().getCode());
    }

    @Test
    void listedClassSubResourceIsNotRoutedFromTheRoot() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.GET("/listed-class-secret")));
        assertEquals(404, e.getStatus().getCode());
    }

    @Test
    void listedSubResourceIsRoutedThroughItsLocator() {
        assertEquals("secret", client.toBlocking().retrieve(HttpRequest.GET("/listed-root/vault/listed-secret")));
    }

    @Requires(property = "spec.name", value = "ListedSubResourceNotRootTest")
    @Replaces(RestApplication.class)
    @ApplicationPath("/api")
    public static class ListingApplication extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(Root.class, Vault.class, ClassVault.class);
        }
    }

    @Requires(property = "spec.name", value = "ListedSubResourceNotRootTest")
    @Path("listed-root")
    public static class Root {
        @Inject
        Vault vault;

        @Path("vault")
        public Vault vault() {
            return vault;
        }
    }

    @Requires(property = "spec.name", value = "ListedSubResourceNotRootTest")
    public static class Vault {
        @GET
        @Path("listed-secret")
        public String secret() {
            return "secret";
        }
    }

    /**
     * Not a bean: a class the annotation processor did not describe as a resource.
     */
    public static class ClassVault {
        @GET
        @Path("listed-class-secret")
        public String secret() {
            return "secret";
        }
    }
}
