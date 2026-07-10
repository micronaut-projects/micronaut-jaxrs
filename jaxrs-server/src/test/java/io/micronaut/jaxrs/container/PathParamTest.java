package io.micronaut.jaxrs.container;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.bind.ArgumentBinder;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.http.BasicHttpAttributes;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.bind.RequestBinderRegistry;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathParamTest {

    @Test
    void bindsRepeatedPathParameters() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "PathParamTest"))) {
            BeanDefinition<TestController> definition = context.getBeanDefinition(TestController.class);
            ExecutableMethod<TestController, Object> method = definition.getRequiredMethod("repeated", List.class);
            @SuppressWarnings("unchecked")
            Argument<List<String>> argument = (Argument<List<String>>) method.getArguments()[0];

            List<String> result = bind(context, argument, pathRequest("/api/path-param/{id}/{id}/{id}", "/api/path-param/a/b/c"));

            assertEquals(List.of("a", "b", "c"), result);
        }
    }

    @Test
    void bindsPathSegmentWithMatrixParameters() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "PathParamTest"))) {
            BeanDefinition<TestController> definition = context.getBeanDefinition(TestController.class);
            ExecutableMethod<TestController, Object> method = definition.getRequiredMethod("segment", PathSegment.class);
            @SuppressWarnings("unchecked")
            Argument<PathSegment> argument = (Argument<PathSegment>) method.getArguments()[0];

            PathSegment result = bind(context, argument, pathRequest("/api/path-param/segment/{id}", "/api/path-param/segment/a;enabled=true"));

            assertEquals("a", result.getPath());
            assertEquals("true", result.getMatrixParameters().getFirst("enabled"));
        }
    }

    @Test
    void bindsPathSegmentWithMatrixAwareRouteTemplate() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "PathParamTest"))) {
            BeanDefinition<TestController> definition = context.getBeanDefinition(TestController.class);
            ExecutableMethod<TestController, Object> method = definition.getRequiredMethod("segment", PathSegment.class);
            @SuppressWarnings("unchecked")
            Argument<PathSegment> argument = (Argument<PathSegment>) method.getArguments()[0];

            PathSegment result = bind(
                context,
                argument,
                pathRequest("/api/path-param/segment{id:;[^/]*|}/{id}{id:;[^/]*|}", "/api/path-param/segment/a;enabled=true")
            );

            assertEquals("a", result.getPath());
            assertEquals("true", result.getMatrixParameters().getFirst("enabled"));
        }
    }

    @Test
    void bindsPathSegmentFromCombinedRouteTemplateVariables() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "PathParamTest"))) {
            BeanDefinition<SubResource> definition = context.getBeanDefinition(SubResource.class);
            ExecutableMethod<SubResource, Object> method = definition.getRequiredMethod("two", String.class, PathSegment.class);
            @SuppressWarnings("unchecked")
            Argument<PathSegment> argument = (Argument<PathSegment>) method.getArguments()[1];

            PathSegment result = bind(
                context,
                argument,
                pathRequest("/api/subresource-path-param{id1:;[^/]*|}/subresource{id1:;[^/]*|}/{id,id1:;[^/]*|}/{id1,id1:;[^/]*|}", "/api/subresource-path-param/subresource/a/b")
            );

            assertEquals("b", result.getPath());
        }
    }

    @Test
    void routesInheritedSubresourcePathParams() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of("spec.name", "PathParamTest"));
             HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            BeanDefinition<SubResource> definition = server.getApplicationContext().getBeanDefinition(SubResource.class);
            ExecutableMethod<SubResource, Object> method = definition.getRequiredMethod("two", String.class, PathSegment.class);

            assertEquals(PathParam.class, method.getArguments()[1].getAnnotationMetadata().getAnnotationTypeByStereotype(Bindable.class).orElseThrow());
            assertEquals("id1", method.getArguments()[1].getAnnotationMetadata().stringValue(Bindable.class).orElseThrow());
            assertEquals("id1", method.getArguments()[1].getAnnotationMetadata().stringValue(PathVariable.class).orElseThrow());
            assertEquals(
                PathParamArgumentBinder.class,
                server.getApplicationContext().getBean(RequestBinderRegistry.class).findArgumentBinder(method.getArguments()[1]).orElseThrow().getClass()
            );

            assertEquals("double=ab", client.toBlocking().retrieve("/api/subresource-path-param/subresource/a/b"));
            assertEquals("list=abc", client.toBlocking().retrieve("/api/subresource-path-param/subresource/a/b/c"));
            assertEquals("matrix=/a;enabled=true", client.toBlocking().retrieve("/api/subresource-path-param/subresource/matrix/a;enabled=true"));
        }
    }

    @Test
    void locatorPrefersDeclaredTargetMethodOverInheritedResourceMethod() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of("spec.name", "PathParamTest"));
             HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {

            assertEquals(
                "single=blue",
                client.toBlocking().retrieve(HttpRequest.POST("/api/path-param-locator/locator/blue", "").accept(MediaType.of("text/html")))
            );
            assertEquals("double=bluegreen", client.toBlocking().retrieve(HttpRequest.POST("/api/path-param-locator/locator/blue/green", "")));
            assertEquals(
                "list=abcdef",
                client.toBlocking().retrieve(HttpRequest.POST("/api/path-param-locator/locator/a/b/c/d/e/f", "").accept(MediaType.TEXT_PLAIN_TYPE))
            );
            assertEquals("double=bluegreen", client.toBlocking().retrieve(HttpRequest.POST("/api/path-param-locator/locatorencoded/blue/green", "")));
        }
    }

    @Test
    void encodedPathParamUsesRawValue() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "PathParamTest"))) {
            BeanDefinition<TestController> definition = context.getBeanDefinition(TestController.class);
            ExecutableMethod<TestController, Object> method = definition.getRequiredMethod("encoded", String.class);
            @SuppressWarnings("unchecked")
            Argument<String> argument = (Argument<String>) method.getArguments()[0];

            String result = bind(context, argument, pathRequest("/api/path-param/encoded/{id}", "/api/path-param/encoded/a%3Db"));

            assertEquals("a%3Db", result);
        }
    }

    @Test
    void customParamConverterBindsSortedSetElements() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "PathParamTest"))) {
            PathParamArgumentBinder<SortedSet<ConvertedPathParam>> binder = new PathParamArgumentBinder<>(
                context.getBean(ConversionService.class),
                List.of(new ConvertedPathParamConverterProvider())
            );
            BeanDefinition<TestController> definition = context.getBeanDefinition(TestController.class);
            ExecutableMethod<TestController, Object> method = definition.getRequiredMethod("custom", SortedSet.class);
            @SuppressWarnings("unchecked")
            Argument<SortedSet<ConvertedPathParam>> argument = (Argument<SortedSet<ConvertedPathParam>>) method.getArguments()[0];

            SortedSet<ConvertedPathParam> result = binder.createSpecific(argument)
                .bind(ConversionContext.of(argument), pathRequest("/api/path-param/custom/{id}/{id}", "/api/path-param/custom/blue/green"))
                .getValue()
                .orElseThrow();

            assertEquals("blue", result.first().value);
        }
    }

    private static <T> T bind(ApplicationContext context, Argument<T> argument, HttpRequest<?> request) {
        @SuppressWarnings("unchecked")
        ArgumentBinder<T, HttpRequest<?>> binder = (ArgumentBinder<T, HttpRequest<?>>) context.getBean(RequestBinderRegistry.class)
            .findArgumentBinder(argument)
            .orElseThrow();
        return binder.bind(ConversionContext.of(argument), request).getValue().orElseThrow();
    }

    private static HttpRequest<?> pathRequest(String template, String path) {
        HttpRequest<?> request = HttpRequest.GET(path);
        BasicHttpAttributes.setUriTemplate(request, template);
        return request;
    }

    @Requires(property = "spec.name", value = "PathParamTest")
    @Path("/path-param")
    static class TestController {

        @GET
        @Path("/{id}/{id}/{id}")
        public String repeated(@PathParam("id") List<String> id) {
            return String.join("", id);
        }

        @GET
        @Path("/segment/{id}")
        public String segment(@PathParam("id") PathSegment id) {
            return id.getPath();
        }

        @GET
        @Path("/encoded/{id}")
        public String encoded(@Encoded @PathParam("id") String id) {
            return id;
        }

        @GET
        @Path("/custom/{id}/{id}")
        public String custom(@PathParam("id") SortedSet<ConvertedPathParam> id) {
            return id.first().value;
        }
    }

    @Requires(property = "spec.name", value = "PathParamTest")
    static class BaseSubResource {

        @GET
        @Path("/{id}/{id1}")
        public String two(@PathParam("id") String id, @PathParam("id1") PathSegment id1) {
            return "double=" + id + id1.getPath();
        }

        @GET
        @Path("/{id}/{id}/{id}")
        public String list(@PathParam("id") List<String> id) {
            return "list=" + String.join("", id);
        }

        @GET
        @Path("/matrix/{id}")
        public String matrix(@PathParam("id") PathSegment id) {
            return "matrix=/" + id.getPath() + ";enabled=" + id.getMatrixParameters().getFirst("enabled");
        }
    }

    @Requires(property = "spec.name", value = "PathParamTest")
    @Path("/subresource-path-param")
    static class SubResource extends BaseSubResource {

        @Path("subresource")
        SubResource subresource() {
            return this;
        }
    }

    @Path("/PathParamTest")
    static class LocatorBaseResource {

        @GET
        @Path("/{id}")
        public String inherited(@PathParam("id") String id) {
            return "inherited=" + id;
        }
    }

    static class LocatorMiddleResource extends LocatorBaseResource {
        private final String value;

        LocatorMiddleResource() {
            this.value = null;
        }

        LocatorMiddleResource(String value) {
            this.value = "single=" + value;
        }

        LocatorMiddleResource(String first, String second) {
            this.value = "double=" + first + second;
        }

        LocatorMiddleResource(String first, String second, String third, String fourth, String fifth, String sixth) {
            this.value = "list=" + first + second + third + fourth + fifth + sixth;
        }

        @POST
        public String returnValue() {
            return value;
        }
    }

    @Requires(property = "spec.name", value = "PathParamTest")
    @Path("/path-param-locator")
    static class PathParamLocatorResource extends LocatorMiddleResource {

        @Path("locator/{id1}")
        LocatorMiddleResource locator(@PathParam("id1") String id1) {
            return new LocatorMiddleResource(id1);
        }

        @Path("locator/{id1}/{id2}")
        LocatorMiddleResource locator(@PathParam("id1") String id1, @PathParam("id2") String id2) {
            return new LocatorMiddleResource(id1, id2);
        }

        @Path("locatorencoded/{id1}/{id2}")
        LocatorMiddleResource locatorEncoded(@PathParam("id1") String id1, @Encoded @PathParam("id2") String id2) {
            return new LocatorMiddleResource(id1, id2);
        }

        @Path("locator/{id1}/{id2}/{id3}/{id4}/{id5}/{id6}")
        LocatorMiddleResource locator(@PathParam("id1") String id1,
                                      @PathParam("id2") String id2,
                                      @PathParam("id3") String id3,
                                      @PathParam("id4") String id4,
                                      @PathParam("id5") String id5,
                                      @PathParam("id6") String id6) {
            return new LocatorMiddleResource(id1, id2, id3, id4, id5, id6);
        }
    }

    @Singleton
    @Requires(property = "spec.name", value = "PathParamTest")
    public static class ConvertedPathParamConverterProvider implements ParamConverterProvider {

        @Override
        @SuppressWarnings("unchecked")
        public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
            if (rawType == ConvertedPathParam.class) {
                return (ParamConverter<T>) new ParamConverter<ConvertedPathParam>() {
                    @Override
                    public ConvertedPathParam fromString(String value) {
                        return new ConvertedPathParam(value);
                    }

                    @Override
                    public String toString(ConvertedPathParam value) {
                        return value.value;
                    }
                };
            }
            return null;
        }
    }

    public record ConvertedPathParam(String value) implements Comparable<ConvertedPathParam> {
        @Override
        public int compareTo(ConvertedPathParam other) {
            return value.compareTo(other.value);
        }
    }
}
