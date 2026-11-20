package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Bean Validation of the resources (JAX-RS chapter 7) with Micronaut Validation: a violation of
 * a parameter, field, property or bean parameter is a 400, of a return value a 500 (JAX-RS 7.6),
 * also for a sub-resource that is not a bean, which Micronaut Validation alone does not validate.
 */
@MicronautTest
@Property(name = "spec.name", value = "BeanValidationTest")
class BeanValidationTest {

    @Inject
    @Client("/api")
    HttpClient client;

    private Result get(String uri) {
        return exchange(HttpRequest.GET(uri));
    }

    private Result exchange(HttpRequest<?> request) {
        try {
            HttpResponse<String> response = client.toBlocking().exchange(request, String.class);
            return new Result(response.getStatus().getCode(), response.getBody().orElse(""));
        } catch (HttpClientResponseException e) {
            return new Result(e.getStatus().getCode(), e.getResponse().getBody(String.class).orElse(""));
        }
    }

    private record Result(int status, String body) {
    }

    @Test
    void parameter() {
        assertEquals(new Result(200, "q=a"), get("/validation/param?q=a"));
        Result result = get("/validation/param?q=");
        assertEquals(400, result.status());
        assertTrue(result.body().contains("q: must not be blank"), result.body());
    }

    @Test
    void singletonParameter() {
        assertEquals(new Result(200, "1"), get("/validation/singleton/1"));
        assertEquals(400, get("/validation/singleton/11").status());
    }

    @Test
    void returnValueIsAServerError() {
        // JAX-RS 7.6: Micronaut Validation alone answers 400
        assertEquals(500, get("/validation/return").status());
        assertEquals(500, get("/validation/singleton-return").status());
        assertEquals(500, get("/validation/async").status());
        assertEquals(new Result(200, "ok"), get("/validation/async?value=ok"));
    }

    @Test
    void responseEntityWithValid() {
        assertEquals(500, get("/validation/response").status());
        assertEquals(200, get("/validation/response?name=x").status());
    }

    @Test
    void entity() {
        Result result = exchange(HttpRequest.POST("/validation/entity", "{\"name\":\"\"}").contentType(MediaType.APPLICATION_JSON_TYPE));
        assertEquals(400, result.status(), result.body());
        assertTrue(result.body().contains("name: must not be blank"), result.body());
        assertEquals(200, exchange(HttpRequest.POST("/validation/entity", "{\"name\":\"a\"}").contentType(MediaType.APPLICATION_JSON_TYPE)).status());
    }

    @Test
    void field() {
        assertEquals(new Result(200, "ab"), get("/field?f=ab"));
        Result result = get("/field?f=abc");
        assertEquals(400, result.status());
        assertTrue(result.body().contains("f: size must be between 0 and 2"), result.body());
    }

    @Test
    void beanParameter() {
        assertEquals(new Result(200, "x"), get("/validation/bean?name=x"));
        Result result = get("/validation/bean");
        assertEquals(400, result.status(), result.body());
        assertTrue(result.body().contains("name: must not be null"), result.body());
        // with @Valid, which cascades: one violation
        result = get("/validation/valid-bean");
        assertEquals(400, result.status(), result.body());
        assertTrue(result.body().contains("name: must not be null"), result.body());
    }

    @Test
    void getterResourceMethodIsAProperty() {
        // JAX-RS 7.4: the constraints of a getter are the ones of a property, validated with the
        // fields, not on execution: a client error, not the server error of a return value
        assertEquals(new Result(200, "v"), get("/getter?v=v"));
        assertEquals(400, get("/getter").status());
    }

    @Test
    void subResourceThatIsNotABean() {
        assertEquals(new Result(200, "sub:a"), get("/validation/locate/sub?v=a"));
        assertEquals(400, get("/validation/locate/sub?v=").status());
        assertEquals(500, get("/validation/locate/sub-return").status());
    }

    @Test
    void locatorParameter() {
        assertEquals(400, get("/validation/locate-checked/abcdef/sub?v=a").status());
        assertEquals(new Result(200, "sub:a"), get("/validation/locate-checked/abc/sub?v=a"));
    }

    @Serdeable
    public record Named(@NotBlank String name) {
    }

    public static class Bean {
        @NotNull
        @QueryParam("name")
        public String name;
    }

    @Requires(property = "spec.name", value = "BeanValidationTest")
    @Path("/validation")
    @Produces("text/plain")
    public static class ValidatedResource {

        @GET
        @Path("param")
        public String param(@NotBlank @QueryParam("q") String q) {
            return "q=" + q;
        }

        @GET
        @Path("return")
        public @Size(max = 1) String returnValue() {
            return "too long";
        }

        @GET
        @Path("async")
        public @NotNull CompletionStage<String> async(@QueryParam("value") String value) {
            return CompletableFuture.completedFuture(value);
        }

        @GET
        @Path("response")
        public @Valid Response response(@QueryParam("name") String name) {
            // the entity is validated, not written: the writer writes its name
            return Response.ok(new Named(name)).type("application/json").build();
        }

        @POST
        @Path("entity")
        @Consumes("application/json")
        public String entity(@Valid Named named) {
            return named.name();
        }

        @GET
        @Path("bean")
        public String bean(@BeanParam Bean bean) {
            return bean.name;
        }

        @GET
        @Path("valid-bean")
        public String validBean(@Valid @BeanParam Bean bean) {
            return bean.name;
        }

        @Path("locate")
        public SubResource locate() {
            return new SubResource();
        }

        @Path("locate-checked/{id}")
        public SubResource locateChecked(@Size(max = 3) @PathParam("id") String id) {
            return new SubResource();
        }
    }

    @Requires(property = "spec.name", value = "BeanValidationTest")
    @Singleton
    @Path("/validation")
    @Produces("text/plain")
    public static class SingletonResource {

        @GET
        @Path("singleton/{n}")
        public String singleton(@Max(10) @PathParam("n") int n) {
            return String.valueOf(n);
        }

        @GET
        @Path("singleton-return")
        public @NotNull String singletonReturn() {
            return null;
        }
    }

    @Requires(property = "spec.name", value = "BeanValidationTest")
    @Path("/field")
    @Produces("text/plain")
    public static class FieldResource {

        @QueryParam("f")
        @Size(max = 2)
        String f;

        @GET
        public String get() {
            return f;
        }
    }

    @Requires(property = "spec.name", value = "BeanValidationTest")
    @Path("/getter")
    @Produces("text/plain")
    public static class GetterResource {

        @QueryParam("v")
        String v;

        @GET
        public @NotNull String getValue() {
            return v;
        }
    }

    /**
     * A sub-resource the locators create, which is not a bean.
     */
    @Produces("text/plain")
    public static class SubResource {

        @GET
        @Path("sub")
        public String sub(@NotBlank @QueryParam("v") String v) {
            return "sub:" + v;
        }

        @GET
        @Path("sub-return")
        public @NotNull String subReturn() {
            return null;
        }
    }
}
