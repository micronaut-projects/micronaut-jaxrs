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
package io.micronaut.jaxrs.processor.routes;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.uri.UriMatchTemplate;
import io.micronaut.http.uri.UriTemplateMatcher;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.inject.writer.GeneratedFile;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.Context;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Declares the routes of a JAX-RS root resource at compile time and implements them with handler
 * functions, instead of turning the resource into a controller. For a resource {@code Foo} it
 * generates:
 * <ul>
 *     <li>{@code Foo$JaxRsRoutes}, an enum of {@code RouteDeclaration}s, one per resource method,
 *     with the index keys computed here and a generated URL parser;</li>
 *     <li>{@code Foo$JaxRsRouter}, an {@code HttpRoutes} bean that binds a handler function to
 *     each constant. The handler reads the parameters of the resource method in generated code,
 *     calls the method directly, and converts its result to a response.</li>
 * </ul>
 * The runtime part, the conversion of parameters, the context objects, the response and the
 * container filters, is {@code io.micronaut.jaxrs.container.JaxRsRouteSupport}.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
public final class JaxRsRoutesGenerator {

    private static final Set<String> NO_BODY_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final String MN_QUERY_VALUE = "io.micronaut.http.annotation.QueryValue";
    private static final String MN_HEADER = "io.micronaut.http.annotation.Header";
    private static final String MN_PATH_VARIABLE = "io.micronaut.http.annotation.PathVariable";
    private static final String MN_COOKIE_VALUE = "io.micronaut.http.annotation.CookieValue";
    private static final String MN_BODY = "io.micronaut.http.annotation.Body";
    private static final String NAMED = "jakarta.inject.Named";
    private static final Set<String> CONTEXT_TYPES = Set.of(
        "jakarta.ws.rs.core.HttpHeaders",
        "jakarta.ws.rs.core.UriInfo",
        "jakarta.ws.rs.core.SecurityContext",
        "jakarta.ws.rs.core.Request",
        "jakarta.ws.rs.core.Application",
        "jakarta.ws.rs.core.Configuration",
        "jakarta.ws.rs.ext.Providers",
        "jakarta.ws.rs.container.ResourceContext",
        "jakarta.ws.rs.container.ResourceInfo",
        "jakarta.ws.rs.core.Cookie",
        "io.micronaut.http.HttpRequest",
        "jakarta.servlet.ServletContext",
        "jakarta.servlet.ServletRequest",
        "jakarta.servlet.http.HttpServletRequest",
        "jakarta.servlet.ServletResponse",
        "jakarta.servlet.http.HttpServletResponse",
        "jakarta.servlet.ServletConfig"
    );
    private static final String FORM_TYPES_MAP = "jakarta.ws.rs.core.MultivaluedMap";
    private static final String FORM_TYPE = "jakarta.ws.rs.core.Form";
    private static final String COMPLETION_STAGE = "java.util.concurrent.CompletionStage";
    private static final String SUPPORT = "io.micronaut.jaxrs.container.JaxRsRouteSupport";

    private JaxRsRoutesGenerator() {
    }

    /**
     * @param context The visitor context
     * @return Whether the compilation has the runtime of the generated routes: the JAX-RS server
     * and a router with declared routes
     */
    public static boolean isSupported(VisitorContext context) {
        return context.getClassElement(SUPPORT).isPresent()
            && context.getClassElement("io.micronaut.web.router.RouteDeclaration").isPresent();
    }

    /**
     * Generate the routes of a root resource.
     *
     * @param resource The resource class, annotated with {@code @Path}
     * @param context  The visitor context
     */
    public static void generate(ClassElement resource, VisitorContext context) {
        String classPath = resource.stringValue(Path.class).orElse("");
        List<ResourceMethod> methods = new ArrayList<>();
        for (MethodElement method : resource.getEnclosedElements(ElementQuery.ALL_METHODS.onlyInstance().onlyConcrete())) {
            if (!method.hasStereotype(HttpMethod.class)) {
                continue;
            }
            ResourceMethod resourceMethod = resourceMethod(resource, classPath, method, context);
            if (resourceMethod != null) {
                methods.add(resourceMethod);
            }
        }
        if (methods.isEmpty()) {
            return;
        }
        String simpleName = resource.getSimpleName();
        String routesName = simpleName + "$JaxRsRoutes";
        String routerName = simpleName + "$JaxRsRouter";
        CompiledRouteMatcherGenerator matcher = new CompiledRouteMatcherGenerator();
        for (int i = 0; i < methods.size(); i++) {
            ResourceMethod method = methods.get(i);
            matcher.add(i, method.httpMethod, method.template);
        }
        write(context, resource, routesName, routesSource(resource, routesName, methods, matcher));
        write(context, resource, routerName, routerSource(resource, routesName, routerName, methods));
    }

