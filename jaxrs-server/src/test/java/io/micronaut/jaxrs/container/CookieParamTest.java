package io.micronaut.jaxrs.container;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.bind.ArgumentBinder;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.bind.RequestBinderRegistry;
import io.micronaut.http.bind.binders.RequestArgumentBinder;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Singleton;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CookieParamTest {

    @Test
    void customParamConverterDoesNotConvertAbsentReferenceParam() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "CookieParamTest"))) {
            RequestArgumentBinder<ConvertedCookieParam> binder = customParamBinder(context);
            Argument<ConvertedCookieParam> argument = customArgument(context);
            HttpRequest<?> request = HttpRequest.GET("/api/cookie-param/custom");

            assertTrue(binder.bind(ConversionContext.of(argument), request).getValue().isEmpty());
        }
    }

    @Test
    void customParamConverterBindsPresentReferenceParam() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "CookieParamTest"))) {
            RequestArgumentBinder<ConvertedCookieParam> binder = customParamBinder(context);
            Argument<ConvertedCookieParam> argument = customArgument(context);
            HttpRequest<?> request = HttpRequest.GET("/api/cookie-param/custom")
                .cookie(Cookie.of("custom", "blue"));

            assertEquals("blue", binder.bind(ConversionContext.of(argument), request).getValue().orElseThrow().value);
        }
    }

    @Test
    void customParamConverterBindsPresentSortedSetElements() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "CookieParamTest"))) {
            CookieParamArgumentBinder<SortedSet<ConvertedCookieParam>> binder = new CookieParamArgumentBinder<>(
                context.getBean(ConversionService.class),
                List.of(new ConvertedCookieParamConverterProvider())
            );
            Argument<SortedSet<ConvertedCookieParam>> argument = customSortedSetArgument(context);
            HttpRequest<?> request = HttpRequest.GET("/api/cookie-param/custom-sorted-set")
                .cookie(Cookie.of("custom", "blue"));

            SortedSet<ConvertedCookieParam> result = binder.createSpecific(argument)
                .bind(ConversionContext.of(argument), request)
                .getValue()
                .orElseThrow();

            assertEquals("blue", result.first().value);
        }
    }

    @Test
    void registryUsesParamConverterProviderForCookieParam() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "CookieParamTest"))) {
            Argument<ConvertedCookieParam> argument = customArgument(context);
            @SuppressWarnings("unchecked")
            ArgumentBinder<ConvertedCookieParam, HttpRequest<?>> binder = (ArgumentBinder<ConvertedCookieParam, HttpRequest<?>>) context.getBean(RequestBinderRegistry.class)
                .findArgumentBinder(argument)
                .orElseThrow();

            ConvertedCookieParam result = binder.bind(ConversionContext.of(argument), HttpRequest.GET("/api/cookie-param/custom").cookie(Cookie.of("custom", "blue")))
                .getValue()
                .orElseThrow();

            assertEquals("blue", result.value);
        }
    }

    @Test
    void scalarCookieParamUsesCookieRequestValue() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "CookieParamTest"))) {
            BeanDefinition<TestController> definition = context.getBeanDefinition(TestController.class);
            ExecutableMethod<TestController, Object> method = definition.getRequiredMethod("name", String.class);
            @SuppressWarnings("unchecked")
            Argument<String> argument = (Argument<String>) method.getArguments()[0];
            @SuppressWarnings("unchecked")
            ArgumentBinder<String, HttpRequest<?>> binder = (ArgumentBinder<String, HttpRequest<?>>) context.getBean(RequestBinderRegistry.class)
                .findArgumentBinder(argument)
                .orElseThrow();

            String result = binder.bind(ConversionContext.of(argument), HttpRequest.GET("/api/cookie-param/name").cookie(Cookie.of("name", "blue")))
                .getValue()
                .orElseThrow();

            assertEquals("blue", result);
        }
    }

    @Test
    void scalarCookieParamUsesDefaultValue() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "CookieParamTest"))) {
            BeanDefinition<TestController> definition = context.getBeanDefinition(TestController.class);
            ExecutableMethod<TestController, Object> method = definition.getRequiredMethod("defaultName", String.class);
            @SuppressWarnings("unchecked")
            Argument<String> argument = (Argument<String>) method.getArguments()[0];
            @SuppressWarnings("unchecked")
            ArgumentBinder<String, HttpRequest<?>> binder = (ArgumentBinder<String, HttpRequest<?>>) context.getBean(RequestBinderRegistry.class)
                .findArgumentBinder(argument)
                .orElseThrow();

            String result = binder.bind(ConversionContext.of(argument), HttpRequest.GET("/api/cookie-param/default-name"))
                .getValue()
                .orElseThrow();

            assertEquals("green", result);
        }
    }

    @Test
    void bindsJakartaCookieParam() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "CookieParamTest"))) {
            BeanDefinition<TestController> definition = context.getBeanDefinition(TestController.class);
            ExecutableMethod<TestController, Object> method = definition.getRequiredMethod("cookie", jakarta.ws.rs.core.Cookie.class);
            @SuppressWarnings("unchecked")
            Argument<jakarta.ws.rs.core.Cookie> argument = (Argument<jakarta.ws.rs.core.Cookie>) method.getArguments()[0];
            @SuppressWarnings("unchecked")
            ArgumentBinder<jakarta.ws.rs.core.Cookie, HttpRequest<?>> binder = (ArgumentBinder<jakarta.ws.rs.core.Cookie, HttpRequest<?>>) context.getBean(RequestBinderRegistry.class)
                .findArgumentBinder(argument)
                .orElseThrow();

            jakarta.ws.rs.core.Cookie result = binder.bind(ConversionContext.of(argument), HttpRequest.GET("/api/cookie-param/cookie").cookie(Cookie.of("cookie", "blue")))
                .getValue()
                .orElseThrow();

            assertEquals("cookie", result.getName());
            assertEquals("blue", result.getValue());
        }
    }

    @Test
    void invokesSubResourceLocatorReturnedResponseMethod() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of("spec.name", "CookieParamLocatorResponseTest"));
             HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            HttpResponse<String> response = client.toBlocking().exchange(
                HttpRequest.POST("/api/cookie-response-locator/child", "").cookie(Cookie.of("name", "blue")),
                String.class
            );

            assertEquals("blue", response.body());
            assertTrue(response.getHeaders().getAll("Set-Cookie").stream().anyMatch(value -> value.contains("echo=blue")));
        }
    }

    @Test
    void customParamConverterRuntimeFailureIsBadRequest() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "CookieParamTest"))) {
            RequestArgumentBinder<ConvertedCookieParam> binder = customParamBinder(context);
            Argument<ConvertedCookieParam> argument = customArgument(context);
            HttpRequest<?> request = HttpRequest.GET("/api/cookie-param/custom")
                .cookie(Cookie.of("custom", "throw"));

            assertThrows(jakarta.ws.rs.BadRequestException.class, () -> binder.bind(ConversionContext.of(argument), request));
        }
    }

    @SuppressWarnings("unchecked")
    private Argument<ConvertedCookieParam> customArgument(ApplicationContext context) {
        BeanDefinition<TestController> definition = context.getBeanDefinition(TestController.class);
        ExecutableMethod<TestController, Object> method = definition.getRequiredMethod("custom", ConvertedCookieParam.class);
        return (Argument<ConvertedCookieParam>) method.getArguments()[0];
    }

    @SuppressWarnings("unchecked")
    private Argument<SortedSet<ConvertedCookieParam>> customSortedSetArgument(ApplicationContext context) {
        BeanDefinition<TestController> definition = context.getBeanDefinition(TestController.class);
        ExecutableMethod<TestController, Object> method = definition.getRequiredMethod("customSortedSet", SortedSet.class);
        return (Argument<SortedSet<ConvertedCookieParam>>) method.getArguments()[0];
    }

    private RequestArgumentBinder<ConvertedCookieParam> customParamBinder(ApplicationContext context) {
        CookieParamArgumentBinder<ConvertedCookieParam> binder = new CookieParamArgumentBinder<>(
            context.getBean(ConversionService.class),
            List.of(new ConvertedCookieParamConverterProvider())
        );
        return binder.createSpecific(customArgument(context));
    }

    @Requires(property = "spec.name", value = "CookieParamTest")
    @Path("/cookie-param")
    static class TestController {

        @GET
        @Path("/custom")
        public String custom(@CookieParam("custom") ConvertedCookieParam custom) {
            return custom == null ? "null" : custom.value;
        }

        @GET
        @Path("/custom-sorted-set")
        public String customSortedSet(@DefaultValue("default") @CookieParam("custom") SortedSet<ConvertedCookieParam> custom) {
            return custom == null || custom.isEmpty() ? "null" : custom.first().value;
        }

        @GET
        @Path("/name")
        public String name(@CookieParam("name") String name) {
            return name;
        }

        @GET
        @Path("/default-name")
        public String defaultName(@DefaultValue("green") @CookieParam("name") String name) {
            return name;
        }

        @GET
        @Path("/cookie")
        public String cookie(@CookieParam("cookie") jakarta.ws.rs.core.Cookie cookie) {
            return cookie.getValue();
        }
    }

    @Requires(property = "spec.name", value = "CookieParamLocatorResponseTest")
    @Path("cookie-response-locator")
    static class ResponseLocatorRoot {

        @Path("child")
        public ResponseLocatorMiddle child(@CookieParam("name") String name) {
            return new ResponseLocatorMiddle(name);
        }
    }

    @Requires(property = "spec.name", value = "CookieParamLocatorResponseTest")
    static class ResponseLocatorMiddle {
        private final String name;

        ResponseLocatorMiddle() {
            this.name = null;
        }

        ResponseLocatorMiddle(String name) {
            this.name = name;
        }

        @POST
        public jakarta.ws.rs.core.Response post() {
            return jakarta.ws.rs.core.Response.ok(name)
                .cookie(new jakarta.ws.rs.core.NewCookie.Builder("echo").value(name).build())
                .build();
        }
    }

    @Singleton
    @Requires(property = "spec.name", value = "CookieParamTest")
    public static class ConvertedCookieParamConverterProvider implements ParamConverterProvider {

        @Override
        @SuppressWarnings("unchecked")
        public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
            if (rawType == ConvertedCookieParam.class) {
                return (ParamConverter<T>) new ParamConverter<ConvertedCookieParam>() {
                    @Override
                    public ConvertedCookieParam fromString(String value) {
                        return new ConvertedCookieParam(value);
                    }

                    @Override
                    public String toString(ConvertedCookieParam value) {
                        return value.value;
                    }
                };
            }
            return null;
        }
    }

    static final class ConvertedCookieParam implements Comparable<ConvertedCookieParam> {
        private final String value;

        private ConvertedCookieParam(String value) {
            if ("throw".equals(value)) {
                throw new IllegalArgumentException("Invalid cookie parameter value");
            }
            this.value = value;
        }

        @Override
        public int compareTo(ConvertedCookieParam other) {
            return value.compareTo(other.value);
        }
    }
}
