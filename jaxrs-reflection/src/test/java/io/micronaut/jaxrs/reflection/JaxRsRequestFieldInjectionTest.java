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
package io.micronaut.jaxrs.reflection;

import io.micronaut.aop.Intercepted;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.bind.ArgumentBinder;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.http.BasicHttpAttributes;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.RequestBean;
import io.micronaut.http.bind.RequestBinderRegistry;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
@Property(name = "spec.name", value = "JaxRsRequestFieldInjectionTest")
class JaxRsRequestFieldInjectionTest {

    @Inject
    ApplicationContext context;

    @Inject
    RequestBinderRegistry binderRegistry;

    @Inject
    ConversionService conversionService;

    @Test
    void injectsMatrixParamFieldBeforeResourceMethodInvocation() {
        FieldResource resource = context.getBeansOfType(FieldResource.class)
            .stream()
            .filter(Intercepted.class::isInstance)
            .findFirst()
            .orElseThrow();
        String response = ServerRequestContext.with(HttpRequest.GET("/field-injection;color=blue"), (Supplier<String>) resource::get);

        assertEquals("blue", response);
    }

    @Test
    void injectsQueryParamFieldBeforeResourceMethodInvocation() {
        QueryFieldResource resource = context.getBeansOfType(QueryFieldResource.class)
            .stream()
            .filter(Intercepted.class::isInstance)
            .findFirst()
            .orElseThrow();
        String response = ServerRequestContext.with(HttpRequest.GET("/query-field?color=blue"), (Supplier<String>) resource::get);

        assertEquals("blue", response);
    }

    @Test
    void injectsHeaderParamFieldBeforeResourceMethodInvocation() {
        HeaderFieldResource resource = context.getBeansOfType(HeaderFieldResource.class)
            .stream()
            .filter(Intercepted.class::isInstance)
            .findFirst()
            .orElseThrow();
        String response = ServerRequestContext.with(HttpRequest.GET("/header-field").header("X-Color", "blue"), (Supplier<String>) resource::get);

        assertEquals("blue", response);
    }

    @Test
    void injectsCookieParamFieldBeforeResourceMethodInvocation() {
        CookieFieldResource resource = context.getBeansOfType(CookieFieldResource.class)
            .stream()
            .filter(Intercepted.class::isInstance)
            .findFirst()
            .orElseThrow();
        String response = ServerRequestContext.with(HttpRequest.GET("/cookie-field").cookie(Cookie.of("color", "blue")), (Supplier<String>) resource::get);

        assertEquals("blue", response);
    }

    @Test
    void injectsDefaultCookieParamFieldBeforeResourceMethodInvocation() {
        CookieFieldResource resource = context.getBeansOfType(CookieFieldResource.class)
            .stream()
            .filter(Intercepted.class::isInstance)
            .findFirst()
            .orElseThrow();
        String response = ServerRequestContext.with(HttpRequest.GET("/cookie-field"), (Supplier<String>) resource::get);

        assertEquals("green", response);
    }

    @Test
    void injectsPathParamFieldBeforeResourceMethodInvocation() {
        PathFieldResource resource = context.getBeansOfType(PathFieldResource.class)
            .stream()
            .filter(Intercepted.class::isInstance)
            .findFirst()
            .orElseThrow();
        String response = ServerRequestContext.with(pathRequest("/path-field/{color}", "/path-field/blue"), (Supplier<String>) resource::get);

        assertEquals("blue", response);
    }

    @Test
    void injectsDefaultPathParamFieldBeforeResourceMethodInvocation() {
        DefaultPathFieldResource resource = context.getBeansOfType(DefaultPathFieldResource.class)
            .stream()
            .filter(Intercepted.class::isInstance)
            .findFirst()
            .orElseThrow();
        String response = ServerRequestContext.with(pathRequest("/path-field-default/{color}", "/path-field-default/blue"), (Supplier<String>) resource::get);

        assertEquals("green", response);
    }

