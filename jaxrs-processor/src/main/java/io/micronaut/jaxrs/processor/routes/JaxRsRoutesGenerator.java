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
import io.micronaut.context.BeanProvider;
import io.micronaut.core.type.Argument;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.Encoded;
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

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Routes a JAX-RS resource with handler functions, instead of turning it into a controller. For a
 * resource {@code Foo} it generates {@code Foo$JaxRsRouter}, an {@code HttpRoutes} bean that adds
 * a route per resource method with the route builder. Its handler function reads the parameters
 * of the resource method in generated code, calls the method directly, and converts its result to
 * a response.
 * The runtime part, the conversion of parameters, the context objects, the response and the
 * container filters, is {@code io.micronaut.jaxrs.container.JaxRsRouteSupport}.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
public final class JaxRsRoutesGenerator {

    private static final Set<String> NO_BODY_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
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
    private static final String ROUTER = "io.micronaut.web.router.builder.";
    private static final ClassTypeDef ARGUMENT = ClassTypeDef.of(Argument.class);
    private static final ClassTypeDef HTTP_METHOD = ClassTypeDef.of(io.micronaut.http.HttpMethod.class);

    private JaxRsRoutesGenerator() {
    }

    /**
     * @param context The visitor context
     * @return Whether the compilation has the runtime of the generated routes: the JAX-RS server
     * and a router with declared routes
     */
    public static boolean isSupported(VisitorContext context) {
        return context.getClassElement(SUPPORT).isPresent()
            && context.getClassElement(ROUTER + "RequestHandler").isPresent();
    }

    /**
     * Generate the routes of a root resource.
     *
     * @param resource The resource class, annotated with {@code @Path}
     * @param context  The visitor context
     */
    public static void generate(ClassElement resource, VisitorContext context) {
        List<Param> constructorParams = new ArrayList<>();
        ConstructorElement constructor = requestConstructor(resource);
        if (constructor != null) {
            for (ParameterElement parameter : constructor.getParameters()) {
                if (parameter.hasAnnotation(BeanParam.class) || parameter.hasAnnotation(Encoded.class)) {
                    context.info("JAX-RS resource with unsupported constructor parameters is not routed", parameter);
                    return;
                }
                Param param = constructorParam(parameter);
                if (param != null) {
                    constructorParams.add(param);
                }
            }
        }
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
        String routerName = simpleName + "$JaxRsRouter";
        ClassDef router = router(resource, routerName, methods, constructor == null ? null : constructorParams, context);
        SourceGenerators.findByLanguage(VisitorContext.Language.JAVA)
            .orElseThrow(() -> new IllegalStateException("No Java source generator"))
            .write(router, context, resource);
    }

    /**
     * The constructor JAX-RS uses for a resource created per request: the public constructor with
     * the most parameters, when it has parameters read from the request.
     *
     * @param resource The resource class
     * @return The constructor, or {@code null} if the resource is a singleton
     */
    public static @Nullable ConstructorElement requestConstructor(ClassElement resource) {
        ConstructorElement selected = null;
        for (ConstructorElement constructor : resource.getEnclosedElements(ElementQuery.CONSTRUCTORS)) {
            if (constructor.isPublic() && (selected == null || constructor.getParameters().length > selected.getParameters().length)) {
                selected = constructor;
            }
        }
        if (selected == null) {
            return null;
        }
        for (ParameterElement parameter : selected.getParameters()) {
            if (isRequestParameter(parameter)) {
                return selected;
            }
        }
        return null;
    }

    /**
     * @param parameter A parameter of a resource constructor
     * @return Whether its value is read from the request: annotated with a JAX-RS parameter
     * annotation or {@code @Context}
     */
    public static boolean isRequestParameter(ParameterElement parameter) {
        return parameter.hasAnnotation(PathParam.class) || parameter.hasAnnotation(QueryParam.class)
            || parameter.hasAnnotation(HeaderParam.class) || parameter.hasAnnotation(CookieParam.class)
            || parameter.hasAnnotation(FormParam.class) || parameter.hasAnnotation(MatrixParam.class)
            || parameter.hasAnnotation(BeanParam.class) || parameter.hasAnnotation(Context.class);
    }

