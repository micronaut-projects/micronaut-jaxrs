/*
 * Copyright 2017-2020 original authors
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
package io.micronaut.jaxrs.processor;

import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NextMajorVersion;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.CookieValue;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.HttpMethodMapping;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.UriMapping;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.TypedElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.inject.Named;
import jakarta.inject.Scope;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * A type element visitor that turns a JAX-RS path into a controller.
 *
 * @author graemerocher
 * @since 1.0
 */
@Internal
public class JaxRsTypeElementVisitor implements TypeElementVisitor<Object, Object> {

    public static final int POSITION = 200;
    private static final String CLIENT_ANNOTATION = "io.micronaut.http.client.annotation.Client";
    private static final String REQUEST_FIELD_INJECTION_ANNOTATION = "io.micronaut.jaxrs.container.JaxRsRequestFieldInjection";
    private static final String SUB_RESOURCE_LOCATOR_ANNOTATION = "io.micronaut.jaxrs.container.JaxRsSubResourceLocator";
    private static final String MATRIX_PARAMETER_ROUTE_PATTERN = ":;[^/]*|";
    private static final Class<?>[] BINDABLE_TYPES = new Class<?>[] {Context.class, SecurityContext.class, UriInfo.class};
    private ClassElement currentClassElement;

    private final List<Class<? extends Annotation>> JAX_RS_BINDING_ANNOTATIONS = List.of(
        HeaderParam.class,
        QueryParam.class,
        FormParam.class,
        MatrixParam.class,
        PathParam.class,
        CookieParam.class,
        BeanParam.class,
        Context.class
    );

    // Backwards compatibility
    @NextMajorVersion("Allow only inject values annotated with @Context")
    private final List<String> JAX_RS_BINDING_TYPES = List.of(
        HttpHeaders.class.getName(),
        Cookie.class.getName(),
        SecurityContext.class.getName(),
        UriInfo.class.getName(),
        "jakarta.servlet.ServletContext",
        "jakarta.servlet.ServletRequest",
        "jakarta.servlet.http.HttpServletRequest",
        "jakarta.servlet.ServletResponse",
        "jakarta.servlet.http.HttpServletResponse",
        "jakarta.servlet.ServletConfig"
    );

    @Override
    public int getOrder() {
        return POSITION; // higher priority to ensure mutations visible
    }

    @NonNull
    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public Set<String> getSupportedAnnotationNames() {
        return Collections.singleton("jakarta.ws.rs.*");
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (element.hasStereotype(Provider.class)) {
            if (element.isEnum()) {
                return;
            }
            element.annotate(Singleton.class);
            element.annotate(Named.class);
            return;
        }
        currentClassElement = element;
        if (element.hasAnnotation(Path.class) && !element.isAbstract()) {
            element.stringValue(Path.class).ifPresent(p -> {
                element.annotate(Controller.class, builder -> builder.value(p));
                element.annotate(UriMapping.class, builder -> builder.value(p));
            });
            if (hasRequestParamField(element)) {
                markRequestFieldInjection();
            }
        }
    }

    @Override
    public void visitMethod(MethodElement element, VisitorContext context) {
        if (isSubResourceLocator(element)) {
            visitSubResourceLocator(element, context);
            return;
        }
        if (element.hasStereotype(HttpMethod.class)) {
            if (currentClassElement != null && !currentClassElement.hasAnnotation(Controller.class) && !currentClassElement.isAbstract()) {
                currentClassElement.annotate(Controller.class);
            }
            if ((currentClassElement == null || !currentClassElement.hasAnnotation(Produces.class)) &&
                !element.hasAnnotation(Produces.class)) {
                element.annotate(Produces.class, b -> b.values(MediaType.ALL));
            }
            if ((currentClassElement == null || !currentClassElement.hasAnnotation(Consumes.class)) &&
                !element.hasAnnotation(Consumes.class)) {
                element.annotate(Consumes.class, b -> b.values(MediaType.ALL));
            }
            if (isServerResourceClass()) {
                List<String> matrixParameterNames = matrixParameterNames(element);
                if (!matrixParameterNames.isEmpty()) {
                    markMatrixAwareClassPath(matrixParameterNames.get(0));
                }
                element.stringValue(HttpMethodMapping.class)
                    .map(path -> toServerRoutePath(element, path, matrixParameterNames))
                    .ifPresent(path -> annotateHttpRoute(element, path));
            }
            visitMethodParameters(element, context, true);
        }
    }

