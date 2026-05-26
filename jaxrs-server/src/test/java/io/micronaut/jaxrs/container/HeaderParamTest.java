package io.micronaut.jaxrs.container;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.bind.ArgumentBinder;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.bind.RequestBinderRegistry;
import io.micronaut.http.bind.binders.RequestArgumentBinder;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
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

class HeaderParamTest {

    @Test
    void customParamConverterDoesNotConvertAbsentReferenceParam() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "HeaderParamTest"))) {
            RequestArgumentBinder<ConvertedHeaderParam> binder = customParamBinder(context);
            Argument<ConvertedHeaderParam> argument = customArgument(context);
            HttpRequest<?> request = HttpRequest.GET("/api/header-param/custom");

            assertTrue(binder.bind(ConversionContext.of(argument), request).getValue().isEmpty());
        }
    }

    @Test
    void customParamConverterBindsPresentReferenceParam() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "HeaderParamTest"))) {
            RequestArgumentBinder<ConvertedHeaderParam> binder = customParamBinder(context);
            Argument<ConvertedHeaderParam> argument = customArgument(context);
            HttpRequest<?> request = HttpRequest.GET("/api/header-param/custom")
                .header("X-Custom", "blue");

            assertEquals("blue", binder.bind(ConversionContext.of(argument), request).getValue().orElseThrow().value);
        }
    }

    @Test
    void customParamConverterBindsPresentSortedSetElements() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "HeaderParamTest"))) {
            HeaderParamArgumentBinder<SortedSet<ConvertedHeaderParam>> binder = new HeaderParamArgumentBinder<>(
                context.getBean(ConversionService.class),
                List.of(new ConvertedHeaderParamConverterProvider())
            );
            Argument<SortedSet<ConvertedHeaderParam>> argument = customSortedSetArgument(context);
            HttpRequest<?> request = HttpRequest.GET("/api/header-param/custom-sorted-set")
                .header("X-Custom", "blue");

            SortedSet<ConvertedHeaderParam> result = binder.createSpecific(argument)
                .bind(ConversionContext.of(argument), request)
                .getValue()
                .orElseThrow();

            assertEquals("blue", result.first().value);
        }
    }

    @Test
    void customParamConverterRuntimeFailureIsBadRequest() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "HeaderParamTest"))) {
            RequestArgumentBinder<ConvertedHeaderParam> binder = customParamBinder(context);
            Argument<ConvertedHeaderParam> argument = customArgument(context);
            HttpRequest<?> request = HttpRequest.GET("/api/header-param/custom")
                .header("X-Custom", "throw");

            assertThrows(BadRequestException.class, () -> binder.bind(ConversionContext.of(argument), request));
        }
    }

    @Test
    void scalarHeaderParamUsesFirstValue() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "HeaderParamTest"))) {
            BeanDefinition<TestController> definition = context.getBeanDefinition(TestController.class);
            ExecutableMethod<TestController, Object> method = definition.getRequiredMethod("number", int.class);
            @SuppressWarnings("unchecked")
            Argument<Integer> argument = (Argument<Integer>) method.getArguments()[0];
            @SuppressWarnings("unchecked")
            ArgumentBinder<Integer, HttpRequest<?>> binder = (ArgumentBinder<Integer, HttpRequest<?>>) context.getBean(RequestBinderRegistry.class)
                .findArgumentBinder(argument)
                .orElseThrow();
            MutableHttpRequest<?> request = HttpRequest.GET("/api/header-param/number");
            request.header("X-Number", "1");
            request.header("X-Number", "2");

            Integer result = binder.bind(ConversionContext.of(argument), request)
                .getValue()
                .orElseThrow();

            assertEquals(1, result);
        }
    }

    @SuppressWarnings("unchecked")
    private Argument<ConvertedHeaderParam> customArgument(ApplicationContext context) {
        BeanDefinition<TestController> definition = context.getBeanDefinition(TestController.class);
        ExecutableMethod<TestController, Object> method = definition.getRequiredMethod("custom", ConvertedHeaderParam.class);
        return (Argument<ConvertedHeaderParam>) method.getArguments()[0];
    }

    @SuppressWarnings("unchecked")
    private Argument<SortedSet<ConvertedHeaderParam>> customSortedSetArgument(ApplicationContext context) {
        BeanDefinition<TestController> definition = context.getBeanDefinition(TestController.class);
        ExecutableMethod<TestController, Object> method = definition.getRequiredMethod("customSortedSet", SortedSet.class);
        return (Argument<SortedSet<ConvertedHeaderParam>>) method.getArguments()[0];
    }

    private RequestArgumentBinder<ConvertedHeaderParam> customParamBinder(ApplicationContext context) {
        HeaderParamArgumentBinder<ConvertedHeaderParam> binder = new HeaderParamArgumentBinder<>(
            context.getBean(ConversionService.class),
            List.of(new ConvertedHeaderParamConverterProvider())
        );
        return binder.createSpecific(customArgument(context));
    }

    @Requires(property = "spec.name", value = "HeaderParamTest")
    @Path("/header-param")
    static class TestController {

        @GET
        @Path("/custom")
        public String custom(@HeaderParam("X-Custom") ConvertedHeaderParam custom) {
            return custom == null ? "null" : custom.value;
        }

        @GET
        @Path("/custom-sorted-set")
        public String customSortedSet(@DefaultValue("default") @HeaderParam("X-Custom") SortedSet<ConvertedHeaderParam> custom) {
            return custom == null || custom.isEmpty() ? "null" : custom.first().value;
        }

        @GET
        @Path("/number")
        public String number(@HeaderParam("X-Number") int number) {
            return String.valueOf(number);
        }
    }

    @Singleton
    @Requires(property = "spec.name", value = "HeaderParamTest")
    public static class ConvertedHeaderParamConverterProvider implements ParamConverterProvider {

        @Override
        @SuppressWarnings("unchecked")
        public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
            if (rawType == ConvertedHeaderParam.class) {
                return (ParamConverter<T>) new ParamConverter<ConvertedHeaderParam>() {
                    @Override
                    public ConvertedHeaderParam fromString(String value) {
                        return new ConvertedHeaderParam(value);
                    }

                    @Override
                    public String toString(ConvertedHeaderParam value) {
                        return value.value;
                    }
                };
            }
            return null;
        }
    }

    static final class ConvertedHeaderParam implements Comparable<ConvertedHeaderParam> {
        private final String value;

        private ConvertedHeaderParam(String value) {
            if ("throw".equals(value)) {
                throw new IllegalArgumentException("Invalid header parameter value");
            }
            this.value = value;
        }

        @Override
        public int compareTo(ConvertedHeaderParam other) {
            return value.compareTo(other.value);
        }
    }
}