    @Test
    void requestBeanBinderUsesIntrospectionMetadata() {
        MutableAnnotationMetadata annotationMetadata = new MutableAnnotationMetadata();
        annotationMetadata.addAnnotation(RequestBean.class.getName(), Map.of());
        annotationMetadata.addStereotype(List.of(RequestBean.class.getName()), Bindable.class.getName(), Map.of());
        Argument<BeanParamRequest> argument = Argument.of(BeanParamRequest.class, "bean", annotationMetadata);

        @SuppressWarnings("unchecked")
        ArgumentBinder<BeanParamRequest, HttpRequest<?>> binder = (ArgumentBinder<BeanParamRequest, HttpRequest<?>>) binderRegistry
            .findArgumentBinder(argument)
            .orElseThrow();
        BeanParamRequest bean = binder.bind(ConversionContext.of(argument), HttpRequest.GET("/bean?jaxrs-color=blue"))
            .getValue()
            .orElseThrow();

        assertInstanceOf(JaxRsRequestBeanAnnotationBinder.class, binder);
        assertEquals("blue", bean.color);
    }

    @Test
    void requestBeanBinderDoesNotMassBindUnannotatedPropertiesByName() {
        MutableAnnotationMetadata annotationMetadata = new MutableAnnotationMetadata();
        annotationMetadata.addAnnotation(RequestBean.class.getName(), Map.of());
        annotationMetadata.addStereotype(List.of(RequestBean.class.getName()), Bindable.class.getName(), Map.of());
        Argument<BeanParamRequest> argument = Argument.of(BeanParamRequest.class, "bean", annotationMetadata);

        @SuppressWarnings("unchecked")
        ArgumentBinder<BeanParamRequest, HttpRequest<?>> binder = (ArgumentBinder<BeanParamRequest, HttpRequest<?>>) binderRegistry
            .findArgumentBinder(argument)
            .orElseThrow();
        BeanParamRequest bean = binder.bind(ConversionContext.of(argument), HttpRequest.GET("/bean?jaxrs-color=blue&unannotated=red"))
            .getValue()
            .orElseThrow();

        assertEquals("blue", bean.color);
        assertEquals("unchanged", bean.unannotated);
    }

    @Test
    void reflectionFieldInjectionIgnoresStaticAndFinalFields() {
        StaticFinalFieldResource resource = context.getBeansOfType(StaticFinalFieldResource.class)
            .stream()
            .filter(Intercepted.class::isInstance)
            .findFirst()
            .orElseThrow();
        String response = ServerRequestContext.with(HttpRequest.GET("/static-final?staticColor=blue&finalColor=red"), (Supplier<String>) resource::get);

        assertEquals("static:final", response);
    }

    @Test
    void discoveredParamConverterProviderWinsOverReflectionFallback() {
        BeanDefinition<QueryResource> definition = context.getBeanDefinition(QueryResource.class);
        ExecutableMethod<QueryResource, Object> method = definition.getRequiredMethod("converted", ConvertedQueryParam.class);

        @SuppressWarnings("unchecked")
        Argument<ConvertedQueryParam> argument = (Argument<ConvertedQueryParam>) method.getArguments()[0];
        @SuppressWarnings("unchecked")
        ArgumentBinder<ConvertedQueryParam, HttpRequest<?>> binder = (ArgumentBinder<ConvertedQueryParam, HttpRequest<?>>) binderRegistry.findArgumentBinder(argument).orElseThrow();

        ConvertedQueryParam converted = binder.bind(ConversionContext.of(argument), HttpRequest.GET("/query?value=blue"))
            .getValue()
            .orElseThrow();

        assertEquals("provider:blue", converted.value);
    }