    private void visitSubResourceLocator(MethodElement element, VisitorContext context) {
        if (!isServerResourceClass()) {
            return;
        }
        subResourceTargetMethod(element).ifPresent(targetMethod -> {
            List<AnnotationValue<Annotation>> httpMethodAnnotations = targetMethod.getAnnotationValuesByStereotype(HttpMethodMapping.class.getName());
            if (httpMethodAnnotations.isEmpty()) {
                return;
            }
            String locatorPath = element.stringValue(HttpMethodMapping.class).orElse(UriMapping.DEFAULT_URI);
            String targetPath = targetMethod.stringValue(HttpMethodMapping.class).orElse(UriMapping.DEFAULT_URI);
            String routePath = prependRoutePath(locatorPath, targetPath);
            annotateHttpRoute(element, httpMethodAnnotations.get(0), routePath);
            element.annotate(SUB_RESOURCE_LOCATOR_ANNOTATION, builder -> builder
                .value(targetMethod.getName())
                .member("type", new AnnotationClassValue<>(targetMethod.getDeclaringType().getName())));
            visitMethodParameters(element, context, false);
        });
    }

    private void visitMethodParameters(MethodElement element, VisitorContext context, boolean bindUnannotatedBody) {
        final ParameterElement[] parameters = element.getParameters();
        boolean encoded = isEncoded(element);
        for (ParameterElement parameter : parameters) {
            final List<Class<? extends Annotation>> unsupported = getUnsupportedParameterAnnotations();
            for (Class<? extends Annotation> annType : unsupported) {
                if (parameter.hasAnnotation(annType)) {
                    context.fail("Unsupported JAX-RS annotation used on method: " + annType.getName(), parameter);
                }
            }
            if (encoded && parameter.hasAnnotation(MatrixParam.class) && !parameter.hasAnnotation(Encoded.class)) {
                parameter.annotate(Encoded.class);
            }
            visitParamOrField(parameter);
            String parameterTypeName = parameter.getType().getName();
            if (bindUnannotatedBody
                && JAX_RS_BINDING_ANNOTATIONS.stream().noneMatch(parameter::hasAnnotation)
                && JAX_RS_BINDING_TYPES.stream().noneMatch(cl -> cl.equals(parameterTypeName))) {
                // unannotated, implicit @Body
                parameter.annotate(Body.class);
                parameter.annotate(Nullable.class); // JAX-RS controller bodies are nullable by default
            }
        }
    }

    @Override
    public void visitField(FieldElement element, VisitorContext context) {
        visitParamOrField(element);
        if (element.hasAnnotation(MatrixParam.class)) {
            markRequestFieldInjection();
            markMatrixAwareClassPath(getMatrixParameterName(element));
        } else if (element.hasAnnotation(QueryParam.class)) {
            markRequestFieldInjection();
        } else if (element.hasAnnotation(HeaderParam.class)) {
            markRequestFieldInjection();
        } else if (element.hasAnnotation(CookieParam.class)) {
            element.removeAnnotation(CookieValue.class);
            markRequestFieldInjection();
        } else if (element.hasAnnotation(FormParam.class) ||
            element.hasAnnotation(PathParam.class) ||
            element.hasAnnotation(BeanParam.class)
        ) {
            context.fail("Request scoped bean parameters are currently not supported", element); // todo
        }
    }

    private void markRequestFieldInjection() {
        if (currentClassElement != null) {
            currentClassElement.annotate(REQUEST_FIELD_INJECTION_ANNOTATION);
            if (!currentClassElement.hasStereotype(Scope.class)) {
                currentClassElement.annotate(Prototype.class);
            }
        }
    }

    private static boolean hasRequestParamField(ClassElement element) {
        return element.getFields().stream().anyMatch(JaxRsTypeElementVisitor::isRequestParamField);
    }

    private static boolean isRequestParamField(FieldElement field) {
        return field.hasAnnotation(MatrixParam.class) || field.hasAnnotation(QueryParam.class) || field.hasAnnotation(HeaderParam.class) || field.hasAnnotation(CookieParam.class);
    }

    private void markMatrixAwareClassPath(String matrixParameterName) {
        if (currentClassElement == null) {
            return;
        }
        currentClassElement.stringValue(Controller.class)
            .filter(path -> !path.contains(MATRIX_PARAMETER_ROUTE_PATTERN))
            .ifPresent(path -> {
                currentClassElement.removeAnnotation(Controller.class);
                currentClassElement.annotate(Controller.class, builder -> builder.value(toMatrixParameterAwareRoute(path, List.of(matrixParameterName))));
            });
        currentClassElement.stringValue(UriMapping.class)
            .filter(path -> !path.contains(MATRIX_PARAMETER_ROUTE_PATTERN))
            .ifPresent(path -> {
                currentClassElement.removeAnnotation(UriMapping.class);
                currentClassElement.annotate(UriMapping.class, builder -> builder.value(toMatrixParameterAwareRoute(path, List.of(matrixParameterName))));
            });
    }