    private static @Nullable Param constructorParam(ParameterElement parameter) {
        String defaultValue = parameter.stringValue(DefaultValue.class).orElse(null);
        if (parameter.hasAnnotation(PathParam.class)) {
            return new Param(ParamKind.PATH, parameter.stringValue(PathParam.class).orElse(parameter.getName()), parameter, defaultValue);
        } else if (parameter.hasAnnotation(QueryParam.class)) {
            return new Param(ParamKind.QUERY, parameter.stringValue(QueryParam.class).orElse(parameter.getName()), parameter, defaultValue);
        } else if (parameter.hasAnnotation(MatrixParam.class)) {
            return new Param(ParamKind.MATRIX, parameter.stringValue(MatrixParam.class).orElse(parameter.getName()), parameter, defaultValue);
        } else if (parameter.hasAnnotation(HeaderParam.class)) {
            return new Param(ParamKind.HEADER, parameter.stringValue(HeaderParam.class).orElse(parameter.getName()), parameter, defaultValue);
        } else if (parameter.hasAnnotation(CookieParam.class)) {
            return new Param(ParamKind.COOKIE, parameter.stringValue(CookieParam.class).orElse(parameter.getName()), parameter, defaultValue);
        } else if (parameter.hasAnnotation(FormParam.class)) {
            return new Param(ParamKind.FORM, parameter.stringValue(FormParam.class).orElse(parameter.getName()), parameter, defaultValue);
        } else if (parameter.hasAnnotation(Context.class)) {
            return new Param(ParamKind.CONTEXT, parameter.stringValue(NAMED).orElse(null), parameter, null);
        }
        // not from the request: injected like for any bean
        return null;
    }

