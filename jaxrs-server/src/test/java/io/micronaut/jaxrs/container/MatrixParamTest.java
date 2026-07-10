package io.micronaut.jaxrs.container;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.bind.ArgumentBinder;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.bind.RequestBinderRegistry;
import io.micronaut.http.bind.binders.RequestArgumentBinder;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
@io.micronaut.context.annotation.Property(name = "spec.name", value = "MatrixParamTest")
class MatrixParamTest {

    @Inject
    @Client("/api/matrix-param")
    HttpClient client;

    @Inject
    @Client("/api/matrix-param-locator")
    HttpClient locatorClient;

    @Inject
    @Client("/api/matrix-writer-locator")
    HttpClient writerLocatorClient;

    @Inject
    @Client("/api")
    HttpClient apiClient;

    @Test
    void routesMatrixParameterRequests() {
        String response = client.toBlocking().retrieve("/colors;color=red;color=green/ids;id=1;id=2", String.class);

        assertEquals("red,green -> 1,2", response);
    }

    @Test
    void routesClassLevelMatrixParameterRequests() {
        String response = apiClient.toBlocking().retrieve("/matrix-param-root;root=blue", String.class);

        assertEquals("blue", response);
    }

    @Test
    void routesEncodedMatrixParameterRequests() {
        String response = client.toBlocking().retrieve("/encoded;encoded=red%20blue", String.class);

        assertEquals("red blue -> red%20blue", response);
    }

    @Test
    void routesCustomSortedSetMatrixParameterRequests() {
        String response = client.toBlocking().retrieve("/custom-sorted-set;custom=blue", String.class);

        assertEquals("blue", response);
    }

    @Test
    void routesMatrixParameterSubresourceLocatorRequests() {
        String response = locatorClient.toBlocking().retrieve(HttpRequest.POST("/locator;doubletest1=123", ""), String.class);

        assertEquals("doubletest1=123.0", response);
    }

    @Test
    void writesSubresourceLocatorResultWithJaxRsWriter() {
        String response = writerLocatorClient.toBlocking().retrieve(
            HttpRequest.POST("/sub;resmatrix=resarg;submatrix=subarg;entity=entityarg", ""),
            String.class
        );

        assertEquals("resMatrix=resarg;subMatrix=null;entity=null", response);
    }

