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
import io.micronaut.core.bind.ArgumentBinder;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.RequestBinderRegistry;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
@Property(name = "spec.name", value = "JaxRsRequestFieldInjectionTest")
class JaxRsRequestFieldInjectionTest {

    @Inject
    ApplicationContext context;

    @Inject
    RequestBinderRegistry binderRegistry;

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
    @Path("/query")
    static class QueryResource {
        @GET
        @Produces("text/plain")
        public String converted(@QueryParam("value") ConvertedQueryParam value) {
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