    @Test
    void reflectionFallbackConvertsHeaderParamMethodArgument() {
        BeanDefinition<HeaderResource> definition = context.getBeanDefinition(HeaderResource.class);
        ExecutableMethod<HeaderResource, Object> method = definition.getRequiredMethod("converted", ConvertedHeaderParam.class);

        @SuppressWarnings("unchecked")
        Argument<ConvertedHeaderParam> argument = (Argument<ConvertedHeaderParam>) method.getArguments()[0];
        assertTrue(argument.getAnnotationMetadata().hasAnnotation(HeaderParam.class));
        assertTrue(argument.getAnnotationMetadata().hasStereotype(Bindable.class));
        assertEquals("X-Value", argument.getAnnotationMetadata().stringValue(HeaderParam.class).orElseThrow());
        assertTrue(context.getBeansOfType(ParamConverterProvider.class)
            .stream()
            .anyMatch(provider -> provider.getConverter(ConvertedHeaderParam.class, ConvertedHeaderParam.class, AnnotationMetadata.EMPTY_METADATA.synthesizeAll()) != null));
        @SuppressWarnings("unchecked")
        ArgumentBinder<ConvertedHeaderParam, HttpRequest<?>> binder = (ArgumentBinder<ConvertedHeaderParam, HttpRequest<?>>) binderRegistry.findArgumentBinder(argument).orElseThrow();

        ConvertedHeaderParam converted = binder.bind(ConversionContext.of(argument), HttpRequest.GET("/header").header("X-Value", "blue"))
            .getValue()
            .orElseThrow();

        assertEquals("reflection:blue", converted.value);
    }

    @Test
    void reflectionFallbackConvertsDefaultHeaderParamMethodArgument() {
        BeanDefinition<HeaderResource> definition = context.getBeanDefinition(HeaderResource.class);
        ExecutableMethod<HeaderResource, Object> method = definition.getRequiredMethod("convertedDefault", ConvertedHeaderParam.class);

        @SuppressWarnings("unchecked")
        Argument<ConvertedHeaderParam> argument = (Argument<ConvertedHeaderParam>) method.getArguments()[0];
        @SuppressWarnings("unchecked")
        ArgumentBinder<ConvertedHeaderParam, HttpRequest<?>> binder = (ArgumentBinder<ConvertedHeaderParam, HttpRequest<?>>) binderRegistry.findArgumentBinder(argument).orElseThrow();

        ConvertedHeaderParam converted = binder.bind(ConversionContext.of(argument), HttpRequest.GET("/header"))
            .getValue()
            .orElseThrow();

        assertEquals("reflection:green", converted.value);
    }

    @Test
    void reflectionFallbackConvertsHeaderParamMethodArgumentElements() {
        BeanDefinition<HeaderResource> definition = context.getBeanDefinition(HeaderResource.class);
        ExecutableMethod<HeaderResource, Object> method = definition.getRequiredMethod("convertedList", List.class);

        @SuppressWarnings("unchecked")
        Argument<List<ConvertedHeaderParam>> argument = (Argument<List<ConvertedHeaderParam>>) method.getArguments()[0];
        @SuppressWarnings("unchecked")
        ArgumentBinder<List<ConvertedHeaderParam>, HttpRequest<?>> binder = (ArgumentBinder<List<ConvertedHeaderParam>, HttpRequest<?>>) binderRegistry.findArgumentBinder(argument).orElseThrow();

        List<ConvertedHeaderParam> converted = binder.bind(ConversionContext.of(argument), HttpRequest.GET("/header").header("X-Value", "blue"))
            .getValue()
            .orElseThrow();

        assertEquals("reflection:blue", converted.get(0).value);
    }

    @Test
    void reflectionFallbackUsesFromStringBeforeValueOfForEnums() {
        BeanDefinition<QueryResource> definition = context.getBeanDefinition(QueryResource.class);
        ExecutableMethod<QueryResource, Object> method = definition.getRequiredMethod("convertedEnum", ConvertedEnumParam.class);

        @SuppressWarnings("unchecked")
        Argument<ConvertedEnumParam> argument = (Argument<ConvertedEnumParam>) method.getArguments()[0];
        @SuppressWarnings("unchecked")
        ArgumentBinder<ConvertedEnumParam, HttpRequest<?>> binder = (ArgumentBinder<ConvertedEnumParam, HttpRequest<?>>) binderRegistry.findArgumentBinder(argument).orElseThrow();

        ConvertedEnumParam converted = binder.bind(ConversionContext.of(argument), HttpRequest.GET("/query?value=blue"))
            .getValue()
            .orElseThrow();

        assertEquals(ConvertedEnumParam.FROM_STRING, converted);
    }

