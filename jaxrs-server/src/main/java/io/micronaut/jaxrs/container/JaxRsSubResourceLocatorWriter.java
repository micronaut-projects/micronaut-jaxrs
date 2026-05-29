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

import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.bind.ArgumentBinder;
import io.micronaut.core.bind.exceptions.UnsatisfiedArgumentException;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.ConversionError;
import io.micronaut.core.convert.exceptions.ConversionErrorException;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.io.buffer.ByteBufferFactory;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.HttpMethodMapping;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.body.ByteBodyFactory;
import io.micronaut.http.body.CloseableByteBody;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.body.ResponseBodyWriter;
import io.micronaut.http.bind.RequestBinderRegistry;
import io.micronaut.http.codec.CodecException;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.ProxyBeanDefinition;
import io.micronaut.jaxrs.common.JaxRsContainerMessageBodyHandlerRegistry;
import io.micronaut.jaxrs.common.JaxRsMutableResponse;
import io.micronaut.jaxrs.common.JaxRsResourceTemplateMetadata;
import io.micronaut.jaxrs.common.JaxRsRouteScore;
import io.micronaut.jaxrs.common.JaxRsSubResourceLocatorMetadata;
import io.micronaut.jaxrs.runtime.ext.bind.UriInfoImpl;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.RouteMatch;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotAcceptableException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.StatusType;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Providers;
import org.jspecify.annotations.Nullable;

import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes responses for routes exposed from Jakarta REST subresource locator methods.
 */
@Internal
@Singleton
final class JaxRsSubResourceLocatorWriter implements ResponseBodyWriter<Object>, Ordered {

    private final BeanContext beanContext;
    private final JaxRsContainerMessageBodyHandlerRegistry jaxRsMessageBodyHandlerRegistry;
    private final MessageBodyHandlerRegistry bodyHandlerRegistry;
    private final ApplicationProvider applicationProvider;
    private final RequestBinderRegistry requestBinderRegistry;
    private final Providers providers;
    private final Map<BeanDefinition<?>, DynamicSubResourceMethods> dynamicMethodsByBeanDefinition = new ConcurrentHashMap<>();
    private final Map<StaticSubResourceTargetsKey, StaticSubResourceTargets> staticTargetsByType = new ConcurrentHashMap<>();

    JaxRsSubResourceLocatorWriter(BeanContext beanContext,
                                  JaxRsContainerMessageBodyHandlerRegistry jaxRsMessageBodyHandlerRegistry,
                                  MessageBodyHandlerRegistry bodyHandlerRegistry,
                                  ApplicationProvider applicationProvider,
                                  RequestBinderRegistry requestBinderRegistry,
                                  Providers providers) {
        this.beanContext = beanContext;
        this.jaxRsMessageBodyHandlerRegistry = jaxRsMessageBodyHandlerRegistry;
        this.bodyHandlerRegistry = bodyHandlerRegistry;
        this.applicationProvider = applicationProvider;
        this.requestBinderRegistry = requestBinderRegistry;
        this.providers = providers;
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }

    @Override
    public boolean isWriteable(Argument<Object> type, @Nullable MediaType mediaType) {
        return type.getAnnotationMetadata().hasAnnotation(JaxRsSubResourceLocator.class);
    }

    @Override
    public void writeTo(Argument<Object> type,
                        MediaType mediaType,
                        Object object,
                        MutableHeaders outgoingHeaders,
                        OutputStream outputStream) throws CodecException {
        Object result = invokeSubResourceMethod(type, object, ServerRequestContext.currentRequest().orElse(null));
        result = unwrapJaxRsResponse(result, null, outgoingHeaders);
        if (result != null) {
            writeResult(result, mediaType, outgoingHeaders, outputStream);
        }
    }

    @Override
    public CloseableByteBody writePiece(ByteBodyFactory bodyFactory,
                                        HttpRequest<?> request,
                                        HttpResponse<?> response,
                                        Argument<Object> type,
                                        MediaType mediaType,
                                        Object object) throws CodecException {
        Object result;
        try {
            result = invokeSubResourceMethod(type, object, request);
        } catch (WebApplicationException e) {
            if (response instanceof MutableHttpResponse<?> mutableResponse) {
                result = applyExceptionResponse(e, mutableResponse);
                if (result == null) {
                    return bodyFactory.createEmpty();
                }
                return writeResultPiece(bodyFactory, request, mutableResponse, mediaType, result);
            }
            throw e;
        }
        result = unwrapJaxRsResponse(result, response, null);
        if (result == null) {
            return bodyFactory.createEmpty();
        }
        return writeResultPiece(bodyFactory, request, response, mediaType, result);
    }