    private static @Nullable ResourceMethod resourceMethod(ClassElement resource, String classPath, MethodElement method, VisitorContext context) {
        if (method.isPrivate() || !method.isPublic() && !method.getDeclaringType().getPackageName().equals(resource.getPackageName())) {
            // the generated router, in the package of the resource, calls the method directly
            return null;
        }
        String httpMethod = method.stringValue(HttpMethod.class).orElse("").toUpperCase(Locale.ENGLISH);
        if (io.micronaut.http.HttpMethod.parse(httpMethod) == io.micronaut.http.HttpMethod.CUSTOM) {
            context.warn("JAX-RS resource method with the HTTP method " + httpMethod + " is not routed yet", method);
            return null;
        }
        AnnotationMetadata methodMetadata = method.getMethodAnnotationMetadata();
        String template = template(classPath, methodMetadata.stringValue(Path.class).orElse(""));
        List<Param> params = new ArrayList<>();
        boolean form = false;
        Param entity = null;
        for (ParameterElement parameter : method.getParameters()) {
            if (parameter.hasAnnotation(MatrixParam.class) || parameter.hasAnnotation(BeanParam.class)) {
                context.fail("Unsupported JAX-RS annotation used on method: "
                    + (parameter.hasAnnotation(MatrixParam.class) ? MatrixParam.class.getName() : BeanParam.class.getName()), parameter);
                return null;
            }
            if (parameter.hasAnnotation(Suspended.class)) {
                context.warn("JAX-RS asynchronous responses with @Suspended are not routed yet", method);
                return null;
            }
            String defaultValue = parameter.stringValue(DefaultValue.class).orElse(null);
            Param param;
            if (parameter.hasAnnotation(PathParam.class)) {
                param = new Param(ParamKind.PATH, parameter.stringValue(PathParam.class).orElse(parameter.getName()), parameter, defaultValue);
            } else if (parameter.hasAnnotation(QueryParam.class)) {
                param = new Param(ParamKind.QUERY, parameter.stringValue(QueryParam.class).orElse(parameter.getName()), parameter, defaultValue);
            } else if (parameter.hasAnnotation(HeaderParam.class)) {
                param = new Param(ParamKind.HEADER, parameter.stringValue(HeaderParam.class).orElse(parameter.getName()), parameter, defaultValue);
            } else if (parameter.hasAnnotation(CookieParam.class)) {
                param = new Param(ParamKind.COOKIE, parameter.stringValue(CookieParam.class).orElse(parameter.getName()), parameter, defaultValue);
            } else if (parameter.hasAnnotation(FormParam.class)) {
                String name = parameter.stringValue(FormParam.class).orElse(parameter.getName());
                if (NO_BODY_METHODS.contains(httpMethod)) {
                    // no form without a body: read from the query, like the controllers did
                    param = new Param(ParamKind.QUERY, name, parameter, defaultValue);
                } else {
                    param = new Param(ParamKind.FORM, name, parameter, defaultValue);
                    form = true;
                }
            } else if (parameter.hasAnnotation(Context.class) || CONTEXT_TYPES.contains(parameter.getType().getName())) {
                param = new Param(ParamKind.CONTEXT, parameter.stringValue(NAMED).orElse(null), parameter, null);
            } else if (parameter.hasDeclaredAnnotation(MN_QUERY_VALUE)) {
                param = new Param(ParamKind.QUERY, micronautName(parameter, MN_QUERY_VALUE), parameter, micronautDefault(parameter, MN_QUERY_VALUE, defaultValue));
            } else if (parameter.hasDeclaredAnnotation(MN_HEADER)) {
                param = new Param(ParamKind.HEADER, micronautName(parameter, MN_HEADER), parameter, micronautDefault(parameter, MN_HEADER, defaultValue));
            } else if (parameter.hasDeclaredAnnotation(MN_PATH_VARIABLE)) {
                param = new Param(ParamKind.PATH, micronautName(parameter, MN_PATH_VARIABLE), parameter, micronautDefault(parameter, MN_PATH_VARIABLE, defaultValue));
            } else if (parameter.hasDeclaredAnnotation(MN_COOKIE_VALUE)) {
                param = new Param(ParamKind.COOKIE, micronautName(parameter, MN_COOKIE_VALUE), parameter, micronautDefault(parameter, MN_COOKIE_VALUE, defaultValue));
            } else if (parameter.hasDeclaredAnnotation(MN_BODY) && parameter.stringValue(MN_BODY).isPresent()) {
                // a named @Body is a field of the form
                param = new Param(ParamKind.FORM, parameter.stringValue(MN_BODY).get(), parameter, defaultValue);
                form = true;
            } else {
                if (entity != null) {
                    context.fail("A JAX-RS resource method can have one entity parameter", parameter);
                    return null;
                }
                param = new Param(ParamKind.ENTITY, parameter.getName(), parameter, null);
                entity = param;
            }
            params.add(param);
        }
        if (form && entity != null) {
            String entityType = entity.parameter.getType().getName();
            if (!entityType.equals(FORM_TYPES_MAP) && !entityType.equals(FORM_TYPE)) {
                context.fail("The entity of a JAX-RS resource method with @FormParam parameters must be a form", entity.parameter);
                return null;
            }
            entity.kind = ParamKind.FORM_ENTITY;
        }
        ClassElement returnType = method.getGenericReturnType();
        boolean async = returnType.getName().equals(COMPLETION_STAGE) || returnType.isAssignable(COMPLETION_STAGE) && returnType.getName().startsWith("java.util.concurrent.");
        List<String> produces = mediaTypes(method, resource, Produces.class);
        List<String> consumes = mediaTypes(method, resource, Consumes.class);
        return new ResourceMethod(method, httpMethod, template, params, form, entity, returnType, async, produces, consumes);
    }

