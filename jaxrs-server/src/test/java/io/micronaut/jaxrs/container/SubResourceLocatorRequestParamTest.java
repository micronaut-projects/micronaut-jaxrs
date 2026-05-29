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
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubResourceLocatorRequestParamTest {

    @Test
    void bindsRequestParametersOnSubResourceTargetMethods() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of("spec.name", "SubResourceLocatorRequestParamTest"));
            HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            assertEquals("query:blue", client.toBlocking().retrieve("/api/locator-param/query/query?value=blue"));
            assertEquals("path:default", client.toBlocking().retrieve("/api/locator-param/path/path/default"));
            assertEquals("matrix:blue", client.toBlocking().retrieve("/api/locator-param/matrix/matrix;value=blue"));
            assertEquals("matrix:default", client.toBlocking().retrieve("/api/locator-param/matrix/matrix"));
            assertEquals("header:blue", client.toBlocking().retrieve(HttpRequest.GET("/api/locator-param/header/header").header("X-Value", "blue"), String.class));
            assertEquals("header:default", client.toBlocking().retrieve("/api/locator-param/header/header"));
            assertEquals("cookie:blue", client.toBlocking().retrieve(HttpRequest.GET("/api/locator-param/cookie/cookie").cookie(Cookie.of("value", "blue")), String.class));
            assertEquals("cookie:default", client.toBlocking().retrieve("/api/locator-param/cookie/cookie"));
            assertEquals("form:blue", client.toBlocking().retrieve(form("/api/locator-param/form/form", "value=blue")));
            assertEquals("form:default", client.toBlocking().retrieve(form("/api/locator-param/form/form", "")));
        }
    }

    @Test
    void dispatchesSupportedHttpMethodsOnSameSubResourceLocator() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of("spec.name", "SubResourceLocatorRequestParamTest"));
            HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            assertEquals("get", client.toBlocking().retrieve("/api/locator-param/multi"));
            assertEquals("post", client.toBlocking().retrieve(
                HttpRequest.POST("/api/locator-param/multi", "body")
                    .contentType(MediaType.TEXT_PLAIN_TYPE)
                    .accept(MediaType.TEXT_PLAIN_TYPE),
                String.class
            ));

            HttpClientResponseException unsupported = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(
                HttpRequest.POST("/api/locator-param/multi", "body")
                    .contentType(MediaType.APPLICATION_JSON_TYPE)
                    .accept(MediaType.TEXT_PLAIN_TYPE),
                String.class
            ));
            assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, unsupported.getStatus());

            HttpClientResponseException notAcceptable = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(
                HttpRequest.POST("/api/locator-param/multi", "body")
                    .contentType(MediaType.TEXT_PLAIN_TYPE)
                    .accept(MediaType.APPLICATION_JSON_TYPE),
                String.class
            ));
            assertEquals(HttpStatus.NOT_ACCEPTABLE, notAcceptable.getStatus());
        }
    }

    @Test
    void subResourceLocatorUsesDeclaredTargetMethodMediaTypes() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of("spec.name", "SubResourceLocatorRequestParamTest"));
            HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            assertEquals("single=a", client.toBlocking().retrieve(
                HttpRequest.POST("/api/locator-param/inherited/a", "")
                    .accept(MediaType.TEXT_HTML_TYPE),
                String.class
            ));
            assertEquals("list=abcdef", client.toBlocking().retrieve(
                HttpRequest.POST("/api/locator-param/inherited/a/b/c/d/e/f", "")
                    .accept(MediaType.TEXT_PLAIN_TYPE),
                String.class
            ));
        }
    }

    @Test
    void resourceMethodTakesPrecedenceOverSamePathSubResourceLocator() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of("spec.name", "SubResourceLocatorRequestParamTest"));
            HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            assertEquals("direct", client.toBlocking().retrieve("/api/locator-param/precedence/target"));
        }
    }

    @Test
    void optionsRequestToSubResourceMethodReturnsAllowedMethods() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of("spec.name", "SubResourceLocatorRequestParamTest"));
            HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.OPTIONS("/api/locator-param/query/query"), String.class);

            assertEquals(HttpStatus.OK, response.getStatus());
            assertTrue(response.getHeaders().get(HttpHeaders.ALLOW).contains(HttpMethod.GET.name()));
            assertTrue(response.getHeaders().get(HttpHeaders.ALLOW).contains(HttpMethod.OPTIONS.name()));
            assertTrue(response.getHeaders().get(HttpHeaders.ALLOW).contains(HttpMethod.HEAD.name()));
        }
    }

    @Test
    void subResourceTargetParameterExceptionsUseJaxRsExceptionMappers() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of("spec.name", "SubResourceLocatorExceptionMapperTest"));
            HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            assertEquals("mapped:CREATED", client.toBlocking().retrieve("/api/locator-exception/child/web?value=x"));
            assertEquals(
                "mapped:BAD_REQUEST:java.lang.IllegalArgumentException",
                client.toBlocking().retrieve(HttpRequest.GET("/api/locator-exception/child/illegal").header("X-Value", "x"), String.class)
            );
            assertEquals("mapped:BAD_REQUEST", client.toBlocking().retrieve("/api/locator-exception/terminal/no-entity"));
            assertEquals("terminal-entity", client.toBlocking().retrieve("/api/locator-exception/terminal/entity"));
        }
    }

    private static HttpRequest<String> form(String path, String body) {
        return HttpRequest.POST(path, body).contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
    }

    @Requires(property = "spec.name", value = "SubResourceLocatorRequestParamTest")
    @Path("/locator-param")
    static class RootResource {

        @Path("query")
        ChildResource query() {
            return new ChildResource("query");
        }

        @Path("path")
        ChildResource path() {
            return new ChildResource("path");
        }

        @Path("matrix")
        ChildResource matrix() {
            return new ChildResource("matrix");
        }

        @Path("form")
        ChildResource form() {
            return new ChildResource("form");
        }

        @Path("cookie")
        ChildResource cookie() {
            return new ChildResource("cookie");
        }

        @Path("header")
        ChildResource header() {
            return new ChildResource("header");
        }

        @Path("multi")
        MultiMethodResource multi() {
            return new MultiMethodResource();
        }

        @Path("inherited/{id}")
        InheritedPostResource inherited(@PathParam("id") String id) {
            return new InheritedPostResource(new InheritedGetResource().single(id));
        }

        @Path("inherited/{id1}/{id2}/{id3}/{id4}/{id5}/{id6}")
        InheritedPostResource inherited(@PathParam("id1") String id1,
                                        @PathParam("id2") String id2,
                                        @PathParam("id3") String id3,
                                        @PathParam("id4") String id4,
                                        @PathParam("id5") String id5,
                                        @PathParam("id6") String id6) {
            return new InheritedPostResource(new InheritedGetResource().list(id1, id2, id3, id4, id5, id6));
        }

        @GET
        @Path("precedence/target")
        String directPrecedence() {
            return "direct";
        }

        @Path("precedence/target")
        PrecedenceResource locatorPrecedence() {
            return new PrecedenceResource();
        }
    }

    @Path("ignored")
    static class ChildResource {
        private final String prefix;

        ChildResource(String prefix) {
            this.prefix = prefix;
        }

        @GET
        @Path("query")
        String query(@QueryParam("value") String value) {
            return prefix + ":" + value;
        }

        @GET
        @Path("path/default")
        String path(@DefaultValue("default") @PathParam("value") String value) {
            return prefix + ":" + value;
        }

        @GET
        @Path("matrix")
        String matrix(@DefaultValue("default") @MatrixParam("value") String value) {
            return prefix + ":" + value;
        }

        @POST
        @Path("form")
        String form(@DefaultValue("default") @FormParam("value") String value) {
            return prefix + ":" + value;
        }

        @GET
        @Path("cookie")
        String cookie(@DefaultValue("default") @CookieParam("value") String value) {
            return prefix + ":" + value;
        }

        @GET
        @Path("header")
        String header(@DefaultValue("default") @HeaderParam("X-Value") String value) {
            return prefix + ":" + value;
        }
    }

    static class MultiMethodResource {

        @GET
        String get() {
            return "get";
        }

        @POST
        @Consumes(MediaType.TEXT_PLAIN)
        @Produces(MediaType.TEXT_PLAIN)
        String post() {
            return "post";
        }
    }

    static class InheritedGetResource {

        @GET
        @Path("{id}")
        @Produces(MediaType.TEXT_HTML)
        String single(@PathParam("id") String id) {
            return "single=" + id;
        }

        @GET
        @Path("{id}/{id}/{id}/{id}/{id}/{id}")
        @Produces(MediaType.TEXT_PLAIN)
        String list(@PathParam("id") String id1,
                    @PathParam("id") String id2,
                    @PathParam("id") String id3,
                    @PathParam("id") String id4,
                    @PathParam("id") String id5,
                    @PathParam("id") String id6) {
            return "list=" + id1 + id2 + id3 + id4 + id5 + id6;
        }
    }

    static class InheritedPostResource extends InheritedGetResource {
        private final String value;

        InheritedPostResource(String value) {
            this.value = value;
        }

        @POST
        String value() {
            return value;
        }
    }

    static class PrecedenceResource {

        @GET
        String get() {
            return "locator";
        }
    }

    @Requires(property = "spec.name", value = "SubResourceLocatorExceptionMapperTest")
    @Path("/locator-exception")
    static class ExceptionRootResource {

        @Path("child")
        ExceptionChildResource child() {
            return new ExceptionChildResource();
        }

        @Path("terminal/{kind}")
        Response terminal(@PathParam("kind") String kind) {
            if ("entity".equals(kind)) {
                throw new WebApplicationException("direct", Response.ok("terminal-entity").build());
            }
            throw new WebApplicationException("no-entity", Response.status(Response.Status.BAD_REQUEST).build());
        }
    }

    static class ExceptionChildResource {

        @GET
        @Path("web")
        String web(@QueryParam("value") CreatedParam param) {
            return "unreachable";
        }

        @GET
        @Path("illegal")
        String illegal(@HeaderParam("X-Value") IllegalParam param) {
            return "unreachable";
        }
    }

    static final class CreatedParam {
    }

    static final class IllegalParam {
    }

    @Requires(property = "spec.name", value = "SubResourceLocatorExceptionMapperTest")
    @Provider
    static final class ThrowingParamConverterProvider implements ParamConverterProvider {

        @Override
        @SuppressWarnings("unchecked")
        public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
            if (rawType == CreatedParam.class) {
                return (ParamConverter<T>) new ParamConverter<CreatedParam>() {
                    @Override
                    public CreatedParam fromString(String value) {
                        throw new WebApplicationException(Response.Status.CREATED);
                    }

                    @Override
                    public String toString(CreatedParam value) {
                        return "";
                    }
                };
            }
            if (rawType == IllegalParam.class) {
                return (ParamConverter<T>) new ParamConverter<IllegalParam>() {
                    @Override
                    public IllegalParam fromString(String value) {
                        throw new IllegalArgumentException("bad parameter");
                    }

                    @Override
                    public String toString(IllegalParam value) {
                        return "";
                    }
                };
            }
            return null;
        }
    }

    @Requires(property = "spec.name", value = "SubResourceLocatorExceptionMapperTest")
    @Provider
    static final class TestWebApplicationExceptionMapper implements ExceptionMapper<WebApplicationException> {

        @Override
        public Response toResponse(WebApplicationException exception) {
            Response.Status status = Response.Status.fromStatusCode(exception.getResponse().getStatus());
            StringBuilder body = new StringBuilder("mapped:");
            body.append(status == null ? exception.getResponse().getStatus() : status.name());
            Throwable cause = exception.getCause();
            if (cause != null) {
                body.append(':').append(cause.getClass().getName());
            }
            return Response.ok(body.toString()).build();
        }
    }
}