    private CloseableByteBody writeResultPiece(ByteBodyFactory bodyFactory,
                                               HttpRequest<?> request,
                                               HttpResponse<?> response,
                                               MediaType mediaType,
                                               Object result) throws CodecException {
        @SuppressWarnings("unchecked")
        Argument<Object> resultType = (Argument<Object>) Argument.of(result.getClass());
        MediaType resultMediaType = response.getContentType().orElse(mediaType);
        MessageBodyWriter<Object> writer = findResultWriter(resultType, resultMediaType);
        ResponseBodyWriter<Object> responseWriter = ResponseBodyWriter.wrap(writer.createSpecific(resultType));
        return responseWriter.writePiece(bodyFactory, request, response, resultType, resultMediaType, result);
    }

    @Override
    public ByteBuffer<?> writeTo(Argument<Object> type,
                                 MediaType mediaType,
                                 Object object,
                                 MutableHeaders outgoingHeaders,
                                 ByteBufferFactory<?, ?> bufferFactory) throws CodecException {
        Object result = invokeSubResourceMethod(type, object, ServerRequestContext.currentRequest().orElse(null));
        result = unwrapJaxRsResponse(result, null, null);
        if (result == null) {
            return bufferFactory.buffer(0);
        }
        @SuppressWarnings("unchecked")
        Argument<Object> resultType = (Argument<Object>) Argument.of(result.getClass());
        return findResultWriter(resultType, mediaType)
            .createSpecific(resultType)
            .writeTo(resultType, mediaType, result, outgoingHeaders, bufferFactory);
    }

    private Object invokeSubResourceMethod(Argument<Object> type, Object subResource, @Nullable HttpRequest<?> request) {
        if (isDynamicSubResourceLocator(type)) {
            return invokeDynamicSubResourceMethod(type, subResource, request);
        }
        Class<?> resourceType = type.getAnnotationMetadata()
            .classValue(JaxRsSubResourceLocator.class, JaxRsSubResourceLocatorMetadata.MEMBER_TYPE)
            .orElseThrow(() -> new CodecException("Missing Jakarta REST subresource locator target type"));
        SubResourceTarget target = selectSubResourceTarget(type, request);
        BeanDefinition<?> beanDefinition = findSubResourceBeanDefinition(resourceType);
        if (subResource instanceof Class<?>) {
            subResource = beanContext.createBean(resourceType);
        }
        subResource = invokeRecursiveLocators(type, request, beanDefinition, subResource);
        if (request != null && !target.resourceTemplate().isEmpty()) {
            request.setAttribute(PathParamArgumentBinder.URI_TEMPLATE_ATTRIBUTE, target.resourceTemplate());
        }
        ExecutableMethod<?, ?> method = findSubResourceMethod(beanDefinition, target.methodName(), target.argumentTypes());
        if (request != null) {
            validateMediaTypes(target, beanDefinition, method, request);
        }
        return invoke(method, subResource, resolveArguments(method, request));
    }

    private static boolean isDynamicSubResourceLocator(Argument<Object> type) {
        return type.getAnnotationMetadata()
            .booleanValue(JaxRsSubResourceLocator.class, JaxRsSubResourceLocatorMetadata.MEMBER_DYNAMIC)
            .orElse(false);
    }

    private Object invokeDynamicSubResourceMethod(Argument<Object> type, Object subResource, @Nullable HttpRequest<?> request) {
        if (request == null) {
            throw new NotFoundException();
        }
        String remainingVariable = type.getAnnotationMetadata()
            .stringValue(JaxRsSubResourceLocator.class, JaxRsSubResourceLocatorMetadata.MEMBER_REMAINING)
            .orElse("");
        List<String> remainingSegments = pathSegments(remainingVariable.isEmpty() ? "" : remainingPath(request, remainingVariable));
        DynamicSubResourceTarget target = selectDynamicSubResourceTarget(subResource, remainingSegments, request);
        if (request != null) {
            request.setAttribute(PathParamArgumentBinder.URI_TEMPLATE_ATTRIBUTE, target.resourceTemplate());
        }
        validateMediaTypes(target.method(), target.beanDefinition(), request);
        return invoke(target.method(), target.resource(), resolveArguments(target.method(), request));
    }

