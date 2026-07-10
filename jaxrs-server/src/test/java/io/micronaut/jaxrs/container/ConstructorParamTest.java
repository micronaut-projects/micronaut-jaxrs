package io.micronaut.jaxrs.container;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConstructorParamTest {

    @Test
    void injectsRequestConstructorParameters() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of("spec.name", "ConstructorParamTest"));
             HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            assertEquals("header-value", client.toBlocking().retrieve(
                HttpRequest.GET("/api/constructor/header").header("X-Value", "header-value")
            ));
            assertEquals("cookie-value", client.toBlocking().retrieve(
                HttpRequest.GET("/api/constructor/cookie").header("Cookie", "value=cookie-value")
            ));
            assertEquals("matrix-value", client.toBlocking().retrieve("/api/constructor/matrix;value=matrix-value"));
            assertEquals("query-value", client.toBlocking().retrieve("/api/constructor/query?value=query-value"));
            assertEquals("path-value", client.toBlocking().retrieve("/api/constructor/path/path-value"));
        }
    }

    @Requires(property = "spec.name", value = "ConstructorParamTest")
    @Path("/constructor/header")
    public static class HeaderConstructorResource {
        private final String value;

        public HeaderConstructorResource(@HeaderParam("X-Value") String value) {
            this.value = value;
        }

        @GET
        @Produces("text/plain")
        public String get() {
            return value;
        }
    }

    @Requires(property = "spec.name", value = "ConstructorParamTest")
    @Path("/constructor/cookie")
    public static class CookieConstructorResource {
        private final String value;

        public CookieConstructorResource(@CookieParam("value") String value) {
            this.value = value;
        }

        @GET
        @Produces("text/plain")
        public String get() {
            return value;
        }
    }

    @Requires(property = "spec.name", value = "ConstructorParamTest")
    @Path("/constructor/matrix")
    public static class MatrixConstructorResource {
        private final String value;

        public MatrixConstructorResource(@MatrixParam("value") String value) {
            this.value = value;
        }

        @GET
        @Produces("text/plain")
        public String get() {
            return value;
        }
    }

    @Requires(property = "spec.name", value = "ConstructorParamTest")
    @Path("/constructor/query")
    public static class QueryConstructorResource {
        private final String value;

        public QueryConstructorResource(@QueryParam("value") String value) {
            this.value = value;
        }

        @GET
        @Produces("text/plain")
        public String get() {
            return value;
        }
    }

    @Requires(property = "spec.name", value = "ConstructorParamTest")
    @Path("/constructor/path/{value}")
    public static class PathConstructorResource {
        private final String value;

        public PathConstructorResource(@PathParam("value") String value) {
            this.value = value;
        }

        @GET
        @Produces("text/plain")
        public String get() {
            return value;
        }
    }
}
