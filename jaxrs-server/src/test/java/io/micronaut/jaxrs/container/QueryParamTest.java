package io.micronaut.jaxrs.container;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.bind.ArgumentBinder;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.RequestBinderRegistry;
import io.micronaut.http.bind.binders.RequestArgumentBinder;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import jakarta.inject.Singleton;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
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

class QueryParamTest {

    @Test
    void customParamConverterDoesNotConvertAbsentReferenceParam() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "QueryParamTest"))) {
            RequestArgumentBinder<ConvertedQueryParam> binder = customParamBinder(context);
            Argument<ConvertedQueryParam> argument = customArgument(context);
            HttpRequest<?> request = HttpRequest.GET("/api/query-param/custom");

            assertTrue(binder.bind(ConversionContext.of(argument), request).getValue().isEmpty());
        }
    }

    @Test
    void customParamConverterBindsPresentReferenceParam() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "QueryParamTest"))) {
            RequestArgumentBinder<ConvertedQueryParam> binder = customParamBinder(context);
            Argument<ConvertedQueryParam> argument = customArgument(context);
            HttpRequest<?> request = HttpRequest.GET("/api/query-param/custom?custom=blue");

            assertEquals("blue", binder.bind(ConversionContext.of(argument), request).getValue().orElseThrow().value);
        }
    }

    @Test
    void customParamConverterBindsPresentSortedSetElements() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "QueryParamTest"))) {
            QueryParamArgumentBinder<SortedSet<ConvertedQueryParam>> binder = new QueryParamArgumentBinder<>(
                context.getBean(ConversionService.class),
                List.of(new ConvertedQueryParamConverterProvider())
            );
            Argument<SortedSet<ConvertedQueryParam>> argument = customSortedSetArgument(context);
            HttpRequest<?> request = HttpRequest.GET("/api/query-param/custom-sorted-set?custom=blue");

            SortedSet<ConvertedQueryParam> result = binder.createSpecific(argument)
                .bind(ConversionContext.of(argument), request)
                .getValue()
                .orElseThrow();

            assertEquals("blue", result.first().value);
        }
    }

    @Test
    void customParamConverterRuntimeFailureIsNotFound() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "QueryParamTest"))) {
            RequestArgumentBinder<ConvertedQueryParam> binder = customParamBinder(context);
            Argument<ConvertedQueryParam> argument = customArgument(context);
            HttpRequest<?> request = HttpRequest.GET("/api/query-param/custom?custom=throw");

            assertThrows(NotFoundException.class, () -> binder.bind(ConversionContext.of(argument), request));
        }
    }

    @Test
    void scalarQueryParamUsesFirstValue() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "QueryParamTest"))) {
            BeanDefinition<TestController> definition = context.getBeanDefinition(TestController.class);
            ExecutableMethod<TestController, Object> method = definition.getRequiredMethod("number", int.class);
            @SuppressWarnings("unchecked")
            Argument<Integer> argument = (Argument<Integer>) method.getArguments()[0];
            @SuppressWarnings("unchecked")
            ArgumentBinder<Integer, HttpRequest<?>> binder = (ArgumentBinder<Integer, HttpRequest<?>>) context.getBean(RequestBinderRegistry.class)
                .findArgumentBinder(argument)
                .orElseThrow();

            Integer result = binder.bind(ConversionContext.of(argument), HttpRequest.GET("/api/query-param/number?number=1&number=2"))
                .getValue()
                .orElseThrow();

            assertEquals(1, result);
        }
    }

    @Test
    void encodedQueryParamUsesRawValue() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "QueryParamTest"))) {
            BeanDefinition<TestController> definition = context.getBeanDefinition(TestController.class);
            ExecutableMethod<TestController, Object> method = definition.getRequiredMethod("encoded", String.class);
            @SuppressWarnings("unchecked")
            Argument<String> argument = (Argument<String>) method.getArguments()[0];
            @SuppressWarnings("unchecked")
            ArgumentBinder<String, HttpRequest<?>> binder = (ArgumentBinder<String, HttpRequest<?>>) context.getBean(RequestBinderRegistry.class)
                .findArgumentBinder(argument)
                .orElseThrow();

            String result = binder.bind(ConversionContext.of(argument), HttpRequest.GET("/api/query-param/encoded?encoded=red%20blue"))
                .getValue()
                .orElseThrow();

            assertEquals("red%20blue", result);
        }
    }

    @SuppressWarnings("unchecked")
    private Argument<ConvertedQueryParam> customArgument(ApplicationContext context) {
        BeanDefinition<TestController> definition = context.getBeanDefinition(TestController.class);
        ExecutableMethod<TestController, Object> method = definition.getRequiredMethod("custom", ConvertedQueryParam.class);
        return (Argument<ConvertedQueryParam>) method.getArguments()[0];
    }

    @SuppressWarnings("unchecked")
    private Argument<SortedSet<ConvertedQueryParam>> customSortedSetArgument(ApplicationContext context) {
        BeanDefinition<TestController> definition = context.getBeanDefinition(TestController.class);
        ExecutableMethod<TestController, Object> method = definition.getRequiredMethod("customSortedSet", SortedSet.class);
        return (Argument<SortedSet<ConvertedQueryParam>>) method.getArguments()[0];
    }

    private RequestArgumentBinder<ConvertedQueryParam> customParamBinder(ApplicationContext context) {
        QueryParamArgumentBinder<ConvertedQueryParam> binder = new QueryParamArgumentBinder<>(
            context.getBean(ConversionService.class),
            List.of(new ConvertedQueryParamConverterProvider())
        );
        return binder.createSpecific(customArgument(context));
    }

    @Requires(property = "spec.name", value = "QueryParamTest")
    @Path("/query-param")
    static class TestController {

        @GET
        @Path("/custom")
        public String custom(@QueryParam("custom") ConvertedQueryParam custom) {
            return custom == null ? "null" : custom.value;
        }

        @GET
        @Path("/custom-sorted-set")
        public String customSortedSet(@DefaultValue("default") @QueryParam("custom") SortedSet<ConvertedQueryParam> custom) {
            return custom == null || custom.isEmpty() ? "null" : custom.first().value;
        }

        @GET
        @Path("/number")
        public String number(@QueryParam("number") int number) {
            return String.valueOf(number);
        }

        @GET
        @Path("/encoded")
        public String encoded(@Encoded @QueryParam("encoded") String encoded) {
            return encoded;
        }
    }

    @Singleton
    @Requires(property = "spec.name", value = "QueryParamTest")
    public static class ConvertedQueryParamConverterProvider implements ParamConverterProvider {

        @Override
        @SuppressWarnings("unchecked")
        public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
            if (rawType == ConvertedQueryParam.class) {
                return (ParamConverter<T>) new ParamConverter<ConvertedQueryParam>() {
                    @Override
                    public ConvertedQueryParam fromString(String value) {
                        return new ConvertedQueryParam(value);
                    }

                    @Override
                    public String toString(ConvertedQueryParam value) {
                        return value.value;
                    }
                };
            }
            return null;
        }
    }

    static final class ConvertedQueryParam implements Comparable<ConvertedQueryParam> {
        private final String value;

        private ConvertedQueryParam(String value) {
            if ("throw".equals(value)) {
                throw new IllegalArgumentException("Invalid query parameter value");
            }
            this.value = value;
        }

        @Override
        public int compareTo(ConvertedQueryParam other) {
            return value.compareTo(other.value);
        }
    }
}