    private static String micronautName(ParameterElement parameter, String annotation) {
        return parameter.stringValue(annotation).filter(name -> !name.isEmpty())
            .or(() -> parameter.stringValue(annotation, "name").filter(name -> !name.isEmpty()))
            .orElse(parameter.getName());
    }

    private static @Nullable String micronautDefault(ParameterElement parameter, String annotation, @Nullable String defaultValue) {
        return defaultValue != null ? defaultValue : parameter.stringValue(annotation, "defaultValue").orElse(null);
    }

    private static List<String> mediaTypes(MethodElement method, ClassElement resource, Class<? extends java.lang.annotation.Annotation> annotation) {
        String[] values = method.getMethodAnnotationMetadata().stringValues(annotation);
        if (values.length == 0) {
            values = resource.stringValues(annotation);
        }
        List<String> mediaTypes = new ArrayList<>();
        for (String value : values) {
            for (String mediaType : value.split(",")) {
                String trimmed = mediaType.trim();
                if (!trimmed.isEmpty()) {
                    mediaTypes.add(trimmed);
                }
            }
        }
        return mediaTypes;
    }

    /**
     * The URI template of a resource method: the paths of the class and the method, joined by a
     * slash, with the spaces JAX-RS allows in a template variable removed.
     */
    static String template(String classPath, String methodPath) {
        StringBuilder template = new StringBuilder();
        for (String path : new String[]{classPath, methodPath}) {
            String trimmed = strip(normalizeVariables(path));
            if (!trimmed.isEmpty()) {
                template.append('/').append(trimmed);
            }
        }
        return template.isEmpty() ? "/" : template.toString();
    }