    private static @Nullable ResourceMethod resourceMethod(ClassElement resource, String classPath, MethodElement method, VisitorContext context) {
        if (method.isPrivate() || !method.isPublic() && !method.getDeclaringType().getPackageName().equals(resource.getPackageName())) {
            // the generated router, in the package of the resource, calls the method directly
            return null;
        }
        String httpMethod = method.stringValue(HttpMethod.class).orElse("").toUpperCase(Locale.ENGLISH);
        boolean custom = io.micronaut.http.HttpMethod.parse(httpMethod) == io.micronaut.http.HttpMethod.CUSTOM;
        AnnotationMetadata methodMetadata = method.getMethodAnnotationMetadata();
        String template = template(classPath, methodMetadata.stringValue(Path.class).orElse(""));
        List<Param> params = new ArrayList<>();
        boolean form = false;
        Param entity = null;
        for (ParameterElement parameter : method.getParameters()) {
            if (parameter.hasAnnotation(BeanParam.class)) {
                context.fail("Unsupported JAX-RS annotation used on method: " + BeanParam.class.getName(), parameter);
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
            } else if (parameter.hasAnnotation(MatrixParam.class)) {
                param = new Param(ParamKind.MATRIX, parameter.stringValue(MatrixParam.class).orElse(parameter.getName()), parameter, defaultValue);
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
        if (form && custom) {
            context.warn("JAX-RS resource method with a form and the HTTP method " + httpMethod + " is not routed yet", method);
            return null;
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
        return new ResourceMethod(method, httpMethod, template, params, form, entity, returnType, async, custom, produces, consumes);
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

    /**
     * The model of the router of a resource: an {@code HttpRoutes} bean with a route per resource
     * method.
     */
    private static ClassDef router(ClassElement resource, String routerName, List<ResourceMethod> methods,
                                   @Nullable List<Param> constructorParams, VisitorContext context) {
        ClassTypeDef routerType = ClassTypeDef.of(resource.getPackageName() + "." + routerName);
        ClassTypeDef resourceType = ClassTypeDef.erasure(resource);
        ClassTypeDef supportType = type(context, SUPPORT);
        ClassTypeDef metadataType = type(context, SUPPORT + ".RouteMetadata");
        ClassTypeDef routeBuilderType = type(context, ROUTER + "HttpRouteBuilder");
        ClassTypeDef uriRouteType = type(context, ROUTER + "HttpRouteSpec");

        ClassDef.ClassDefBuilder router = ClassDef.builder(routerType.getName())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addAnnotation(ClassTypeDef.of("jakarta.inject.Singleton"))
            .addSuperinterface(type(context, ROUTER + "HttpRoutes"))
            .addJavadoc("Implements the routes of the JAX-RS resource {@link " + resource.getCanonicalName() + "} with handler functions.");

        FieldDef resourceField = FieldDef.builder("resource", TypeDef.parameterized(ClassTypeDef.of(BeanProvider.class), resourceType))
            .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
            .build();
        FieldDef supportField = FieldDef.builder("support", supportType)
            .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
            .build();

        // the constants of the constructor parameters read from the request
        List<VariableDef.StaticField> constructorArguments = new ArrayList<>();
        boolean constructorForm = false;
        if (constructorParams != null) {
            for (int j = 0; j < constructorParams.size(); j++) {
                Param param = constructorParams.get(j);
                constructorArguments.add(constant(router, routerType, "C" + j, ARGUMENT, argument(param.parameter.getGenericType())));
                constructorForm |= param.kind == ParamKind.FORM;
            }
        }

        List<RouteModel> routes = new ArrayList<>();
        for (int i = 0; i < methods.size(); i++) {
            ResourceMethod method = methods.get(i);
            List<VariableDef.StaticField> arguments = new ArrayList<>();
            for (int j = 0; j < method.params.size(); j++) {
                Param param = method.params.get(j);
                ExpressionDef argument = argument(param.parameter.getGenericType());
                if (param.kind == ParamKind.ENTITY) {
                    // an entity is optional
                    argument = routeBuilderType.invokeStatic("nullableBody", ARGUMENT, argument);
                }
                arguments.add(constant(router, routerType, "A" + i + "_" + j, ARGUMENT, argument));
            }
            ClassElement valueType = method.async ? firstTypeArgument(method.returnType) : method.returnType;
            VariableDef.StaticField returnType = constant(router, routerType, "R" + i, ARGUMENT,
                valueType == null || valueType.isVoid() ? ARGUMENT.getStaticField("VOID", ARGUMENT) : argument(valueType));
            VariableDef.StaticField metadata = constant(router, routerType, "ROUTE" + i, metadataType, metadataType.instantiate(
                ExpressionDef.constant(resourceType),
                ExpressionDef.constant(method.method.getName()),
                TypeDef.CLASS.array().instantiate(Arrays.stream(method.method.getParameters())
                    .map(p -> (ExpressionDef) ExpressionDef.constant(TypeDef.erasure(p.getType()))).toList()),
                strings(method.produces),
                strings(method.consumes)
            ));
            // a form is read for the constructor when the method can have one and reads no entity
            boolean form = method.form || constructorForm && !NO_BODY_METHODS.contains(method.httpMethod) && method.entity == null;
            routes.add(new RouteModel("route" + i, method, arguments, returnType, metadata, form));
        }

        router.addField(resourceField);
        router.addField(supportField);
        router.addMethod(MethodDef.constructor()
            .addModifiers(Modifier.PUBLIC)
            .addParameter("resource", resourceField.getType())
            .addParameter("support", supportType)
            .build((aThis, params) -> StatementDef.multi(
                aThis.field(resourceField).assign(params.get(0)),
                aThis.field(supportField).assign(params.get(1))
            )));
        for (RouteModel route : routes) {
            router.addMethod(routeMethod(resourceType, supportType, resourceField, supportField, route, constructorParams, constructorArguments, context));
        }
        router.addMethod(MethodDef.builder("routes")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(Override.class)
            .addParameter("routes", routeBuilderType)
            .returns(TypeDef.VOID)
            .build((aThis, params) -> {
                VariableDef support = aThis.field(supportField);
                List<StatementDef> statements = new ArrayList<>();
                for (RouteModel route : routes) {
                    ExpressionDef handler = handlerLambda(aThis, route, context);
                    List<ExpressionDef> handle = new ArrayList<>();
                    // a custom HTTP method is routed by its name
                    handle.add(route.method.custom
                        ? ExpressionDef.constant(route.method.httpMethod)
                        : HTTP_METHOD.getStaticField(route.method.httpMethod, HTTP_METHOD));
                    handle.add(support.invoke("uri", TypeDef.STRING, ExpressionDef.constant(route.method.template)));
                    String builderMethod;
                    if (route.form) {
                        builderMethod = "handleForm";
                    } else if (route.method.entity != null && !route.method.httpMethod.equals("GET")) {
                        builderMethod = route.method.async ? "handleAsync" : "handle";
                        handle.add(route.arguments.get(route.method.params.indexOf(route.method.entity)));
                    } else if (route.method.async) {
                        builderMethod = "handleAsync";
                    } else {
                        builderMethod = "handle";
                    }
                    handle.add(handler);
                    statements.add(support.invoke("configure", TypeDef.VOID,
                        params.get(0).invoke(builderMethod, uriRouteType, handle),
                        route.metadata));
                }
                // the resource is not a bean in this context, e.g. disabled by @Requires
                return aThis.field(resourceField).invoke("isPresent", TypeDef.Primitive.BOOLEAN).ifTrue(StatementDef.multi(statements));
            }));
        return router.build();
    }

    /**
     * The interface of the handler function of a route, and its type variables.
     */
    private static String handlerType(RouteModel route) {
        ResourceMethod method = route.method;
        boolean entity = method.entity != null && !method.httpMethod.equals("GET");
        if (route.form) {
            return method.async ? "AsyncFormRequestHandler" : "FormRequestHandler";
        } else if (entity) {
            return method.async ? "AsyncBodyRequestHandler" : "BodyRequestHandler";
        } else if (method.async) {
            return "AsyncRequestHandler";
        }
        return "RequestHandler";
    }

    /**
     * The handler function of a route: calls the method of the route.
     */
    private static ExpressionDef handlerLambda(VariableDef.This aThis, RouteModel route, VisitorContext context) {
        String handlerType = handlerType(route);
        Map<String, TypeDef> typeVariables = handlerType.endsWith("BodyRequestHandler") ? Map.of("B", TypeDef.OBJECT) : Map.of();
        return type(context, ROUTER + handlerType).getLambda(typeVariables).implement((lambdaThis, lambdaParams) ->
            aThis.invoke(route.name, TypeDef.OBJECT, new ArrayList<ExpressionDef>(lambdaParams)).returning());
    }

    /**
     * The method of a route: reads the parameters, calls the resource method and returns the
     * response.
     */
    private static MethodDef routeMethod(ClassTypeDef resourceType, ClassTypeDef supportType,
                                         FieldDef resourceField, FieldDef supportField, RouteModel route,
                                         @Nullable List<Param> constructorParams, List<VariableDef.StaticField> constructorArguments,
                                         VisitorContext context) {
        ResourceMethod method = route.method;
        String handlerType = handlerType(route);
        boolean async = handlerType.startsWith("Async");
        boolean formRoute = handlerType.endsWith("FormRequestHandler");
        boolean bodyRoute = handlerType.endsWith("BodyRequestHandler");
        MethodDef.MethodDefBuilder builder = MethodDef.builder(route.name)
            .addModifiers(Modifier.PRIVATE)
            .addParameter("request", TypeDef.parameterized(ClassTypeDef.of("io.micronaut.http.HttpRequest"), TypeDef.wildcard()))
            .addParameter("pathVariables", type(context, ROUTER + "PathVariables"));
        if (formRoute) {
            builder.addParameter("form", type(context, "io.micronaut.http.form.FormData"));
        } else if (bodyRoute) {
            builder.addParameter("body", TypeDef.OBJECT);
        }
        TypeDef response = TypeDef.parameterized(ClassTypeDef.of("io.micronaut.http.HttpResponse"), TypeDef.wildcard());
        builder.returns(async ? TypeDef.parameterized(ClassTypeDef.of(java.util.concurrent.CompletionStage.class), TypeDef.wildcardSubtypeOf(response)) : response);
        builder.addThrows(ClassTypeDef.of(Exception.class));
        return builder.build((aThis, params) -> {
            VariableDef request = params.get(0);
            VariableDef pathVariables = params.get(1);
            @Nullable VariableDef third = params.size() > 2 ? params.get(2) : null;
            VariableDef support = aThis.field(supportField);
            HandlerScope scope = new HandlerScope(support, request, pathVariables,
                formRoute ? third : null,
                bodyRoute ? third : null);

            ExpressionDef instance;
            if (constructorParams == null) {
                instance = aThis.field(resourceField).invoke("get", TypeDef.OBJECT).cast(resourceType);
            } else {
                // created per request, with the values of the request as its @Parameters
                List<ExpressionDef> names = new ArrayList<>();
                List<ExpressionDef> values = new ArrayList<>();
                for (int j = 0; j < constructorParams.size(); j++) {
                    Param param = constructorParams.get(j);
                    Param source = param.kind == ParamKind.FORM && !route.form
                        ? new Param(ParamKind.QUERY, param.name, param.parameter, param.defaultValue)
                        : param;
                    names.add(ExpressionDef.constant(param.parameter.getName()));
                    values.add(value(source, constructorArguments.get(j), scope));
                }
                instance = support.invoke("create", TypeDef.OBJECT,
                    ExpressionDef.constant(resourceType),
                    TypeDef.STRING.array().instantiate(names),
                    TypeDef.OBJECT.array().instantiate(values)
                ).cast(resourceType);
            }
            List<ExpressionDef> arguments = new ArrayList<>();
            for (int j = 0; j < method.params.size(); j++) {
                arguments.add(value(method.params.get(j), route.arguments.get(j), scope));
            }
            ExpressionDef call = instance.invoke(method.method, arguments);
            StatementDef result;
            if (method.returnType.isVoid()) {
                result = StatementDef.multi(
                    (StatementDef) call,
                    support.invoke("response", TypeDef.OBJECT, request, ExpressionDef.nullValue(), route.returnType, route.metadata).returning()
                );
            } else if (method.async) {
                result = support.invoke("responseAsync", TypeDef.OBJECT, request, call, route.returnType, route.metadata).returning();
            } else {
                result = support.invoke("response", TypeDef.OBJECT, request, call, route.returnType, route.metadata).returning();
            }
            if (throwsThrowable(method.method)) {
                // a route handler can only throw exceptions
                result = StatementDef.doTry(result).doCatch(Throwable.class, throwable ->
                    supportType.invokeStatic("rethrow", ClassTypeDef.of(RuntimeException.class), throwable).doThrow());
            }
            return result;
        });
    }

    /**
     * The value of a parameter, read from the request and converted.
     */
    private static ExpressionDef value(Param param, VariableDef.StaticField argument, HandlerScope scope) {
        ExpressionDef defaultValue = param.defaultValue == null ? ExpressionDef.nullValue() : ExpressionDef.constant(param.defaultValue);
        ExpressionDef name = param.name == null ? ExpressionDef.nullValue() : ExpressionDef.constant(param.name);
        VariableDef support = scope.support;
        ExpressionDef value = switch (param.kind) {
            case PATH -> support.invoke("pathParam", TypeDef.OBJECT, scope.request, scope.pathVariables, name, argument, defaultValue);
            case QUERY -> support.invoke("queryParam", TypeDef.OBJECT, scope.request, name, argument, defaultValue);
            case MATRIX -> support.invoke("matrixParam", TypeDef.OBJECT, scope.request, name, argument, defaultValue);
            case HEADER -> support.invoke("headerParam", TypeDef.OBJECT, scope.request, name, argument, defaultValue);
            case COOKIE -> support.invoke("cookieParam", TypeDef.OBJECT, scope.request, name, argument, defaultValue);
            case FORM -> support.invoke("formParam", TypeDef.OBJECT, Objects.requireNonNull(scope.form, "form"), name, argument, defaultValue);
            case FORM_ENTITY -> support.invoke("formEntity", TypeDef.OBJECT, Objects.requireNonNull(scope.form, "form"), argument);
            case CONTEXT -> support.invoke("context", TypeDef.OBJECT, scope.request, argument, name);
            case ENTITY -> support.invoke("entity", TypeDef.OBJECT, scope.body == null ? ExpressionDef.nullValue() : scope.body, argument);
        };
        return value.cast(TypeDef.erasure(param.parameter.getType()));
    }

    /**
     * A {@code private static final} constant of the router.
     */
    private static VariableDef.StaticField constant(ClassDef.ClassDefBuilder router, ClassTypeDef routerType, String name, ClassTypeDef type, ExpressionDef value) {
        FieldDef field = FieldDef.builder(name, type)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer(value)
            .build();
        router.addField(field);
        return routerType.getStaticField(field);
    }

    /**
     * An expression creating the {@code Argument} of a type, with its type arguments.
     */
    private static ExpressionDef argument(ClassElement type) {
        if (type.isGenericPlaceholder() || type.isWildcard()) {
            return ARGUMENT.getStaticField("OBJECT_ARGUMENT", ARGUMENT);
        }
        List<ExpressionDef> values = new ArrayList<>();
        values.add(ExpressionDef.constant(TypeDef.erasure(type)));
        if (!type.isArray() && !type.isPrimitive()) {
            for (ClassElement typeArgument : type.getTypeArguments().values()) {
                values.add(argument(typeArgument));
            }
        }
        return ARGUMENT.invokeStatic("of", ARGUMENT, values);
    }

    private static ExpressionDef strings(List<String> values) {
        return TypeDef.STRING.array().instantiate(values.stream().map(v -> (ExpressionDef) ExpressionDef.constant(v)).toList());
    }

    private static ClassTypeDef type(VisitorContext context, String name) {
        return ClassTypeDef.erasure(context.getClassElement(name)
            .orElseThrow(() -> new IllegalStateException("The type " + name + " is not on the classpath of the compilation")));
    }

    private static boolean throwsThrowable(MethodElement method) {
        for (ClassElement thrown : method.getThrownTypes()) {
            if (!thrown.isAssignable(Exception.class)) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable ClassElement firstTypeArgument(ClassElement type) {
        Map<String, ClassElement> typeArguments = type.getTypeArguments();
        return typeArguments.isEmpty() ? null : typeArguments.values().iterator().next();
    }

    /**
     * What a route method needs to read a value.
     *
     * @param support       The route support field
     * @param request       The request parameter
     * @param pathVariables The path variables parameter
     * @param form          The form parameter of a form route
     * @param body          The body parameter of a route with an entity
     */
    private record HandlerScope(VariableDef support, VariableDef request, VariableDef pathVariables,
                                @Nullable VariableDef form, @Nullable VariableDef body) {
    }

    /**
     * A route of the router, with its constants.
     *
     * @param name       The name of the route method
     * @param method     The resource method
     * @param arguments  The argument constants of its parameters
     * @param returnType The argument constant of its result
     * @param metadata   The route metadata constant
     * @param form       Whether the route reads a form
     */
    private record RouteModel(String name, ResourceMethod method, List<VariableDef.StaticField> arguments,
                              VariableDef.StaticField returnType, VariableDef.StaticField metadata, boolean form) {
    }

    private enum ParamKind {
        PATH, QUERY, MATRIX, HEADER, COOKIE, FORM, FORM_ENTITY, CONTEXT, ENTITY
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
                                  boolean custom,
                                  List<String> produces,
                                  List<String> consumes) {
    }
}