    private DynamicSubResourceTarget selectDynamicSubResourceTarget(Object subResource,
                                                                    List<String> remainingSegments,
                                                                    HttpRequest<?> request) {
        Object current = subResource;
        List<String> segments = remainingSegments;
        JaxRsRequestMethod requestMethod = JaxRsRequestMethod.forRequest(request);
        while (current != null) {
            if (current instanceof Class<?> resourceType) {
                current = beanContext.createBean(resourceType);
            }
            BeanDefinition<?> beanDefinition = findSubResourceBeanDefinition(runtimeResourceType(current));
            DynamicSubResourceMethods dynamicMethods = dynamicSubResourceMethods(beanDefinition);
            // Dynamic locators cannot be fully resolved by the visitor because the
            // runtime object type may change at each locator step. The visitor still
            // narrows candidates through metadata; this loop only walks the returned
            // resource object graph.
            Optional<DynamicResourceMethod> resourceMethod = findDynamicResourceMethod(dynamicMethods.resourceMethods(), segments, requestMethod);
            if (resourceMethod.isPresent()) {
                DynamicResourceMethod method = resourceMethod.get();
                return new DynamicSubResourceTarget(current, beanDefinition, method.method(), method.resourceTemplate());
            }
            Optional<DynamicLocatorMethod> locatorMethod = findDynamicLocatorMethod(dynamicMethods.locatorMethods(), segments);
            if (locatorMethod.isEmpty()) {
                break;
            }
            DynamicLocatorMethod locator = locatorMethod.get();
            ExecutableMethod<?, ?> method = locator.method();
            current = invoke(method, current, resolveArguments(method, request));
            segments = segments.subList(locator.pathSegments().size(), segments.size());
        }
        throw new NotFoundException();
    }

    private static Class<?> runtimeResourceType(Object subResource) {
        if (subResource instanceof Class<?> resourceType) {
            return resourceType;
        }
        return subResource.getClass();
    }

    private DynamicSubResourceMethods dynamicSubResourceMethods(BeanDefinition<?> beanDefinition) {
        return dynamicMethodsByBeanDefinition.computeIfAbsent(beanDefinition, JaxRsSubResourceLocatorWriter::createDynamicSubResourceMethods);
    }

    private static DynamicSubResourceMethods createDynamicSubResourceMethods(BeanDefinition<?> beanDefinition) {
        List<DynamicLocatorMethod> locatorMethods = new ArrayList<>();
        List<DynamicResourceMethod> resourceMethods = new ArrayList<>();
        for (ExecutableMethod<?, ?> method : beanDefinition.getExecutableMethods()) {
            if (method.booleanValue(JaxRsSubResourceLocator.class, JaxRsSubResourceLocatorMetadata.MEMBER_DYNAMIC).orElse(false)) {
                String routePath = method.stringValue(JaxRsSubResourceLocator.class, JaxRsSubResourceLocatorMetadata.MEMBER_ROUTE_PATH)
                    .orElseGet(() -> method.stringValue(HttpMethodMapping.class).orElse(""));
                locatorMethods.add(new DynamicLocatorMethod(
                    method,
                    pathSegments(routePath),
                    JaxRsRouteScore.of(routePath)
                ));
            }
            String httpMethod = method.stringValue(JaxRsResourceTemplate.class, JaxRsResourceTemplateMetadata.MEMBER_HTTP_METHOD).orElse("");
            if (!httpMethod.isEmpty()) {
                String routePath = method.stringValue(HttpMethodMapping.class).orElse("");
                resourceMethods.add(new DynamicResourceMethod(
                    method,
                    httpMethod,
                    pathSegments(routePath),
                    JaxRsRouteScore.of(routePath),
                    dynamicResourceTemplate(beanDefinition, method)
                ));
            }
        }
        return new DynamicSubResourceMethods(List.copyOf(locatorMethods), List.copyOf(resourceMethods));
    }

    private static Optional<DynamicLocatorMethod> findDynamicLocatorMethod(List<DynamicLocatorMethod> methods,
                                                                          List<String> remainingSegments) {
        DynamicLocatorMethod best = null;
        for (DynamicLocatorMethod method : methods) {
            if (!matchesTemplatePrefix(method.pathSegments(), remainingSegments)) {
                continue;
            }
            if (best == null || JaxRsRouteScore.COMPARATOR.compare(method.score(), best.score()) > 0) {
                best = method;
            }
        }
        return Optional.ofNullable(best);
    }

    private static Optional<DynamicResourceMethod> findDynamicResourceMethod(List<DynamicResourceMethod> methods,
                                                                            List<String> remainingSegments,
                                                                            JaxRsRequestMethod requestMethod) {
        DynamicResourceMethod best = null;
        for (DynamicResourceMethod method : methods) {
            if (!requestMethod.matches(method.httpMethod())
                || !matchesTemplate(method.pathSegments(), remainingSegments)) {
                continue;
            }
            if (best == null || JaxRsRouteScore.COMPARATOR.compare(method.score(), best.score()) > 0) {
                best = method;
            }
        }
        return Optional.ofNullable(best);
    }