    private void visitParamOrField(TypedElement parameter) {
        if (parameter.hasAnnotation(HeaderParam.class)) {
            if (isClientClass()) {
                mapParam(parameter, HeaderParam.class, Header.class);
            } else {
                annotateHeaderParam(parameter);
            }
        }
        mapParam(parameter, FormParam.class, Body.class);
        if (parameter.hasAnnotation(CookieParam.class)) {
            if (isClientClass()) {
                mapParam(parameter, CookieParam.class, CookieValue.class);
            } else {
                parameter.removeAnnotation(CookieValue.class);
                annotateCookieParam(parameter);
            }
        }
        mapParam(parameter, PathParam.class, PathVariable.class);
        if (parameter.hasAnnotation(QueryParam.class)) {
            annotateQueryParam(parameter);
        }
        if (parameter.hasAnnotation(MatrixParam.class)) {
            annotateMatrixParam(parameter);
        }
    }

    private static void annotateHeaderParam(TypedElement parameter) {
        AnnotationValueBuilder<HeaderParam> builder = AnnotationValue.builder(HeaderParam.class);
        annotateDefaultAndNullable(parameter, builder);
        parameter.annotate(
            builder
                .stereotype(AnnotationValue.builder(Bindable.class).build()).build()
        );
    }

    private static void annotateQueryParam(TypedElement parameter) {
        AnnotationValueBuilder<QueryParam> builder = AnnotationValue.builder(QueryParam.class);
        annotateDefaultAndNullable(parameter, builder);
        parameter.annotate(
            builder
            .stereotype(AnnotationValue.builder(Bindable.class).build()).build()
        );
    }

    private static void annotateMatrixParam(TypedElement parameter) {
        AnnotationValueBuilder<MatrixParam> builder = AnnotationValue.builder(MatrixParam.class);
        annotateDefaultAndNullable(parameter, builder);
        parameter.annotate(
            builder
                .stereotype(AnnotationValue.builder(Bindable.class).build()).build()
        );
    }

    private static void annotateCookieParam(TypedElement parameter) {
        AnnotationValueBuilder<CookieParam> builder = AnnotationValue.builder(CookieParam.class);
        annotateDefaultAndNullable(parameter, builder);
        parameter.annotate(
            builder
                .stereotype(AnnotationValue.builder(Bindable.class).build()).build()
        );
    }

    private static void annotateDefaultAndNullable(TypedElement parameter, AnnotationValueBuilder<?> builder) {
        if (parameter.isPrimitive()) {
            if (parameter.getType().isAssignable(boolean.class)) {
                annotateDefaultValue(parameter, builder, "false");
            } else {
                annotateDefaultValue(parameter, builder, "0");
            }
        } else if (!parameter.isNonNull()) {
            parameter.annotate(Nullable.class);
        }
    }

    private static void annotateDefaultValue(TypedElement parameter, AnnotationValueBuilder<?> builder, String defaultValue) {
        builder.member("defaultValue", defaultValue);
        if (!parameter.hasAnnotation(Bindable.class)) {
            parameter.annotate(Bindable.class, bindable -> bindable.member("defaultValue", defaultValue));
        }
    }

    private static <P extends Annotation> void mapParam(TypedElement parameter, Class<P> jakartaAnnotation, Class<? extends Annotation> mnAnnotation) {
        AnnotationValue<P> ann = parameter.getAnnotation(jakartaAnnotation);
        if (ann != null) {
            parameter.annotate(mnAnnotation, builder -> {
                ann.stringValue().ifPresent(builder::value);
                annotateDefaultAndNullable(parameter, builder);
            });
        }
    }

    private boolean isServerResourceClass() {
        return currentClassElement != null && !isClientClass();
    }

    private boolean isClientClass() {
        return currentClassElement != null && (currentClassElement.hasStereotype(CLIENT_ANNOTATION) || currentClassElement.hasAnnotation(CLIENT_ANNOTATION));
    }

    private boolean isEncoded(MethodElement element) {
        return element.hasAnnotation(Encoded.class) || currentClassElement != null && currentClassElement.hasAnnotation(Encoded.class);
    }

    private static List<String> matrixParameterNames(MethodElement element) {
        return Arrays.stream(element.getParameters())
            .filter(parameter -> parameter.hasAnnotation(MatrixParam.class))
            .map(parameter -> getMatrixParameterName(parameter))
            .toList();
    }