    @Test
    void bindsMatrixParametersFromPathSegments() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "MatrixParamTest"))) {
            BeanDefinition<TestController> definition = context.getBeanDefinition(TestController.class);
            ExecutableMethod<TestController, Object> method = definition.getRequiredMethod("colors", List.class, Long[].class);
            HttpRequest<?> request = HttpRequest.GET("/api/matrix-param/colors;color=red;color=green/ids;id=1;id=2");
            RequestBinderRegistry registry = context.getBean(RequestBinderRegistry.class);

            @SuppressWarnings("unchecked")
            Argument<List<String>> colorsArgument = (Argument<List<String>>) method.getArguments()[0];
            @SuppressWarnings("unchecked")
            Argument<Long[]> idsArgument = (Argument<Long[]>) method.getArguments()[1];

            assertEquals(List.of("red", "green"), bind(registry, colorsArgument, request));
            assertArrayEquals(new Long[] {1L, 2L}, bind(registry, idsArgument, request));
        }
    }

    @Test
    void bindsEncodedMatrixParametersFromPathSegments() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "MatrixParamTest"))) {
            BeanDefinition<TestController> definition = context.getBeanDefinition(TestController.class);
            ExecutableMethod<TestController, Object> method = definition.getRequiredMethod("encoded", String.class, String.class);
            HttpRequest<?> request = HttpRequest.GET("/api/matrix-param/encoded;encoded=red%20blue");
            RequestBinderRegistry registry = context.getBean(RequestBinderRegistry.class);

            @SuppressWarnings("unchecked")
            Argument<String> decodedArgument = (Argument<String>) method.getArguments()[0];
            @SuppressWarnings("unchecked")
            Argument<String> encodedArgument = (Argument<String>) method.getArguments()[1];

            assertEquals("red blue", bind(registry, decodedArgument, request));
            assertEquals("red%20blue", bind(registry, encodedArgument, request));
        }
    }

    @Test
    void customParamConverterDoesNotConvertAbsentReferenceParam() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "MatrixParamTest"))) {
            RequestArgumentBinder<ConvertedMatrixParam> binder = customParamBinder(context);
            Argument<ConvertedMatrixParam> argument = customArgument(context);
            HttpRequest<?> request = HttpRequest.GET("/api/matrix-param/custom");

            assertTrue(binder.bind(ConversionContext.of(argument), request).getValue().isEmpty());
        }
    }

    @Test
    void emptyMatrixParameterTokensAreIgnored() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "MatrixParamTest"))) {
            RequestArgumentBinder<ConvertedMatrixParam> binder = customParamBinder(context);
            Argument<ConvertedMatrixParam> argument = customArgument(context);
            HttpRequest<?> request = HttpRequest.GET("/api/matrix-param/custom;;custom=blue");

            assertEquals("blue", binder.bind(ConversionContext.of(argument), request).getValue().orElseThrow().value);
        }
    }

    @Test
    void customParamConverterBindsPresentReferenceParam() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "MatrixParamTest"))) {
            RequestArgumentBinder<ConvertedMatrixParam> binder = customParamBinder(context);
            Argument<ConvertedMatrixParam> argument = customArgument(context);
            HttpRequest<?> request = HttpRequest.GET("/api/matrix-param/custom;custom=blue");

            assertEquals("blue", binder.bind(ConversionContext.of(argument), request).getValue().orElseThrow().value);
        }
    }

    @Test
    void customParamConverterBindsPresentSortedSetElements() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "MatrixParamTest"))) {
            MatrixParamArgumentBinder<SortedSet<ConvertedMatrixParam>> binder = new MatrixParamArgumentBinder<>(
                context.getBean(ConversionService.class),
                List.of(new ConvertedMatrixParamConverterProvider())
            );
            Argument<SortedSet<ConvertedMatrixParam>> argument = customSortedSetArgument(context);
            HttpRequest<?> request = HttpRequest.GET("/api/matrix-param/custom;custom=blue");

            SortedSet<ConvertedMatrixParam> result = binder.createSpecific(argument)
                .bind(ConversionContext.of(argument), request)
                .getValue()
                .orElseThrow();

            assertEquals("blue", result.first().value);
        }
    }

    @SuppressWarnings("unchecked")
    private Argument<ConvertedMatrixParam> customArgument(ApplicationContext context) {
        BeanDefinition<TestController> definition = context.getBeanDefinition(TestController.class);
        ExecutableMethod<TestController, Object> method = definition.getRequiredMethod("custom", ConvertedMatrixParam.class);
        return (Argument<ConvertedMatrixParam>) method.getArguments()[0];
    }

    @SuppressWarnings("unchecked")
    private Argument<SortedSet<ConvertedMatrixParam>> customSortedSetArgument(ApplicationContext context) {
        BeanDefinition<TestController> definition = context.getBeanDefinition(TestController.class);
        ExecutableMethod<TestController, Object> method = definition.getRequiredMethod("customSortedSet", String.class, SortedSet.class);
        return (Argument<SortedSet<ConvertedMatrixParam>>) method.getArguments()[1];
    }

    private RequestArgumentBinder<ConvertedMatrixParam> customParamBinder(ApplicationContext context) {
        MatrixParamArgumentBinder<ConvertedMatrixParam> binder = new MatrixParamArgumentBinder<>(
            context.getBean(ConversionService.class),
            List.of(new ConvertedMatrixParamConverterProvider())
        );
        return binder.createSpecific(customArgument(context));
    }

    private <T> T bind(RequestBinderRegistry registry, Argument<T> argument, HttpRequest<?> request) {
        ArgumentBinder<T, HttpRequest<?>> binder = registry.findArgumentBinder(argument).orElseThrow();
        return binder.bind(ConversionContext.of(argument), request).getValue().orElseThrow();
    }

    @Requires(property = "spec.name", value = "MatrixParamTest")
    @Path("/matrix-param")
    static class TestController {

        @GET
        @Path("/colors/ids")
        @Produces("text/plain")
        public String colors(@MatrixParam("color") List<String> colors, @MatrixParam("id") Long[] ids) {
            return String.join(",", colors) + " -> " + Arrays.stream(ids).map(String::valueOf).collect(joining(","));
        }

        @GET
        @Path("/encoded")
        @Produces("text/plain")
        public String encoded(@MatrixParam("encoded") String decoded, @Encoded @MatrixParam("encoded") String encodedValue) {
            return decoded + " -> " + encodedValue;
        }

        @GET
        @Path("/custom")
        @Produces("text/plain")
        public String custom(@MatrixParam("custom") ConvertedMatrixParam custom) {
            return custom == null ? "null" : custom.value;
        }

        @GET
        @Path("/custom-sorted-set")
        @Produces("text/plain")
        public String customSortedSet(@Nullable @MatrixParam("routing") String ignored, @DefaultValue("default") @MatrixParam("custom") SortedSet<ConvertedMatrixParam> custom) {
            return custom == null || custom.isEmpty() ? "null" : custom.first().value;
        }
    }

    @Requires(property = "spec.name", value = "MatrixParamTest")
    @Path("/matrix-param-root")
    static class RootMatrixController {

        @GET
        @Produces("text/plain")
        public String root(@MatrixParam("root") String root) {
            return root;
        }
    }

    @Requires(property = "spec.name", value = "MatrixParamTest")
    @Path("/matrix-param-locator")
    static class MatrixLocatorRoot {

        @Path("/locator")
        public MatrixLocatorMiddle locator(@MatrixParam("doubletest1") double doubleValue) {
            return new MatrixLocatorMiddle(doubleValue);
        }
    }

    @Requires(property = "spec.name", value = "MatrixParamTest")
    static class MatrixLocatorMiddle {
        private final double doubleValue;

        MatrixLocatorMiddle() {
            this.doubleValue = 0;
        }

        MatrixLocatorMiddle(double doubleValue) {
            this.doubleValue = doubleValue;
        }

        @POST
        @Produces("text/plain")
        public String returnValue() {
            return "doubletest1=" + doubleValue;
        }
    }

    @Requires(property = "spec.name", value = "MatrixParamTest")
    @Path("/matrix-writer-locator")
    static class MatrixWriterLocatorRoot {

        @Path("/sub")
        public MatrixWriterLocatorSub locator(@MatrixParam("resmatrix") String matrixValue) {
            return new MatrixWriterLocatorSub(matrixValue);
        }
    }

    @Requires(property = "spec.name", value = "MatrixParamTest")
    static class MatrixWriterLocatorSub {
        private final String resmatrix;

        @MatrixParam("submatrix")
        private String submatrix;

        MatrixWriterLocatorSub() {
            this(null);
        }

        MatrixWriterLocatorSub(String resmatrix) {
            this.resmatrix = resmatrix;
        }

        @POST
        @Produces("text/plain")
        public MatrixWriterLocatorEntity entity() {
            return new MatrixWriterLocatorEntity(resmatrix, submatrix);
        }
    }

    static class MatrixWriterLocatorEntity {
        final String resMatrix;
        final String subMatrix;

        @MatrixParam("entity")
        public String entity;

        MatrixWriterLocatorEntity(String resMatrix, String subMatrix) {
            this.resMatrix = resMatrix;
            this.subMatrix = subMatrix;
        }
    }

    @Provider
    @Produces("text/plain")
    @Requires(property = "spec.name", value = "MatrixParamTest")
    public static class MatrixWriterLocatorEntityWriter implements MessageBodyWriter<MatrixWriterLocatorEntity> {

        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return MatrixWriterLocatorEntity.class.isAssignableFrom(type);
        }

        @Override
        public void writeTo(MatrixWriterLocatorEntity entity,
                            Class<?> type,
                            Type genericType,
                            Annotation[] annotations,
                            MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders,
                            OutputStream entityStream) throws IOException {
            entityStream.write(("resMatrix=" + entity.resMatrix +
                ";subMatrix=" + entity.subMatrix +
                ";entity=" + entity.entity).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Singleton
    @Requires(property = "spec.name", value = "MatrixParamTest")
    public static class ConvertedMatrixParamConverterProvider implements ParamConverterProvider {

        @Override
        @SuppressWarnings("unchecked")
        public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
            if (rawType == ConvertedMatrixParam.class) {
                return (ParamConverter<T>) new ParamConverter<ConvertedMatrixParam>() {
                    @Override
                    public ConvertedMatrixParam fromString(String value) {
                        return new ConvertedMatrixParam(value);
                    }

                    @Override
                    public String toString(ConvertedMatrixParam value) {
                        return value.value;
                    }
                };
            }
            return null;
        }
    }

    static final class ConvertedMatrixParam implements Comparable<ConvertedMatrixParam> {
        private final String value;

        private ConvertedMatrixParam(String value) {
            if (value == null) {
                throw new IllegalArgumentException("Missing matrix parameter value");
            }
            this.value = value;
        }

        @Override
        public int compareTo(ConvertedMatrixParam other) {
            return value.compareTo(other.value);
        }
    }
}