    private SubResourceTarget selectSubResourceTarget(Argument<Object> type, @Nullable HttpRequest<?> request) {
        StaticSubResourceTargets targets = staticSubResourceTargets(type);
        if (request == null || targets.candidates().isEmpty()) {
            return targets.fallback();
        }
        String lookupPath = applicationRelativePath(request.getUri().getRawPath());
        List<String> lookupSegments = pathSegments(lookupPath);
        JaxRsRequestMethod requestMethod = JaxRsRequestMethod.forRequest(request);
        return targets.candidates().stream()
            .filter(candidate -> requestMethod.matches(candidate.httpMethod()))
            .filter(candidate -> matchesTemplate(candidate.pathSegments(), lookupSegments, targets.recursive()))
            .max((left, right) -> JaxRsRouteScore.COMPARATOR.compare(left.score(), right.score()))
            .orElseThrow(NotFoundException::new);
    }

    private StaticSubResourceTargets staticSubResourceTargets(Argument<Object> type) {
        return staticTargetsByType.computeIfAbsent(staticSubResourceTargetsKey(type), ignored -> createStaticSubResourceTargets(type));
    }

    private static StaticSubResourceTargetsKey staticSubResourceTargetsKey(Argument<?> type) {
        AnnotationMetadata annotationMetadata = type.getAnnotationMetadata();
        return new StaticSubResourceTargetsKey(
            type.getType().getName(),
            annotationMetadata.stringValue(JaxRsSubResourceLocator.class).orElse(""),
            annotationMetadata.stringValue(JaxRsSubResourceLocator.class, JaxRsSubResourceLocatorMetadata.MEMBER_ROUTE_PATH).orElse("")
        );
    }

    private static StaticSubResourceTargets createStaticSubResourceTargets(Argument<?> type) {
        String methodName = type.getAnnotationMetadata()
            .stringValue(JaxRsSubResourceLocator.class)
            .orElseThrow(() -> new CodecException("Missing Jakarta REST subresource locator target method"));
        String[] argumentTypes = type.getAnnotationMetadata()
            .stringValues(JaxRsSubResourceLocator.class, JaxRsSubResourceLocatorMetadata.MEMBER_ARGUMENT_TYPES);
        SubResourceTarget fallback = new SubResourceTarget(
            methodName,
            "",
            List.of(),
            argumentTypes,
            "",
            new String[0],
            new String[0],
            false,
            JaxRsRouteScore.of("")
        );
        String[] targetMethods = type.getAnnotationMetadata()
            .stringValues(JaxRsSubResourceLocator.class, JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_METHODS);
        String[] targetResourceTemplates = type.getAnnotationMetadata()
            .stringValues(JaxRsSubResourceLocator.class, JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_RESOURCE_TEMPLATES);
        if (targetMethods.length == 0 || targetResourceTemplates.length != targetMethods.length) {
            return new StaticSubResourceTargets(fallback, List.of(), false);
        }
        String[] targetHttpMethods = type.getAnnotationMetadata()
            .stringValues(JaxRsSubResourceLocator.class, JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_HTTP_METHODS);
        String[] targetArgumentTypes = type.getAnnotationMetadata()
            .stringValues(JaxRsSubResourceLocator.class, JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_ARGUMENT_TYPES);
        int[] targetArgumentTypeCounts = type.getAnnotationMetadata()
            .getValue(JaxRsSubResourceLocator.class, JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_ARGUMENT_TYPE_COUNTS, int[].class)
            .orElse(new int[0]);
        String[] targetConsumes = type.getAnnotationMetadata()
            .stringValues(JaxRsSubResourceLocator.class, JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_CONSUMES);
        int[] targetConsumesCounts = type.getAnnotationMetadata()
            .getValue(JaxRsSubResourceLocator.class, JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_CONSUMES_COUNTS, int[].class)
            .orElse(new int[0]);
        String[] targetProduces = type.getAnnotationMetadata()
            .stringValues(JaxRsSubResourceLocator.class, JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_PRODUCES);
        int[] targetProducesCounts = type.getAnnotationMetadata()
            .getValue(JaxRsSubResourceLocator.class, JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_PRODUCES_COUNTS, int[].class)
            .orElse(new int[0]);
        boolean recursive = type.getAnnotationMetadata()
            .stringValue(JaxRsSubResourceLocator.class, JaxRsSubResourceLocatorMetadata.MEMBER_RECURSIVE)
            .filter(value -> !value.isEmpty())
            .isPresent();
        return new StaticSubResourceTargets(
            fallback,
            subResourceTargets(
                targetMethods,
                targetHttpMethods,
                targetResourceTemplates,
                targetArgumentTypes,
                targetArgumentTypeCounts,
                argumentTypes,
                targetConsumes,
                targetConsumesCounts,
                targetProduces,
                targetProducesCounts
            ),
            recursive
        );
    }