    private static String strip(String path) {
        int start = 0;
        int end = path.length();
        while (start < end && path.charAt(start) == '/') {
            start++;
        }
        while (end > start && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(start, end);
    }

    /**
     * {@code { name : regex }} to {@code {name:regex}}; braces of the regex are kept.
     */
    static String normalizeVariables(String path) {
        StringBuilder result = new StringBuilder(path.length());
        int i = 0;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c != '{') {
                result.append(c);
                i++;
                continue;
            }
            int depth = 1;
            int j = i + 1;
            while (j < path.length() && depth > 0) {
                char d = path.charAt(j);
                if (d == '{') {
                    depth++;
                } else if (d == '}') {
                    depth--;
                }
                j++;
            }
            String variable = path.substring(i + 1, depth == 0 ? j - 1 : j);
            int colon = variable.indexOf(':');
            result.append('{');
            if (colon < 0) {
                result.append(variable.trim());
            } else {
                result.append(variable.substring(0, colon).trim()).append(':').append(variable.substring(colon + 1).trim());
            }
            result.append('}');
            i = j;
        }
        return result.toString();
    }

    private static String routesSource(ClassElement resource, String routesName, List<ResourceMethod> methods, CompiledRouteMatcherGenerator matcher) {
        List<String> constants = new ArrayList<>();
        for (int i = 0; i < methods.size(); i++) {
            ResourceMethod method = methods.get(i);
            UriTemplateMatcher keys = new UriTemplateMatcher(new UriMatchTemplate(method.template).getTemplateString());
            constants.add("    " + constantName(method, i)
                + "(io.micronaut.http.HttpMethod." + method.httpMethod + ", " + literal(method.template) + ", " + literal(keys.getRequiredPrefix()) + ", "
                + keys.getRawLength() + ", " + keys.getPathVariableCount() + ")");
        }
        return """
            package %s;

            /**
             * The routes of the JAX-RS resource {@link %s}, declared at compile time.
             */
            public enum %s implements io.micronaut.web.router.RouteDeclaration {
            %s;

                private final io.micronaut.http.HttpMethod httpMethod;
                private final String uriTemplate;
                private final String requiredPathPrefix;
                private final int rawLength;
                private final int pathVariableCount;

                %s(io.micronaut.http.HttpMethod httpMethod, String uriTemplate, String requiredPathPrefix, int rawLength, int pathVariableCount) {
                    this.httpMethod = httpMethod;
                    this.uriTemplate = uriTemplate;
                    this.requiredPathPrefix = requiredPathPrefix;
                    this.rawLength = rawLength;
                    this.pathVariableCount = pathVariableCount;
                }

                @Override
                public io.micronaut.http.HttpMethod httpMethod() {
                    return httpMethod;
                }

                @Override
                public String uriTemplate() {
                    return uriTemplate;
                }

                @Override
                public String requiredPathPrefix() {
                    return requiredPathPrefix;
                }

                @Override
                public int rawLength() {
                    return rawLength;
                }

                @Override
                public int pathVariableCount() {
                    return pathVariableCount;
                }

                @Override
                public io.micronaut.web.router.CompiledRouteMatcher matcher() {
                    return Matcher.INSTANCE;
                }

            %s}
            """.formatted(resource.getPackageName(), resource.getCanonicalName(), routesName, String.join(",\n", constants), routesName, matcher.generate("Matcher"));
    }

    private static String routerSource(ClassElement resource, String routesName, String routerName, List<ResourceMethod> methods) {
        String resourceType = resource.getCanonicalName();
        StringBuilder fields = new StringBuilder();
        StringBuilder routes = new StringBuilder();
        for (int i = 0; i < methods.size(); i++) {
            ResourceMethod method = methods.get(i);
            List<String> arguments = new ArrayList<>();
            for (int j = 0; j < method.params.size(); j++) {
                Param param = method.params.get(j);
                String argumentField = "A" + i + "_" + j;
                String argument = argument(param.parameter.getGenericType());
                fields.append("    private static final io.micronaut.core.type.Argument ").append(argumentField).append(" = ")
                    .append(param.kind == ParamKind.ENTITY ? SUPPORT + ".nullable(" + argument + ")" : argument).append(";\n");
                arguments.add(argumentExpression(param, argumentField));
            }
            ClassElement valueType = method.async ? firstTypeArgument(method.returnType) : method.returnType;
            String returnField = "R" + i;
            fields.append("    private static final io.micronaut.core.type.Argument ").append(returnField).append(" = ")
                .append(valueType == null || valueType.isVoid() ? "io.micronaut.core.type.Argument.VOID" : argument(valueType)).append(";\n");
            String routeField = "ROUTE" + i;
            fields.append("    private static final ").append(SUPPORT).append(".RouteMetadata ").append(routeField).append(" = new ")
                .append(SUPPORT).append(".RouteMetadata(").append(resourceType).append(".class, ").append(literal(method.method.getName())).append(", ")
                .append(parameterTypes(method.method)).append(", ")
                .append(stringArray(method.produces)).append(", ").append(stringArray(method.consumes)).append(");\n");

            String call = "resource.get()." + method.method.getName() + "(" + String.join(", ", arguments) + ")";
            String declaration = "support.declaration(" + routesName + "." + constantName(method, i) + ")";
            String result;
            if (method.returnType.isVoid()) {
                result = call + ";\n                return support.response(request, null, " + returnField + ", " + routeField + ");";
            } else if (method.async) {
                result = "return support.responseAsync(request, " + call + ", " + returnField + ", " + routeField + ");";
            } else {
                result = "return support.response(request, " + call + ", " + returnField + ", " + routeField + ");";
            }
            if (throwsThrowable(method.method)) {
                // a lambda of a route handler can only throw exceptions
                result = "try {\n                    " + result + "\n                } catch (java.lang.Throwable t) {\n                    throw " + SUPPORT + ".rethrow(t);\n                }";
            }
            String handler;
            if (method.form) {
                handler = "routes.handleForm(" + declaration + ", support.handler(" + routeField + ", (request, pathVariables, form) -> {\n                " + syncResult(method, result) + "\n            }))";
            } else if (method.entity != null && method.httpMethod.equals("GET")) {
                handler = "routes.handle(" + declaration + ", support.handler(" + routeField + ", (request, pathVariables) -> {\n                Object body = null;\n                " + syncResult(method, result) + "\n            }))";
            } else if (method.entity != null) {
                String entityField = "A" + i + "_" + method.params.indexOf(method.entity);
                handler = "routes.handle(" + declaration + ", " + entityField + ", support.handler(" + routeField + ", (request, pathVariables, body) -> {\n                " + syncResult(method, result) + "\n            }))";
            } else if (method.async) {
                handler = "routes.handleAsync(" + declaration + ", support.handler(" + routeField + ", (request, pathVariables) -> {\n                " + result + "\n            }))";
            } else {
                handler = "routes.handle(" + declaration + ", support.handler(" + routeField + ", (request, pathVariables) -> {\n                " + result + "\n            }))";
            }
            routes.append("        support.configure(").append(handler).append(", ").append(routeField)
                .append(method.async && (method.form || method.entity != null) ? ", true" : ", false").append(");\n");
        }
        return """
            package %s;

            /**
             * Implements the routes of the JAX-RS resource {@link %s} with handler functions.
             */
            @jakarta.inject.Singleton
            @SuppressWarnings({"unchecked", "rawtypes"})
            public final class %s implements io.micronaut.web.router.HttpRoutes {

            %s
                private final io.micronaut.context.BeanProvider<%s> resource;
                private final %s support;

                public %s(io.micronaut.context.BeanProvider<%s> resource, %s support) {
                    this.resource = resource;
                    this.support = support;
                }

                @Override
                public void routes(io.micronaut.web.router.RouteBuilder routes) {
                    if (!resource.isPresent()) {
                        // the resource is not a bean in this context, e.g. disabled by @Requires
                        return;
                    }
            %s    }
            }
            """.formatted(resource.getPackageName(), resourceType, routerName, fields, resourceType, SUPPORT,
            routerName, resourceType, SUPPORT, routes);
    }

    /**
     * A resource method returning a {@code CompletionStage} with an entity or a form is routed by
     * a synchronous handler that waits for the stage on a blocking executor.
     */
    private static String syncResult(ResourceMethod method, String result) {
        if (!method.async) {
            return result;
        }
        return result.replace("support.responseAsync(", "support.responseAwait(");
    }

    private static String argumentExpression(Param param, String argumentField) {
        String cast = "(" + castType(param.parameter.getType()) + ") ";
        String defaultValue = param.defaultValue == null ? "null" : literal(param.defaultValue);
        String name = param.name == null ? "null" : literal(param.name);
        return switch (param.kind) {
            case PATH -> cast + "support.pathParam(request, pathVariables, " + name + ", " + argumentField + ", " + defaultValue + ")";
            case QUERY -> cast + "support.queryParam(request, " + name + ", " + argumentField + ", " + defaultValue + ")";
            case HEADER -> cast + "support.headerParam(request, " + name + ", " + argumentField + ", " + defaultValue + ")";
            case COOKIE -> cast + "support.cookieParam(request, " + name + ", " + argumentField + ", " + defaultValue + ")";
            case FORM -> cast + "support.formParam(form, " + name + ", " + argumentField + ", " + defaultValue + ")";
            case FORM_ENTITY -> cast + "support.formEntity(form, " + argumentField + ")";
            case CONTEXT -> cast + "support.context(request, " + argumentField + ", " + (param.name == null ? "null" : name) + ")";
            case ENTITY -> cast + "support.entity(body, " + argumentField + ")";
        };
    }

    private static boolean throwsThrowable(MethodElement method) {
        for (ClassElement thrown : method.getThrownTypes()) {
            if (!thrown.isAssignable(Exception.class)) {
                return true;
            }
        }
        return false;
    }

    private static String parameterTypes(MethodElement method) {
        List<String> types = new ArrayList<>();
        for (ParameterElement parameter : method.getParameters()) {
            types.add(castType(parameter.getType()) + ".class");
        }
        return types.isEmpty() ? "new Class<?>[0]" : "new Class<?>[]{" + String.join(", ", types) + "}";
    }

    private static String castType(ClassElement type) {
        if (type.isArray()) {
            return castType(type.fromArray()) + "[]".repeat(Math.max(1, type.getArrayDimensions()));
        }
        if (type.isPrimitive()) {
            return type.getName();
        }
        if (type.isGenericPlaceholder() || type.isWildcard()) {
            return "java.lang.Object";
        }
        return type.getCanonicalName();
    }

    /**
     * An expression creating the {@code Argument} of a type, with its type arguments.
     */
    private static String argument(ClassElement type) {
        if (type.isGenericPlaceholder() || type.isWildcard()) {
            return "io.micronaut.core.type.Argument.OBJECT_ARGUMENT";
        }
        String raw = castType(type) + ".class";
        Map<String, ClassElement> typeArguments = type.isArray() || type.isPrimitive() ? Map.of() : type.getTypeArguments();
        if (typeArguments.isEmpty()) {
            return "io.micronaut.core.type.Argument.of(" + raw + ")";
        }
        List<String> arguments = new ArrayList<>();
        for (ClassElement typeArgument : typeArguments.values()) {
            arguments.add(argument(typeArgument));
        }
        return "io.micronaut.core.type.Argument.of(" + raw + ", " + String.join(", ", arguments) + ")";
    }

    private static @Nullable ClassElement firstTypeArgument(ClassElement type) {
        Map<String, ClassElement> typeArguments = type.getTypeArguments();
        return typeArguments.isEmpty() ? null : typeArguments.values().iterator().next();
    }

    private static String constantName(ResourceMethod method, int index) {
        StringBuilder name = new StringBuilder();
        for (char c : method.method.getName().toCharArray()) {
            if (Character.isUpperCase(c) && !name.isEmpty()) {
                name.append('_');
            }
            name.append(Character.toUpperCase(c));
        }
        return name.append('_').append(index).toString();
    }

    private static String stringArray(List<String> values) {
        if (values.isEmpty()) {
            return "new String[0]";
        }
        List<String> literals = new ArrayList<>();
        for (String value : values) {
            literals.add(literal(value));
        }
        return "new String[]{" + String.join(", ", literals) + "}";
    }

    private static String literal(String value) {
        StringBuilder literal = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> literal.append("\\\"");
                case '$' -> literal.append("\\u0024");
                case '\\' -> literal.append("\\\\");
                case '\n' -> literal.append("\\n");
                case '\r' -> literal.append("\\r");
                case '\t' -> literal.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        literal.append(String.format("\\u%04x", (int) c));
                    } else {
                        literal.append(c);
                    }
                }
            }
        }
        return literal.append('"').toString();
    }

    private static void write(VisitorContext context, ClassElement resource, String name, String source) {
        GeneratedFile file = context.visitGeneratedSourceFile(resource.getPackageName(), name, resource)
            .orElseThrow(() -> new IllegalStateException("Cannot write the routes of " + resource.getName()));
        try (Writer writer = file.openWriter()) {
            writer.write(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private enum ParamKind {
        PATH, QUERY, HEADER, COOKIE, FORM, FORM_ENTITY, CONTEXT, ENTITY
    }

    private static final class Param {
        ParamKind kind;
        final @Nullable String name;
        final ParameterElement parameter;
        final @Nullable String defaultValue;

        Param(ParamKind kind, @Nullable String name, ParameterElement parameter, @Nullable String defaultValue) {
            this.kind = kind;
            this.name = name;
            this.parameter = parameter;
            this.defaultValue = defaultValue;
        }
    }

    private record ResourceMethod(MethodElement method,
                                  String httpMethod,
                                  String template,
                                  List<Param> params,
                                  boolean form,
                                  @Nullable Param entity,
                                  ClassElement returnType,
                                  boolean async,
                                  List<String> produces,
                                  List<String> consumes) {
    }
}
