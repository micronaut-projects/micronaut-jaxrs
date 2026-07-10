/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.jaxrs.container;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.jaxrs.common.JaxRsApplicationResources;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.web.router.Router;
import io.micronaut.web.router.UriRouteInfo;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RootPathResourceTest {

    @Test
    void routesEmptyPathResourceAtContextPathRoot() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", "RootPathResourceTest",
            "micronaut.server.context-path", "tck-root"
        ));
             HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            assertEquals("root", client.toBlocking().retrieve("/tck-root/"));
        }
    }

    @Test
    void doesNotApplyJaxRsExceptionMapperOutsideApplicationPath() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", "RootPathResourceTest",
            "micronaut.server.context-path", "tck-root"
        ));
             HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            assertEquals("mapped:404", client.toBlocking().retrieve("/tck-root/missing"));

            HttpClientResponseException outsideApplication = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(HttpRequest.GET("/wrong-root/"), String.class)
            );
            assertEquals(HttpStatus.NOT_FOUND, outsideApplication.getStatus());
        }
    }

    @Test
    void selectsMoreSpecificRootResourceBeforeMethodPath() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of("spec.name", "RootPathResourceTest"));
             HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            assertEquals("long-root", client.toBlocking().retrieve("/match/a/b"));
        }
    }

    @Test
    void selectsMoreSpecificRootResourceForPostWithMediaCandidates() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of("spec.name", "RootPathResourceTest"));
             HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            assertEquals("long-post", client.toBlocking().retrieve(HttpRequest.create(HttpMethod.POST, "/match-post/a/b"), String.class));
            assertEquals("long-post", client.toBlocking().retrieve(
                HttpRequest.POST("/match-post/a/b", "")
                    .contentType(MediaType.TEXT_PLAIN_TYPE),
                String.class
            ));
        }
    }

    @Test
    void selectsMoreSpecificRootResourceForPostWithMediaCandidatesAtContextPath() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", "RootPathResourceTest",
            "micronaut.server.context-path", "jaxrs-root"
        ));
             HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            assertEquals("long-post", client.toBlocking().retrieve(
                HttpRequest.POST("/jaxrs-root/match-post/a/b", "")
                    .contentType(MediaType.TEXT_PLAIN_TYPE),
                String.class
            ));
        }
    }

    @Test
    void selectsTckStyleMoreSpecificRootResourceAtContextPath() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", "RootPathResourceTest",
            "micronaut.server.context-path", "jaxrs-root"
        ));
             HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            assertEquals("tck-long-post", client.toBlocking().retrieve(
                HttpRequest.create(HttpMethod.POST, "/jaxrs-root/resource/subresource/sub"),
                String.class
            ));
        }
    }

    @Test
    void directResourceMethodMediaMismatchReturnsNotAcceptable() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", "RootPathResourceMediaTest"
        ));
             HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            UriRouteInfo<?, ?> route = server.getApplicationContext().getBean(Router.class)
                .uriRoutes()
                .filter(uriRouteInfo -> uriRouteInfo.getDeclaringType().equals(DirectMediaResource.class))
                .findFirst()
                .orElseThrow();
            assertEquals(List.of(MediaType.TEXT_PLAIN_TYPE), route.getProduces());

            HttpClientResponseException notAcceptable = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(
                HttpRequest.GET("/media/plain")
                    .accept(MediaType.TEXT_HTML_TYPE),
                String.class
            ));
            assertEquals(HttpStatus.NOT_ACCEPTABLE, notAcceptable.getStatus());
        }
    }

    @Test
    void directResourceMethodMediaMismatchAtContextPathReturnsNotAcceptable() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", "RootPathResourceMediaTest",
            "micronaut.server.context-path", "jaxrs-media"
        ));
             HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            HttpClientResponseException notAcceptable = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(
                HttpRequest.GET("/jaxrs-media/media/plain")
                    .accept(MediaType.TEXT_HTML_TYPE),
                String.class
            ));
            assertEquals(HttpStatus.NOT_ACCEPTABLE, notAcceptable.getStatus());
        }
    }

    @Test
    void doesNotBacktrackToLessSpecificRootResourceWhenSubResourceLocatorLeavesUnmatchedPath() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", "RootPathResourceNoBacktrackingTest"
        ));
             HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            HttpClientResponseException notFound = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(
                HttpRequest.GET("/no-backtrack/resource/locator/locator/locator"),
                String.class
            ));
            assertEquals(HttpStatus.NOT_FOUND, notFound.getStatus());
        }
    }

    @Requires(property = "spec.name", value = "RootPathResourceMediaTest")
    @Primary
    @Singleton
    @JaxRsApplicationResources("io.micronaut.jaxrs.container.RootPathResourceTest$DirectMediaResource")
    static final class MediaTestApplication extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            throw new AssertionError("Generated application resource metadata should be used");
        }
    }

    @Requires(property = "spec.name", value = "RootPathResourceNoBacktrackingTest")
    @Primary
    @Singleton
    @JaxRsApplicationResources({
        "io.micronaut.jaxrs.container.RootPathResourceTest$NoBacktrackingShortRootResource",
        "io.micronaut.jaxrs.container.RootPathResourceTest$NoBacktrackingLongRootResource"
    })
    static final class NoBacktrackingApplication extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            throw new AssertionError("Generated application resource metadata should be used");
        }
    }

    @Requires(property = "spec.name", value = "RootPathResourceTest")
    @Primary
    @Singleton
    @JaxRsApplicationResources({
        "io.micronaut.jaxrs.container.RootPathResourceTest$EmptyPathResource",
        "io.micronaut.jaxrs.container.RootPathResourceTest$MissingResource",
        "io.micronaut.jaxrs.container.RootPathResourceTest$ShortRootResource",
        "io.micronaut.jaxrs.container.RootPathResourceTest$LongRootResource",
        "io.micronaut.jaxrs.container.RootPathResourceTest$ShortPostRootResource",
        "io.micronaut.jaxrs.container.RootPathResourceTest$LongPostRootResource",
        "io.micronaut.jaxrs.container.RootPathResourceTest$TckShortPostRootResource",
        "io.micronaut.jaxrs.container.RootPathResourceTest$TckLongPostRootResource"
    })
    static final class TestApplication extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            throw new AssertionError("Generated application resource metadata should be used");
        }
    }

    @Requires(property = "spec.name", value = "RootPathResourceTest")
    @Path("")
    static class EmptyPathResource {
        @GET
        String get() {
            return "root";
        }
    }

    @Provider
    @Requires(property = "spec.name", value = "RootPathResourceTest")
    static class WebApplicationExceptionMapper implements ExceptionMapper<WebApplicationException> {
        @Override
        public Response toResponse(WebApplicationException exception) {
            return Response.ok("mapped:" + exception.getResponse().getStatus()).build();
        }
    }

    @Requires(property = "spec.name", value = "RootPathResourceTest")
    @Path("missing")
    static class MissingResource {
        @GET
        String missing() {
            throw new NotFoundException();
        }
    }

    @Requires(property = "spec.name", value = "RootPathResourceTest")
    @Path("match/a")
    static class ShortRootResource {
        @GET
        @Path("b")
        String get() {
            return "short-root";
        }
    }

    @Requires(property = "spec.name", value = "RootPathResourceTest")
    @Path("match/a/b")
    static class LongRootResource {
        @GET
        String get() {
            return "long-root";
        }
    }

    @Requires(property = "spec.name", value = "RootPathResourceTest")
    @Path("match-post/a")
    static class ShortPostRootResource {
        @POST
        @Path("b")
        @Consumes(MediaType.TEXT_PLAIN)
        @Produces(MediaType.TEXT_PLAIN)
        String post() {
            return "short-post";
        }
    }

    @Requires(property = "spec.name", value = "RootPathResourceTest")
    @Path("match-post/a/b")
    static class LongPostRootResource {
        @POST
        @Consumes(MediaType.TEXT_PLAIN)
        @Produces(MediaType.TEXT_PLAIN)
        String post() {
            return "long-post";
        }

        @POST
        String fallbackPost() {
            return "long-post-fallback";
        }
    }

    @Requires(property = "spec.name", value = "RootPathResourceTest")
    @Path("resource/subresource")
    static class TckShortPostRootResource {
        @POST
        @Path("sub")
        @Consumes(MediaType.TEXT_PLAIN)
        @Produces(MediaType.TEXT_PLAIN)
        String post() {
            return "tck-short-post";
        }
    }

    @Requires(property = "spec.name", value = "RootPathResourceTest")
    @Path("resource/subresource/sub")
    static class TckLongPostRootResource {
        @POST
        @Consumes(MediaType.TEXT_PLAIN)
        String post() {
            return "tck-long-post";
        }

        @POST
        String fallbackPost() {
            return "tck-long-post-fallback";
        }
    }

    @Requires(property = "spec.name", value = "RootPathResourceTest")
    public static class UnlistedRootResource {
        @GET
        public String get() {
            return "unlisted";
        }
    }

    @Requires(property = "spec.name", value = "RootPathResourceMediaTest")
    @Path("media")
    static class DirectMediaResource {
        @GET
        @Path("plain")
        @Produces(MediaType.TEXT_PLAIN)
        String plain() {
            return "plain";
        }
    }

    @Requires(property = "spec.name", value = "RootPathResourceNoBacktrackingTest")
    @Path("no-backtrack/resource")
    static class NoBacktrackingShortRootResource {
        @GET
        @Path("locator/locator/locator")
        String locator() {
            return "short-root";
        }
    }

    @Requires(property = "spec.name", value = "RootPathResourceNoBacktrackingTest")
    @Path("no-backtrack/resource/locator")
    static class NoBacktrackingLongRootResource {
        @Path("locator")
        NoBacktrackingSubResource locator() {
            return new NoBacktrackingSubResource();
        }

        @GET
        @Path("locator")
        String subresourcePrecedence() {
            return "long-root";
        }
    }

    static class NoBacktrackingSubResource {
        @GET
        String get() {
            return "subresource";
        }
    }
}