    private String applicationRelativePath(String rawPath) {
        String path = stripApplicationPath(rawPath, applicationProvider.getPath());
        return stripApplicationPath(path, applicationProvider.getApplicationPath());
    }

    private static String stripApplicationPath(String path, String applicationPath) {
        if ("/".equals(applicationPath) || !path.startsWith(applicationPath)) {
            return path;
        }
        int prefixLength = applicationPath.length();
        if (path.length() == prefixLength) {
            return "/";
        }
        if (path.charAt(prefixLength) == '/') {
            return path.substring(prefixLength);
        }
        return path;
    }

    private static List<SubResourceTarget> subResourceTargets(String[] methods,
                                                             String[] httpMethods,
                                                             String[] resourceTemplates,
                                                             String[] argumentTypes,
                                                             int[] argumentTypeCounts,
                                                             String[] fallbackArgumentTypes,
                                                             String[] consumes,
                                                             int[] consumesCounts,
                                                             String[] produces,
                                                             int[] producesCounts) {
        SubResourceTarget[] targets = new SubResourceTarget[methods.length];
        int argumentOffset = 0;
        int consumesOffset = 0;
        int producesOffset = 0;
        boolean mediaMetadata = consumesCounts.length == methods.length && producesCounts.length == methods.length;
        for (int i = 0; i < methods.length; i++) {
            String[] methodArgumentTypes;
            if (argumentTypeCounts.length == methods.length) {
                int argumentTypeCount = argumentTypeCounts[i];
                methodArgumentTypes = Arrays.copyOfRange(argumentTypes, argumentOffset, argumentOffset + argumentTypeCount);
                argumentOffset += argumentTypeCount;
            } else if (argumentTypes.length == methods.length) {
                methodArgumentTypes = new String[] { argumentTypes[i] };
            } else if (argumentTypes.length == 0) {
                methodArgumentTypes = new String[0];
            } else {
                methodArgumentTypes = fallbackArgumentTypes;
            }
            String httpMethod = httpMethods.length == methods.length ? httpMethods[i] : "";
            String[] methodConsumes = new String[0];
            String[] methodProduces = new String[0];
            if (mediaMetadata) {
                int consumesCount = consumesCounts[i];
                methodConsumes = Arrays.copyOfRange(consumes, consumesOffset, consumesOffset + consumesCount);
                consumesOffset += consumesCount;
                int producesCount = producesCounts[i];
                methodProduces = Arrays.copyOfRange(produces, producesOffset, producesOffset + producesCount);
                producesOffset += producesCount;
            }
            String resourceTemplate = resourceTemplates[i];
            targets[i] = new SubResourceTarget(
                methods[i],
                resourceTemplate,
                pathSegments(resourceTemplate),
                methodArgumentTypes,
                httpMethod,
                methodConsumes,
                methodProduces,
                mediaMetadata,
                JaxRsRouteScore.of(resourceTemplate)
            );
        }
        return List.of(targets);
    }