    @Test
    void reflectionFallbackUsesValueOfBeforeFromStringForNonEnums() {
        BeanDefinition<QueryResource> definition = context.getBeanDefinition(QueryResource.class);
        ExecutableMethod<QueryResource, Object> method = definition.getRequiredMethod("convertedFactory", StaticFactoryParam.class);

        @SuppressWarnings("unchecked")
        Argument<StaticFactoryParam> argument = (Argument<StaticFactoryParam>) method.getArguments()[0];
        @SuppressWarnings("unchecked")
        ArgumentBinder<StaticFactoryParam, HttpRequest<?>> binder = (ArgumentBinder<StaticFactoryParam, HttpRequest<?>>) binderRegistry.findArgumentBinder(argument).orElseThrow();

        StaticFactoryParam converted = binder.bind(ConversionContext.of(argument), HttpRequest.GET("/query?value=blue"))
            .getValue()
            .orElseThrow();

        assertEquals("valueOf:blue", converted.value);
    }

    @Test
    void reflectionFallbackWrapsPathParamConstructionFailure() {
        Argument<ThrowingParam> argument = Argument.of(
            ThrowingParam.class,
            "value",
            parameterMetadata(PathParam.class, "value")
        );

        NotFoundException exception = assertThrows(NotFoundException.class,
            () -> conversionService.convert("blue", ConversionContext.of(argument)));

        assertInstanceOf(IllegalArgumentException.class, exception.getCause());
    }

    @Test
    void reflectionFallbackPropagatesWebApplicationException() {
        Argument<WebApplicationExceptionParam> argument = Argument.of(
            WebApplicationExceptionParam.class,
            "value",
            parameterMetadata(QueryParam.class, "value")
        );

        WebApplicationException exception = assertThrows(WebApplicationException.class,
            () -> conversionService.convert("blue", ConversionContext.of(argument)));

        assertEquals(Response.Status.CREATED.getStatusCode(), exception.getResponse().getStatus());
    }

    @Requires(property = "spec.name", value = "JaxRsRequestFieldInjectionTest")
    @Path("/field-injection")
    static class FieldResource {
        @MatrixParam("color")
        String color;

        @GET
        @Produces("text/plain")
        public String get() {
            return color;
        }
    }

    @Requires(property = "spec.name", value = "JaxRsRequestFieldInjectionTest")
    @Path("/query-field")
    static class QueryFieldResource {
        @QueryParam("color")
        String color;

        @GET
        @Produces("text/plain")
        public String get() {
            return color;
        }
    }

    @Requires(property = "spec.name", value = "JaxRsRequestFieldInjectionTest")
    @Path("/header-field")
    static class HeaderFieldResource {
        @HeaderParam("X-Color")
        String color;

        @GET
        @Produces("text/plain")
        public String get() {
            return color;
        }
    }

    @Requires(property = "spec.name", value = "JaxRsRequestFieldInjectionTest")
    @Path("/cookie-field")
    static class CookieFieldResource {
        @DefaultValue("green")
        @CookieParam("color")
        String color;

        @GET
        @Produces("text/plain")
        public String get() {
            return color;
        }
    }

    @Requires(property = "spec.name", value = "JaxRsRequestFieldInjectionTest")
    @Path("/path-field/{color}")
    static class PathFieldResource {
        @PathParam("color")
        String color;

        @GET
        @Produces("text/plain")
        public String get() {
            return color;
        }
    }

    @Requires(property = "spec.name", value = "JaxRsRequestFieldInjectionTest")
    @Path("/path-field-default/{color}")
    static class DefaultPathFieldResource {
        @DefaultValue("green")
        @PathParam("fallback")
        String color;

        @GET
        @Produces("text/plain")
        public String get() {
            return color;
        }
    }

    @Requires(property = "spec.name", value = "JaxRsRequestFieldInjectionTest")
    @Path("/query")
    static class QueryResource {
        @GET
        @Produces("text/plain")
        public String converted(@QueryParam("value") ConvertedQueryParam value) {
            return value.value;
        }