    private boolean isSubResourceLocator(MethodElement method) {
        return isServerResourceClass() && method.hasAnnotation(Path.class) && !method.hasStereotype(HttpMethod.class);
    }

    private static java.util.Optional<MethodElement> subResourceTargetMethod(MethodElement locator) {
        return locator.getReturnType()
            .getMethods()
            .stream()
            .filter(method -> method.hasStereotype(HttpMethod.class))
            .filter(method -> method.getParameters().length == 0)
            .findFirst();
    }

    private String toServerRoutePath(MethodElement method, String path, List<String> matrixParameterNames) {
        String routePath = path;
        if (isInheritedResourceMethod(method)) {
            List<String> subResourceLocatorPaths = subResourceLocatorPaths();
            if (!subResourceLocatorPaths.isEmpty()) {
                routePath = prependRoutePath(subResourceLocatorPaths.get(0), routePath);
            }
        }
        if (!matrixParameterNames.isEmpty()) {
            routePath = toMatrixParameterAwareRoute(routePath, matrixParameterNames);
        }
        return routePath;
    }

    private static void annotateHttpRoute(MethodElement method, String path) {
        method.removeAnnotation(HttpMethodMapping.class);
        method.annotate(HttpMethodMapping.class, builder -> builder.value(path));
    }

    private static void annotateHttpRoute(MethodElement method, AnnotationValue<Annotation> httpMethodAnnotation, String path) {
        method.removeAnnotation(HttpMethodMapping.class);
        method.annotate(httpMethodAnnotation.mutate().value(path).build());
    }

    private boolean isInheritedResourceMethod(MethodElement method) {
        return currentClassElement != null && !method.getDeclaringType().getName().equals(currentClassElement.getName());
    }

    private List<String> subResourceLocatorPaths() {
        if (currentClassElement == null) {
            return List.of();
        }
        return currentClassElement.getMethods().stream()
            .filter(method -> method.hasAnnotation(Path.class))
            .filter(method -> !method.hasStereotype(HttpMethod.class))
            .filter(method -> method.getReturnType().isAssignable(currentClassElement))
            .map(method -> method.stringValue(Path.class).orElse(""))
            .filter(path -> !path.isEmpty())
            .toList();
    }

    private static String getMatrixParameterName(TypedElement parameter) {
        return parameter.stringValue(MatrixParam.class).orElse(parameter.getName());
    }

    private static String prependRoutePath(String prefix, String path) {
        String normalizedPrefix = normalizeRoutePath(prefix);
        String normalizedPath = normalizeRoutePath(path);
        if ("/".equals(normalizedPath)) {
            return normalizedPrefix;
        }
        if ("/".equals(normalizedPrefix)) {
            return normalizedPath;
        }
        return normalizedPrefix + normalizedPath;
    }

    private static String normalizeRoutePath(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "/";
        }
        return path.charAt(0) == '/' ? path : '/' + path;
    }

    private static String toMatrixParameterAwareRoute(String path, List<String> matrixParameterNames) {
        if (path == null || path.isEmpty() || path.equals("/")) {
            return path;
        }
        StringBuilder route = new StringBuilder(path.length() + 32);
        int segmentStart = 0;
        int segmentIndex = 0;
        int braceDepth = 0;
        for (int i = 0; i <= path.length(); i++) {
            boolean end = i == path.length();
            char c = end ? '\0' : path.charAt(i);
            if (!end) {
                if (c == '{') {
                    braceDepth++;
                } else if (c == '}' && braceDepth > 0) {
                    braceDepth--;
                }
            }
            if (end || (c == '/' && braceDepth == 0)) {
                if (i > segmentStart) {
                    route.append(path, segmentStart, i);
                    appendMatrixParameterRoute(route, matrixParameterNames.get(Math.min(segmentIndex++, matrixParameterNames.size() - 1)));
                }
                if (!end) {
                    route.append(c);
                }
                segmentStart = i + 1;
            }
        }
        return route.toString();
    }

    private static void appendMatrixParameterRoute(StringBuilder route, String parameterName) {
        route.append('{')
            .append(parameterName)
            .append(MATRIX_PARAMETER_ROUTE_PATTERN)
            .append('}');
    }

    private List<Class<? extends Annotation>> getUnsupportedParameterAnnotations() {
        return Collections.singletonList(BeanParam.class);
    }

    @Override
    public void start(VisitorContext visitorContext) {
        for (Class<?> type : BINDABLE_TYPES) {
            visitorContext.getClassElement(type).ifPresent(bindable -> bindable.annotate(Bindable.class));
        }
    }
}