    private static boolean matchesTemplate(List<String> templateSegments, List<String> pathSegments, boolean prefixMatch) {
        if (prefixMatch) {
            return matchesTemplatePrefix(templateSegments, pathSegments);
        }
        int offset = pathSegments.size() - templateSegments.size();
        if (offset < 0) {
            return false;
        }
        for (int i = 0; i < templateSegments.size(); i++) {
            String templateSegment = templateSegments.get(i);
            String pathSegment = stripMatrixParameters(pathSegments.get(offset + i));
            if (!isTemplateVariable(templateSegment) && !templateSegment.equals(pathSegment)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesTemplate(List<String> templateSegments, List<String> pathSegments) {
        if (templateSegments.size() != pathSegments.size()) {
            return false;
        }
        for (int i = 0; i < templateSegments.size(); i++) {
            String templateSegment = templateSegments.get(i);
            String pathSegment = stripMatrixParameters(pathSegments.get(i));
            if (!isTemplateVariable(templateSegment) && !templateSegment.equals(pathSegment)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesTemplatePrefix(List<String> templateSegments, List<String> pathSegments) {
        if (templateSegments.isEmpty()) {
            return true;
        }
        if (templateSegments.size() > pathSegments.size()) {
            return false;
        }
        for (int i = 0; i < templateSegments.size(); i++) {
            String templateSegment = templateSegments.get(i);
            String pathSegment = stripMatrixParameters(pathSegments.get(i));
            if (!isTemplateVariable(templateSegment) && !templateSegment.equals(pathSegment)) {
                return false;
            }
        }
        return true;
    }

    private static String dynamicResourceTemplate(BeanDefinition<?> beanDefinition, ExecutableMethod<?, ?> method) {
        return method.stringValue(JaxRsResourceTemplate.class)
            .orElseGet(() -> dynamicResourceTemplateFallback(beanDefinition, method));
    }

    private static String dynamicResourceTemplateFallback(BeanDefinition<?> beanDefinition, ExecutableMethod<?, ?> method) {
        String beanPath = beanDefinition.stringValue(Controller.class).orElse("");
        String methodPath = method.stringValue(HttpMethodMapping.class).orElse("");
        if (beanPath.isEmpty()) {
            return methodPath;
        }
        if (methodPath.isEmpty() || "/".equals(methodPath)) {
            return beanPath;
        }
        if ("/".equals(beanPath)) {
            return methodPath;
        }
        return beanPath + (methodPath.startsWith("/") ? methodPath : '/' + methodPath);
    }

    private static boolean isTemplateVariable(String templateSegment) {
        return templateSegment.startsWith("{") && templateSegment.endsWith("}");
    }

    private static String stripMatrixParameters(String pathSegment) {
        int matrixIndex = pathSegment.indexOf(';');
        return matrixIndex > -1 ? pathSegment.substring(0, matrixIndex) : pathSegment;
    }

    private static List<String> pathSegments(String path) {
        int queryIndex = path.indexOf('?');
        if (queryIndex > -1) {
            path = path.substring(0, queryIndex);
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(path.split("/"));
    }

    private static ExecutableMethod<?, ?> findSubResourceMethod(BeanDefinition<?> beanDefinition,
                                                                String methodName,
                                                                String[] argumentTypes) {
        return beanDefinition.findPossibleMethods(methodName)
            .filter(method -> matchesArgumentTypes(method.getArguments(), argumentTypes))
            .findFirst()
            .orElseThrow(() -> new CodecException("Missing Jakarta REST subresource target method: " + methodName));
    }

    private static boolean matchesArgumentTypes(Argument<?>[] arguments, String[] argumentTypes) {
        if (arguments.length != argumentTypes.length) {
            return false;
        }
        for (int i = 0; i < arguments.length; i++) {
            if (!arguments[i].getType().getName().equals(argumentTypes[i])) {
                return false;
            }
        }
        return true;
    }

    private static void validateMediaTypes(SubResourceTarget target,
                                           BeanDefinition<?> beanDefinition,
                                           ExecutableMethod<?, ?> method,
                                           HttpRequest<?> request) {
        String[] consumes = target.mediaMetadata() ? target.consumes() : mediaTypes(beanDefinition, method, Consumes.class);
        if (consumes.length > 0) {
            request.getContentType().ifPresent(contentType -> {
                if (!anyConsumedMediaTypeMatches(contentType, consumes)) {
                    throw new NotSupportedException();
                }
            });
        }
        String[] produces = target.mediaMetadata() ? target.produces() : mediaTypes(beanDefinition, method, Produces.class);
        if (produces.length > 0 && !anyProducedMediaTypeMatches(request.accept(), produces)) {
            throw new NotAcceptableException();
        }
    }

    private static void validateMediaTypes(ExecutableMethod<?, ?> method,
                                           BeanDefinition<?> beanDefinition,
                                           HttpRequest<?> request) {
        String[] consumes = mediaTypes(beanDefinition, method, Consumes.class);
        if (consumes.length > 0) {
            request.getContentType().ifPresent(contentType -> {
                if (!anyConsumedMediaTypeMatches(contentType, consumes)) {
                    throw new NotSupportedException();
                }
            });
        }
        String[] produces = mediaTypes(beanDefinition, method, Produces.class);
        if (produces.length > 0 && !anyProducedMediaTypeMatches(request.accept(), produces)) {
            throw new NotAcceptableException();
        }
    }

    private static String[] mediaTypes(BeanDefinition<?> beanDefinition,
                                       ExecutableMethod<?, ?> method,
                                       Class<? extends Annotation> annotationType) {
        if (method.getAnnotationMetadata().hasDeclaredAnnotation(annotationType)) {
            return method.stringValues(annotationType);
        }
        return beanDefinition.stringValues(annotationType);
    }

    private static boolean anyConsumedMediaTypeMatches(MediaType contentType, String[] consumedMediaTypes) {
        for (MediaType consumedMediaType : MediaType.of(consumedMediaTypes)) {
            if (consumedMediaType.matches(contentType)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyProducedMediaTypeMatches(Collection<MediaType> acceptedMediaTypes, String[] producedMediaTypes) {
        if (acceptedMediaTypes.isEmpty()) {
            return true;
        }
        MediaType[] mediaTypes = MediaType.of(producedMediaTypes);
        for (MediaType acceptedMediaType : acceptedMediaTypes) {
            for (MediaType mediaType : mediaTypes) {
                if (acceptedMediaType.matches(mediaType)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Object[] resolveArguments(ExecutableMethod<?, ?> method, @Nullable HttpRequest<?> request) {
        return Arrays.stream(method.getArguments())
            .map(argument -> resolveArgument(argument, request))
            .toArray(Object[]::new);
    }

    private Object resolveArgument(Argument<?> argument, @Nullable HttpRequest<?> request) {
        if (argument.isAnnotationPresent(Context.class) && argument.getType().equals(UriInfo.class)) {
            if (request == null) {
                throw new CodecException("Cannot bind UriInfo without an active HTTP request");
            }
            return new UriInfoImpl(request, applicationProvider.getPath(), applicationProvider.getApplicationPath());
        }
        if (request == null) {
            throw new CodecException("Cannot bind Jakarta REST subresource target argument without an active HTTP request: " + argument.getName());
        }
        return bindRequestArgument(argument, request);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object bindRequestArgument(Argument<?> argument, HttpRequest<?> request) {
        Argument<Object> objectArgument = (Argument<Object>) argument;
        ArgumentConversionContext<Object> conversionContext = ConversionContext.of(
            objectArgument,
            request.getLocale().orElse(Locale.getDefault()),
            request.getCharacterEncoding()
        );
        ArgumentBinder<Object, HttpRequest<?>> binder = (ArgumentBinder) requestBinderRegistry.findArgumentBinder(objectArgument)
            .orElseThrow(() -> new UnsatisfiedArgumentException(objectArgument));
        ArgumentBinder.BindingResult<Object> result = binder.bind(conversionContext, request);
        List<ConversionError> conversionErrors = result.getConversionErrors();
        if (!conversionErrors.isEmpty()) {
            throw new ConversionErrorException(objectArgument, conversionErrors.get(0));
        }
        Optional<Object> value = result.getValue();
        if (objectArgument.isOptional()) {
            return value;
        }
        if (value.isPresent()) {
            return value.get();
        }
        if (objectArgument.isNullable()) {
            return null;
        }
        throw new UnsatisfiedArgumentException(objectArgument);
    }

    private static Object invokeRecursiveLocators(Argument<Object> type,
                                                  @Nullable HttpRequest<?> request,
                                                  BeanDefinition<?> beanDefinition,
                                                  Object subResource) {
        String recursiveMethod = type.getAnnotationMetadata()
            .stringValue(JaxRsSubResourceLocator.class, JaxRsSubResourceLocatorMetadata.MEMBER_RECURSIVE)
            .orElse("");
        if (recursiveMethod.isEmpty() || request == null) {
            return subResource;
        }
        String remaining = type.getAnnotationMetadata()
            .stringValue(JaxRsSubResourceLocator.class, JaxRsSubResourceLocatorMetadata.MEMBER_REMAINING)
            .orElse("");
        if (remaining.isEmpty()) {
            return subResource;
        }
        String path = remainingPath(request, remaining);
        if (path.isEmpty()) {
            return subResource;
        }
        ExecutableMethod<?, ?> method = beanDefinition.getRequiredMethod(recursiveMethod);
        Object current = subResource;
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) {
                current = invoke(method, current);
            }
        }
        return current;
    }

    private static String remainingPath(HttpRequest<?> request, String remaining) {
        return RouteAttributes.getRouteMatch(request)
            .map(RouteMatch::getVariableValues)
            .map(values -> values.get(remaining))
            .map(Object::toString)
            .orElse("");
    }

    private BeanDefinition<?> findSubResourceBeanDefinition(Class<?> resourceType) {
        return beanContext.getBeanDefinitions(resourceType)
            .stream()
            .filter(beanDefinition -> beanType(beanDefinition).equals(resourceType))
            .findFirst()
            .orElseThrow(() -> new CodecException("Missing Jakarta REST subresource target bean definition: " + resourceType.getName()));
    }

    private static Class<?> beanType(BeanDefinition<?> beanDefinition) {
        if (beanDefinition instanceof ProxyBeanDefinition<?> proxyBeanDefinition) {
            return proxyBeanDefinition.getTargetType();
        }
        return beanDefinition.getBeanType();
    }

    private void writeResult(Object result,
                             MediaType mediaType,
                             MutableHeaders outgoingHeaders,
                             OutputStream outputStream) {
        @SuppressWarnings("unchecked")
        Argument<Object> resultType = (Argument<Object>) Argument.of(result.getClass());
        findResultWriter(resultType, mediaType)
            .createSpecific(resultType)
            .writeTo(resultType, mediaType, result, outgoingHeaders, outputStream);
    }

    private MessageBodyWriter<Object> findResultWriter(Argument<Object> resultType, MediaType mediaType) {
        List<MediaType> mediaTypes = List.of(mediaType);
        return jaxRsMessageBodyHandlerRegistry.findWriter(resultType, mediaTypes)
            .orElseGet(() -> bodyHandlerRegistry.getWriter(resultType, mediaTypes));
    }

    private static @Nullable Object unwrapJaxRsResponse(Object result,
                                                        @Nullable HttpResponse<?> response,
                                                        @Nullable MutableHeaders outgoingHeaders) {
        if (result instanceof JaxRsMutableResponse jaxRsResponse) {
            MutableHttpResponse<?> source = jaxRsResponse.getResponse();
            if (response instanceof MutableHttpResponse<?> mutableResponse) {
                mutableResponse.status(source.code(), source.reason());
                source.getAttributes().forEach(mutableResponse::setAttribute);
                copyHeaders(source, mutableResponse.getHeaders());
            } else if (outgoingHeaders != null) {
                copyHeaders(source, outgoingHeaders);
            }
            return source.getBody().orElse(null);
        }
        return result;
    }

    private static void copyHeaders(HttpResponse<?> source, MutableHeaders target) {
        source.getHeaders().forEach((name, values) -> {
            for (String value : values) {
                target.add(name, value);
            }
        });
    }

    private static void copyHeaders(jakarta.ws.rs.core.Response source, MutableHeaders target) {
        source.getHeaders().forEach((name, values) -> {
            for (Object value : values) {
                target.add(name, value.toString());
            }
        });
    }

    private @Nullable Object applyExceptionResponse(WebApplicationException exception, MutableHttpResponse<?> response) {
        Response exceptionResponse = exceptionResponse(exception);
        StatusType statusInfo = exceptionResponse.getStatusInfo();
        response.status(statusInfo.getStatusCode(), statusInfo.getReasonPhrase());
        if (exceptionResponse instanceof JaxRsMutableResponse jaxRsResponse) {
            MutableHttpResponse<?> source = jaxRsResponse.getResponse();
            source.getAttributes().forEach(response::setAttribute);
            copyHeaders(source, response.getHeaders());
            return source.getBody().orElse(null);
        }
        copyHeaders(exceptionResponse, response.getHeaders());
        return exceptionResponse.hasEntity() ? exceptionResponse.getEntity() : null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Response exceptionResponse(WebApplicationException exception) {
        ExceptionMapper exceptionMapper = providers.getExceptionMapper(exception.getClass());
        if (exceptionMapper != null) {
            return exceptionMapper.toResponse(exception);
        }
        return exception.getResponse();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static @Nullable Object invoke(ExecutableMethod executableMethod, Object subResource, Object... arguments) {
        return executableMethod.invoke(subResource, arguments);
    }

    private record SubResourceTarget(String methodName,
                                     String resourceTemplate,
                                     List<String> pathSegments,
                                     String[] argumentTypes,
                                     String httpMethod,
                                     String[] consumes,
                                     String[] produces,
                                     boolean mediaMetadata,
                                     JaxRsRouteScore score) {
    }

    private record StaticSubResourceTargets(SubResourceTarget fallback,
                                            List<SubResourceTarget> candidates,
                                            boolean recursive) {
    }

    private record StaticSubResourceTargetsKey(String typeName,
                                               String locatorMethod,
                                               String routePath) {
    }

    private record DynamicSubResourceTarget(Object resource,
                                            BeanDefinition<?> beanDefinition,
                                            ExecutableMethod<?, ?> method,
                                            String resourceTemplate) {
    }

    private record DynamicSubResourceMethods(List<DynamicLocatorMethod> locatorMethods,
                                             List<DynamicResourceMethod> resourceMethods) {
    }

    private record DynamicLocatorMethod(ExecutableMethod<?, ?> method,
                                        List<String> pathSegments,
                                        JaxRsRouteScore score) {
    }

    private record DynamicResourceMethod(ExecutableMethod<?, ?> method,
                                         String httpMethod,
                                         List<String> pathSegments,
                                         JaxRsRouteScore score,
                                         String resourceTemplate) {
    }

}