        @GET
        @Produces("text/plain")
        public String convertedEnum(@QueryParam("value") ConvertedEnumParam value) {
            return value.name();
        }

        @GET
        @Produces("text/plain")
        public String convertedFactory(@QueryParam("value") StaticFactoryParam value) {
            return value.value;
        }
    }

    @Requires(property = "spec.name", value = "JaxRsRequestFieldInjectionTest")
    @Path("/header")
    static class HeaderResource {
        @GET
        @Produces("text/plain")
        public String converted(@HeaderParam("X-Value") ConvertedHeaderParam value) {
            return value.value;
        }

        @GET
        @Produces("text/plain")
        public String convertedDefault(@DefaultValue("green") @HeaderParam("X-Value") ConvertedHeaderParam value) {
            return value.value;
        }

        @GET
        @Produces("text/plain")
        public String convertedList(@HeaderParam("X-Value") List<ConvertedHeaderParam> value) {
            return value.get(0).value;
        }
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD, visibility = Introspected.Visibility.ANY)
    static final class BeanParamRequest {
        @QueryParam("jaxrs-color")
        String color;

        String unannotated = "unchanged";
    }

    @Requires(property = "spec.name", value = "JaxRsRequestFieldInjectionTest")
    @Path("/static-final")
    static class StaticFinalFieldResource {
        @QueryParam("staticColor")
        static String staticColor = "static";

        @QueryParam("finalColor")
        final String finalColor = "final";

        @GET
        @Produces("text/plain")
        public String get() {
            return staticColor + ":" + finalColor;
        }
    }

    static final class ConvertedQueryParam {
        private final String value;

        public ConvertedQueryParam(String value) {
            this.value = "reflection:" + value;
        }

        private ConvertedQueryParam(String value, boolean ignored) {
            this.value = value;
        }
    }

    public static final class ConvertedHeaderParam {
        private final String value;

        public ConvertedHeaderParam(String value) {
            this.value = "reflection:" + value;
        }
    }

    enum ConvertedEnumParam {
        FROM_STRING,
        VALUE_OF;

        public static ConvertedEnumParam fromString(String ignored) {
            return FROM_STRING;
        }
    }

    public static final class StaticFactoryParam {
        private final String value;

        private StaticFactoryParam(String value) {
            this.value = value;
        }

        public static StaticFactoryParam valueOf(String value) {
            return new StaticFactoryParam("valueOf:" + value);
        }

        public static StaticFactoryParam fromString(String value) {
            return new StaticFactoryParam("fromString:" + value);
        }
    }

    public static final class ThrowingParam {
        public ThrowingParam(String value) {
            throw new IllegalArgumentException(value);
        }
    }

    public static final class WebApplicationExceptionParam {
        public WebApplicationExceptionParam(String ignored) {
            throw new WebApplicationException(Response.status(Response.Status.CREATED).build());
        }
    }

    private static HttpRequest<?> pathRequest(String template, String path) {
        HttpRequest<?> request = HttpRequest.GET(path);
        BasicHttpAttributes.setUriTemplate(request, template);
        return request;
    }

    private static MutableAnnotationMetadata parameterMetadata(Class<? extends Annotation> annotationType, String name) {
        MutableAnnotationMetadata annotationMetadata = new MutableAnnotationMetadata();
        annotationMetadata.addAnnotation(annotationType.getName(), Map.of(AnnotationMetadata.VALUE_MEMBER, name));
        return annotationMetadata;
    }

    @Provider
    @Requires(property = "spec.name", value = "JaxRsRequestFieldInjectionTest")
    static final class ConvertedQueryParamConverterProvider implements ParamConverterProvider {
        @Override
        @SuppressWarnings("unchecked")
        public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
            if (rawType == ConvertedQueryParam.class) {
                return (ParamConverter<T>) new ParamConverter<ConvertedQueryParam>() {
                    @Override
                    public ConvertedQueryParam fromString(String value) {
                        return new ConvertedQueryParam("provider:" + value, true);
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
}
