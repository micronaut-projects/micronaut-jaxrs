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

import io.micronaut.context.BeanProvider;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.TypedElement;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Routes a JAX-RS resource with handler functions, instead of turning it into a controller. For a
 * resource {@code Foo} it generates {@code Foo$JaxRsRouter}, an {@code HttpRoutes} bean that adds
 * a route per resource method with the route builder. The method of a route reads the parameters
 * of the resource method in generated code, calls the method directly, and converts its result to
 * a response, the conversion selected by the declared return type.
 *
 * <p>Sub-resources are flattened at compile time: a sub-resource locator, a method with
 * {@code @Path} and no HTTP method, is followed to the type it returns, and every resource method
 * of that type gets a route with the paths joined. The route calls the locators in order.</p>
 *
 * <p>A resource whose constructor, fields or setters read values of the request is created for
 * every request, like a type a locator returns as a {@code Class}, and a {@code @BeanParam}: the
 * router has a method per such type that creates and injects an instance.</p>
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
    private static final String JAX_RS_RESPONSE = "jakarta.ws.rs.core.Response";
    private static final String HTTP_RESPONSE = "io.micronaut.http.HttpResponse";
    private static final String SUPPORT = "io.micronaut.jaxrs.container.JaxRsRouteSupport";
    private static final String ROUTER = "io.micronaut.web.router.builder.";
    private static final String ASYNC_HANDLER = "io.micronaut.jaxrs.container.JaxRsAsyncHandler";
    private static final ClassTypeDef ARGUMENT = ClassTypeDef.of(Argument.class);
    private static final ClassTypeDef HTTP_METHOD = ClassTypeDef.of(io.micronaut.http.HttpMethod.class);
    /**
     * How many times a type can appear in a chain of sub-resource locators: a locator returning
     * its own type is routed to this depth.
     */
    private static final int MAX_LOCATOR_REPEAT = 2;
    /**
     * See {@code JaxRsRouteTemplateEngine#ROOT_MARK}.
     */
    private static final char ROOT_MARK = '\u001E';
    /**
     * See {@code JaxRsRouteTemplateEngine#LOCATOR_MARK}.
     */
    private static final char LOCATOR_MARK = '\u001F';
    private static final String GENERIC_ENTITY = "jakarta.ws.rs.core.GenericEntity";
    private static final String LOCATED_ROUTES = "io.micronaut.jaxrs.container.JaxRsLocatedRoutes";
    private static final Map<String, String> BUILT_IN_ARGUMENTS = Map.ofEntries(
        Map.entry("java.lang.String", "STRING"),
        Map.entry("int", "INT"),
        Map.entry("long", "LONG"),
        Map.entry("float", "FLOAT"),
        Map.entry("double", "DOUBLE"),
        Map.entry("byte", "BYTE"),
        Map.entry("boolean", "BOOLEAN"),
        Map.entry("char", "CHAR"),
        Map.entry("short", "SHORT"),
        Map.entry("void", "VOID"),
        Map.entry("java.lang.Void", "VOID_OBJECT"),
        Map.entry("java.lang.Object", "OBJECT_ARGUMENT"),
        Map.entry("java.util.List<java.lang.String>", "LIST_OF_STRING")
    );
    private static final List<Class<? extends java.lang.annotation.Annotation>> REQUEST_ANNOTATIONS = List.of(
        PathParam.class, QueryParam.class, MatrixParam.class, HeaderParam.class, CookieParam.class,
        FormParam.class, BeanParam.class, Context.class
    );

    private JaxRsRoutesGenerator() {
    }

    /**
     * @param context The visitor context
     * @return Whether the compilation has the runtime of the generated routes: the JAX-RS server
     * and the route builder
     */
    public static boolean isSupported(VisitorContext context) {
        return context.getClassElement(SUPPORT).isPresent()
            && context.getClassElement(ROUTER + "RequestHandler").isPresent();
    }

    /**
     * Generate the routes of a resource.
     *
     * @param resource The resource class
     * @param context  The visitor context
     */
    public static void generate(ClassElement resource, VisitorContext context) {
        Model model = new Model(resource, context);
        RequestType root = null;
        if (isPerRequest(resource)) {
            root = model.requestType(resource);
            if (root == null) {
                return;
            }
        }
        Map<String, Integer> visited = new LinkedHashMap<>();
        visited.put(resource.getName(), 1);
        model.rootSegments = segments(resource.stringValue(Path.class).orElse(""));
        String rootPath = resource.stringValue(Path.class).orElse("");
        // the root resource class is selected first by the specificity of its @Path: the end of
        // it is marked in the templates, for the JAX-RS route template engine
        model.collect(resource, strip(rootPath).isEmpty() ? rootPath : strip(rootPath) + ROOT_MARK, List.of(), visited, true);
        if (model.routes.isEmpty() && model.runtimeLocators.isEmpty()) {
            return;
        }
        ClassDef router = router(model, root);
        SourceGenerators.findByLanguage(VisitorContext.Language.JAVA)
            .orElseThrow(() -> new IllegalStateException("No Java source generator"))
            .write(router, context, resource);
    }

    /**
     * Generate the routes of a class as the target of a sub-resource locator that is only known
     * at runtime: the routes relative to the prefix of the locator, on the instance it located.
     *
     * @param type    The class
     * @param context The visitor context
     */
    public static void generateLocated(ClassElement type, VisitorContext context) {
        Model model = new Model(type, context);
        model.located = true;
        Map<String, Integer> visited = new LinkedHashMap<>();
        visited.put(type.getName(), 1);
        model.collect(type, "", List.of(), visited, false);
        if (model.routes.isEmpty() && model.runtimeLocators.isEmpty()) {
            return;
        }
        SourceGenerators.findByLanguage(VisitorContext.Language.JAVA)
            .orElseThrow(() -> new IllegalStateException("No Java source generator"))
            .write(router(model, null), context, type);
    }

    /**
     * @param type A resource class
     * @return Whether it is created for every request: its constructor, fields or setters read
     * values of the request
     */
    public static boolean isPerRequest(ClassElement type) {
        return requestConstructor(type) != null || !requestMembers(type).isEmpty();
    }

    /**
     * @param type A class
     * @return Whether the generated routes create it for every request and fill its members: a
     * resource or sub-resource, with values of the request in its constructor, fields or setters.
     * Other classes with {@code @Context} members, an {@code Application} or a provider, are beans
     * whose members are injected with proxies of the current request.
     */
    public static boolean isCreatedPerRequest(ClassElement type) {
        if (!isPerRequest(type)) {
            return false;
        }
        return type.hasAnnotation(Path.class) || !type.getEnclosedElements(ElementQuery.ALL_METHODS.onlyInstance()
            .annotated(metadata -> metadata.hasStereotype(HttpMethod.class) || metadata.hasDeclaredAnnotation(Path.class))).isEmpty();
    }

    /**
     * The constructor JAX-RS uses for an instance created per request: the public constructor
     * with the most parameters, when it has parameters read from the request.
     *
     * @param type The class
     * @return The constructor, or {@code null}
     */
    public static @Nullable ConstructorElement requestConstructor(ClassElement type) {
        ConstructorElement selected = null;
        for (ConstructorElement constructor : type.getEnclosedElements(ElementQuery.CONSTRUCTORS)) {
            if (constructor.isPublic() && (selected == null || constructor.getParameters().length > selected.getParameters().length)) {
                selected = constructor;
            }
        }
        if (selected == null) {
            return null;
        }
        for (ParameterElement parameter : selected.getParameters()) {
            if (isRequestAnnotated(parameter)) {
                return selected;
            }
        }
        return null;
    }

    /**
     * @param element A parameter, field or setter
     * @return Whether its value is read from the request: annotated with a JAX-RS parameter
     * annotation or {@code @Context}
     */
    public static boolean isRequestAnnotated(Element element) {
        for (Class<? extends java.lang.annotation.Annotation> annotation : REQUEST_ANNOTATIONS) {
            if (element.hasAnnotation(annotation)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The fields and setters of a class that read values of the request.
     *
     * @param type The class
     * @return The fields and setters
     */
    public static List<MemberElement> requestMembers(ClassElement type) {
        List<MemberElement> members = new ArrayList<>();
        for (FieldElement field : type.getEnclosedElements(ElementQuery.ALL_FIELDS.onlyInstance())) {
            if (isRequestAnnotated(field)) {
                members.add(field);
            }
        }
        for (MethodElement method : type.getEnclosedElements(ElementQuery.ALL_METHODS.onlyInstance())) {
            if (method.getParameters().length == 1 && !method.hasStereotype(HttpMethod.class)
                && !method.getMethodAnnotationMetadata().hasAnnotation(Path.class) && isRequestAnnotated(method)) {
                members.add(method);
            }
        }
        return members;
    }

    /**
     * What the generator collects for a resource: its routes, and the types it creates per
     * request.
     */
    private static final class Model {
        final ClassElement resource;
        final VisitorContext context;
        final List<Route> routes = new ArrayList<>();
        final Map<String, RequestType> requestTypes = new LinkedHashMap<>();
        final List<RuntimeLocator> runtimeLocators = new ArrayList<>();
        int rootSegments;
        boolean located;

        Model(ClassElement resource, VisitorContext context) {
            this.resource = resource;
            this.context = context;
        }

        /**
         * The routes of the resource methods of a type, and of the sub-resources of its locators.
         */
        void collect(ClassElement type, String path, List<Locator> locators, Map<String, Integer> visited, boolean root) {
            ElementQuery<MethodElement> query = ElementQuery.ALL_METHODS.onlyInstance();
            if (root) {
                query = query.onlyConcrete();
            }
            for (MethodElement method : type.getEnclosedElements(query)) {
                if (!accessible(method)) {
                    continue;
                }
                AnnotationMetadata methodMetadata = method.getMethodAnnotationMetadata();
                if (method.hasStereotype(HttpMethod.class)) {
                    String template = template(path, methodMetadata.stringValue(Path.class).orElse(""));
                    ResourceMethod resourceMethod = resourceMethod(type, template, method);
                    if (resourceMethod != null) {
                        routes.add(new Route(resourceMethod, locators, type));
                    }
                } else if (methodMetadata.hasAnnotation(Path.class)) {
                    // a resource method is selected before a locator: the end of its @Path is marked
                    locator(method, template(path, methodMetadata.stringValue(Path.class).orElse("")) + LOCATOR_MARK, locators, visited);
                }
            }
        }

        /**
         * Follow a sub-resource locator to the type it returns.
         */
        private void locator(MethodElement method, String path, List<Locator> locators, Map<String, Integer> visited) {
            List<Param> params = new ArrayList<>();
            for (ParameterElement parameter : method.getParameters()) {
                Param param = isRequestAnnotated(parameter)
                    ? requestParam(jaxRsMetadata(parameter, method), parameter, parameter.getName(), encoded(parameter, method))
                    : null;
                if (param == null) {
                    context.info("JAX-RS sub-resource locator with a parameter that is not read from the request is not routed", parameter);
                    return;
                }
                params.add(param);
            }
            ClassElement returned = method.getGenericReturnType();
            boolean classReturn = returned.getName().equals(Class.class.getName());
            if (classReturn) {
                Map<String, ClassElement> typeArguments = returned.getTypeArguments();
                returned = typeArguments.isEmpty() ? null : typeArguments.values().iterator().next();
            }
            if (returned == null || returned.isPrimitive() || returned.getName().equals(Object.class.getName())
                || returned.isGenericPlaceholder() || returned.isWildcard() || returned.isAssignable(JAX_RS_RESPONSE)) {
                // known only at runtime: the router locates the target, then routes the rest of the path
                runtimeLocator(method, path, locators, params, classReturn);
                return;
            }
            int occurrences = visited.getOrDefault(returned.getName(), 0);
            if (occurrences >= MAX_LOCATOR_REPEAT) {
                // recursive: the paths cannot be enumerated, the deeper levels are located at runtime
                runtimeLocator(method, path, locators, params, classReturn);
                return;
            }
            visited.put(returned.getName(), occurrences + 1);
            try {
                RequestType created = null;
                if (classReturn) {
                    created = requestType(returned);
                    if (created == null) {
                        return;
                    }
                }
                List<Locator> chain = new ArrayList<>(locators);
                chain.add(new Locator(method, params, returned, created, segments(path)));
                collect(returned, path, chain, visited, false);
            } finally {
                visited.put(returned.getName(), occurrences);
            }
        }

        private void runtimeLocator(MethodElement method, String path, List<Locator> locators, List<Param> params, boolean classReturn) {
            if (classReturn) {
                context.info("JAX-RS sub-resource locator returning a class known only at runtime is not routed", method);
                return;
            }
            if (params.stream().anyMatch(p -> p.kind == ParamKind.FORM)) {
                context.info("JAX-RS sub-resource locator known only at runtime with a form parameter is not routed", method);
                return;
            }
            runtimeLocators.add(new RuntimeLocator(method, params, locators, path));
        }


        /**
         * How to create and inject an instance of a type per request.
         */
        @Nullable RequestType requestType(ClassElement type) {
            RequestType existing = requestTypes.get(type.getName());
            if (existing != null) {
                return existing;
            }
            List<Param> constructorParams = new ArrayList<>();
            ConstructorElement constructor = requestConstructor(type);
            if (constructor != null) {
                for (ParameterElement parameter : constructor.getParameters()) {
                    if (isRequestAnnotated(parameter)) {
                        Param param = requestParam(parameter, parameter, parameter.getName(), encoded(parameter, type));
                        if (param == null) {
                            return null;
                        }
                        constructorParams.add(param);
                    }
                }
            }
            List<Member> members = new ArrayList<>();
            for (MemberElement member : requestMembers(type)) {
                if (member instanceof FieldElement field) {
                    Param param = requestParam(field, field, field.getName(), encoded(field, type));
                    if (param == null) {
                        return null;
                    }
                    members.add(new Member(param, field, null));
                } else if (member instanceof MethodElement setter) {
                    ParameterElement parameter = setter.getParameters()[0];
                    Param param = requestParam(setter, parameter, parameter.getName(), encoded(setter, type));
                    if (param == null) {
                        return null;
                    }
                    members.add(new Member(param, null, setter));
                }
            }
            RequestType requestType = new RequestType(type, "create" + requestTypes.size(), constructorParams, members);
            requestTypes.put(type.getName(), requestType);
            return requestType;
        }

        /**
         * A parameter, field or setter read from the request.
         *
         * @param annotated The element with the annotations
         * @param typed     The element with the type
         * @param name      The name of the parameter, for a {@code @Parameter} of a constructor
         * @param encoded   Whether the value is not decoded
         */
        @Nullable Param requestParam(AnnotationMetadata annotated, TypedElement typed, String name, boolean encoded) {
            String defaultValue = annotated.stringValue(DefaultValue.class).orElse(null);
            if (annotated.hasAnnotation(PathParam.class)) {
                return new Param(ParamKind.PATH, annotated.stringValue(PathParam.class).orElse(name), typed, defaultValue, encoded, null);
            } else if (annotated.hasAnnotation(QueryParam.class)) {
                return new Param(ParamKind.QUERY, annotated.stringValue(QueryParam.class).orElse(name), typed, defaultValue, encoded, null);
            } else if (annotated.hasAnnotation(MatrixParam.class)) {
                return new Param(ParamKind.MATRIX, annotated.stringValue(MatrixParam.class).orElse(name), typed, defaultValue, encoded, null);
            } else if (annotated.hasAnnotation(HeaderParam.class)) {
                return new Param(ParamKind.HEADER, annotated.stringValue(HeaderParam.class).orElse(name), typed, defaultValue, encoded, null);
            } else if (annotated.hasAnnotation(CookieParam.class)) {
                return new Param(ParamKind.COOKIE, annotated.stringValue(CookieParam.class).orElse(name), typed, defaultValue, encoded, null);
            } else if (annotated.hasAnnotation(FormParam.class)) {
                return new Param(ParamKind.FORM, annotated.stringValue(FormParam.class).orElse(name), typed, defaultValue, encoded, null);
            } else if (annotated.hasAnnotation(BeanParam.class)) {
                // initialized at runtime from its introspection
                return new Param(ParamKind.BEAN, name, typed, null, encoded, null);
            } else if (annotated.hasAnnotation(Context.class)) {
                return new Param(ParamKind.CONTEXT, annotated.stringValue(NAMED).orElse(null), typed, null, false, null);
            }
            return null;
        }

        /**
         * The annotations of a parameter of a resource method or locator, as JAX-RS inherits them
         * (section 3.6): a method that declares a JAX-RS annotation itself does not inherit any
         * from the method it overrides, on the method or its parameters.
         */
        private static AnnotationMetadata jaxRsMetadata(ParameterElement parameter, MethodElement method) {
            AnnotationMetadata declared = method.getMethodAnnotationMetadata().getDeclaredMetadata();
            for (String name : declared.getAnnotationNames()) {
                if (name.startsWith("jakarta.ws.rs.")) {
                    return parameter.getDeclaredMetadata();
                }
            }
            return parameter;
        }

        private @Nullable ResourceMethod resourceMethod(ClassElement owner, String template, MethodElement method) {
            String httpMethod = method.stringValue(HttpMethod.class).orElse("").toUpperCase(Locale.ENGLISH);
            List<Param> params = new ArrayList<>();
            boolean form = false;
            Param entity = null;
            for (ParameterElement parameter : method.getParameters()) {
                if (parameter.hasAnnotation(Suspended.class)) {
                    context.warn("JAX-RS asynchronous responses with @Suspended are not routed yet", method);
                    return null;
                }
                Param param;
                if (parameter.hasAnnotation(FormParam.class) && NO_BODY_METHODS.contains(httpMethod)) {
                    // no form without a body: read from the query, like the controllers did
                    param = new Param(ParamKind.QUERY, parameter.stringValue(FormParam.class).orElse(parameter.getName()), parameter,
                        parameter.stringValue(DefaultValue.class).orElse(null), encoded(parameter, method), null);
                } else if (isRequestAnnotated(parameter)) {
                    param = requestParam(jaxRsMetadata(parameter, method), parameter, parameter.getName(), encoded(parameter, method));
                    if (param == null) {
                        return null;
                    }
                    form |= param.kind == ParamKind.FORM;
                } else if (CONTEXT_TYPES.contains(parameter.getType().getName())) {
                    param = new Param(ParamKind.CONTEXT, parameter.stringValue(NAMED).orElse(null), parameter, null, false, null);
                } else {
                    if (entity != null) {
                        context.fail("A JAX-RS resource method can have one entity parameter", parameter);
                        return null;
                    }
                    param = new Param(ParamKind.ENTITY, parameter.getName(), parameter, null, false, null);
                    entity = param;
                }
                params.add(param);
            }
            if (form && entity != null) {
                String entityType = entity.element.getType().getName();
                if (!entityType.equals(FORM_TYPES_MAP) && !entityType.equals(FORM_TYPE)) {
                    context.fail("The entity of a JAX-RS resource method with @FormParam parameters must be a form", entity.element);
                    return null;
                }
                entity.kind = ParamKind.FORM_ENTITY;
            }
            ClassElement returnType = method.getGenericReturnType();
            boolean async = returnType.getName().equals(COMPLETION_STAGE) || returnType.isAssignable(COMPLETION_STAGE) && returnType.getName().startsWith("java.util.concurrent.");
            return new ResourceMethod(method, httpMethod, template, params, form, entity, returnType, async,
                mediaTypes(method, owner, Produces.class), mediaTypes(method, owner, Consumes.class));
        }

        /**
         * @return Whether the generated router, in the package of the resource, can use the member
         * directly
         */
        boolean accessible(MemberElement member) {
            return !member.isPrivate() && (member.isPublic() || member.getDeclaringType().getPackageName().equals(resource.getPackageName()));
        }
    }

    /**
     * @return Whether a {@code @BeanParam} type, or one nested in it, reads a form field
     */
    private static boolean beanUsesForm(ClassElement type, int depth) {
        if (depth > 8) {
            return false;
        }
        for (MemberElement member : requestMembers(type)) {
            if (member.hasAnnotation(FormParam.class)
                || member.hasAnnotation(BeanParam.class) && member instanceof FieldElement field && beanUsesForm(field.getType(), depth + 1)) {
                return true;
            }
        }
        ConstructorElement constructor = requestConstructor(type);
        return constructor != null && Arrays.stream(constructor.getParameters()).anyMatch(p -> p.hasAnnotation(FormParam.class));
    }

    private static boolean encoded(Element element, Element enclosing) {
        return element.hasAnnotation(Encoded.class) || enclosing.hasAnnotation(Encoded.class);
    }

    private static List<String> mediaTypes(MethodElement method, ClassElement owner, Class<? extends java.lang.annotation.Annotation> annotation) {
        String[] values = method.getMethodAnnotationMetadata().stringValues(annotation);
        if (values.length == 0) {
            values = owner.stringValues(annotation);
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
     * A route template in the language of JAX-RS: the paths joined by a slash.
     */
    static String template(String prefix, String path) {
        StringBuilder template = new StringBuilder();
        for (String part : new String[]{prefix, path}) {
            String trimmed = strip(part);
            if (!trimmed.isEmpty()) {
                template.append('/').append(trimmed);
            }
        }
        return template.isEmpty() ? "/" : template.toString();
    }

    /**
     * The number of path segments a template matches, a variable with a regular expression
     * counted as one.
     */
    static int segments(String template) {
        int segments = 0;
        int depth = 0;
        boolean inSegment = false;
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
            if (c == '/' && depth == 0) {
                inSegment = false;
            } else if (!inSegment) {
                inSegment = true;
                segments++;
            }
        }
        return segments;
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
     * The model of the router of a resource: an {@code HttpRoutes} bean with a route per resource
     * method, and a method per type created per request.
     */
    private static ClassDef router(Model model, @Nullable RequestType root) {
        ClassElement resource = model.resource;
        ClassTypeDef routerType = ClassTypeDef.of(resource.getPackageName() + "." + resource.getSimpleName()
            + (model.located ? "$JaxRsLocatedRouter" : "$JaxRsRouter"));
        ClassTypeDef resourceType = ClassTypeDef.erasure(resource);
        Types types = new Types(model.context);

        ClassDef.ClassDefBuilder router = ClassDef.builder(routerType.getName())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addAnnotation(ClassTypeDef.of("jakarta.inject.Singleton"))
            .addSuperinterface(model.located ? types.type(LOCATED_ROUTES) : types.type(ROUTER + "HttpRoutes"))
            .addJavadoc(model.located
                ? "Implements the routes of {@link " + resource.getCanonicalName() + "} as the target of a sub-resource locator, relative to its prefix."
                : "Implements the routes of the JAX-RS resource {@link " + resource.getCanonicalName() + "} with handler functions.");
        Constants constants = new Constants(router, routerType);

        FieldDef resourceField = FieldDef.builder("resource", TypeDef.parameterized(ClassTypeDef.of(BeanProvider.class), resourceType))
            .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
            .build();
        FieldDef supportField = FieldDef.builder("support", types.support)
            .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
            .build();
        Generator generator = new Generator(model, constants, types, resourceField, supportField);

        List<RouteModel> routes = new ArrayList<>();
        for (int i = 0; i < model.routes.size(); i++) {
            routes.add(generator.routeModel(model.routes.get(i), i, root));
        }

        router.addField(supportField);
        if (model.located) {
            router.addMethod(MethodDef.constructor()
                .addModifiers(Modifier.PUBLIC)
                .addParameter("support", types.support)
                .build((aThis, params) -> aThis.field(supportField).assign(params.get(0))));
            router.addMethod(MethodDef.builder("type")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(TypeDef.parameterized(ClassTypeDef.of(Class.class), TypeDef.wildcard()))
                .build((aThis, params) -> ExpressionDef.constant(resourceType).returning()));
        } else {
            router.addField(resourceField);
            router.addMethod(MethodDef.constructor()
                .addModifiers(Modifier.PUBLIC)
                .addParameter("resource", resourceField.getType())
                .addParameter("support", types.support)
                .build((aThis, params) -> StatementDef.multi(
                    aThis.field(resourceField).assign(params.get(0)),
                    aThis.field(supportField).assign(params.get(1))
                )));
        }
        for (RequestType requestType : model.requestTypes.values()) {
            router.addMethod(generator.createMethod(requestType));
        }
        for (RouteModel route : routes) {
            router.addMethod(generator.routeMethod(route, root));
        }
        for (int i = 0; i < model.runtimeLocators.size(); i++) {
            router.addMethod(generator.locateMethod(model.runtimeLocators.get(i), "locate" + i, root));
        }
        router.addMethod(MethodDef.builder("routes")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(Override.class)
            .addParameter("routes", types.routeBuilder)
            .returns(TypeDef.VOID)
            .build((aThis, params) -> {
                VariableDef support = aThis.field(supportField);
                List<StatementDef> statements = new ArrayList<>();
                for (RouteModel route : routes) {
                    ResourceMethod method = route.route.method;
                    List<ExpressionDef> handle = new ArrayList<>();
                    // declared by the name of the HTTP method, custom or not, with the template in the language of JAX-RS
                    handle.add(support.invoke(model.located ? "locatedDeclaration" : "declaration", types.routeDeclaration,
                        ExpressionDef.constant(method.httpMethod), ExpressionDef.constant(method.template)));
                    String builderMethod;
                    if (method.async) {
                        // the handler reads the entity or the form, see handlerLambda
                        builderMethod = "handleAsync";
                    } else if (route.form) {
                        builderMethod = "handleForm";
                    } else if (route.body) {
                        builderMethod = "handle";
                        handle.add(route.entityArgument);
                    } else {
                        builderMethod = "handle";
                    }
                    handle.add(generator.handlerLambda(aThis, route));
                    statements.add(support.invoke("configure", TypeDef.VOID,
                        params.get(0).invoke(builderMethod, types.routeSpec, handle),
                        route.metadata));
                }
                for (int i = 0; i < model.runtimeLocators.size(); i++) {
                    RuntimeLocator locator = model.runtimeLocators.get(i);
                    String locateMethod = "locate" + i;
                    // the router locates the target, then matches the rest of the path with the routes of its class
                    statements.add(params.get(0).invoke("locate", TypeDef.VOID,
                        support.invoke(model.located ? "locatedPrefix" : "prefix", types.routeTemplate, ExpressionDef.constant(locator.prefix)),
                        types.type(ROUTER + "LocatorHandler").getLambda(Map.of()).implement((lambdaThis, lambdaParams) ->
                            aThis.invoke(locateMethod, TypeDef.OBJECT, new ArrayList<ExpressionDef>(lambdaParams)).returning()),
                        support.invoke("locatedTables", TypeDef.OBJECT)));
                }
                if (model.located) {
                    return StatementDef.multi(statements);
                }
                // the resource is not a bean in this context, e.g. disabled by @Requires, or the
                // Application lists its classes without it
                return aThis.field(resourceField).invoke("isPresent", TypeDef.Primitive.BOOLEAN).ifTrue(
                    support.invoke("isRegistered", TypeDef.Primitive.BOOLEAN, ExpressionDef.constant(ClassTypeDef.erasure(model.resource)))
                        .ifTrue(StatementDef.multi(statements)));
            }));
        return router.build();
    }

    /**
     * The types the generated code uses.
     */
    private static final class Types {
        final VisitorContext context;
        final ClassTypeDef support;
        final ClassTypeDef metadata;
        final ClassTypeDef routeBuilder;
        final ClassTypeDef routeSpec;
        final ClassTypeDef routeDeclaration;
        final ClassTypeDef routeTemplate;
        final TypeDef request = TypeDef.parameterized(ClassTypeDef.of("io.micronaut.http.HttpRequest"), TypeDef.wildcard());
        final TypeDef response = TypeDef.parameterized(ClassTypeDef.of(HTTP_RESPONSE), TypeDef.wildcard());
        final ClassTypeDef pathVariables;
        final ClassTypeDef form;

        Types(VisitorContext context) {
            this.context = context;
            support = type(SUPPORT);
            metadata = type(SUPPORT + ".RouteMetadata");
            routeBuilder = type(ROUTER + "HttpRouteBuilder");
            routeSpec = type(ROUTER + "HttpRouteSpec");
            routeDeclaration = type(ROUTER + "RouteDeclaration");
            routeTemplate = type("io.micronaut.http.uri.RouteTemplate");
            pathVariables = type(ROUTER + "PathVariables");
            form = type("io.micronaut.http.form.FormData");
        }

        ClassTypeDef type(String name) {
            return ClassTypeDef.erasure(context.getClassElement(name)
                .orElseThrow(() -> new IllegalStateException("The type " + name + " is not on the classpath of the compilation")));
        }
    }

    /**
     * The {@code private static final} constants of the router.
     */
    private static final class Constants {
        final ClassDef.ClassDefBuilder router;
        final ClassTypeDef routerType;
        int count;

        Constants(ClassDef.ClassDefBuilder router, ClassTypeDef routerType) {
            this.router = router;
            this.routerType = routerType;
        }

        VariableDef.StaticField add(String prefix, ClassTypeDef type, ExpressionDef value) {
            FieldDef field = FieldDef.builder(prefix + count++, type)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer(value)
                .build();
            router.addField(field);
            return routerType.getStaticField(field);
        }
    }

    /**
     * Generates the methods of the router.
     */
    private static final class Generator {
        final Model model;
        final Constants constants;
        final Types types;
        final FieldDef resourceField;
        final FieldDef supportField;
        final Map<String, ExpressionDef> arguments = new LinkedHashMap<>();

        Generator(Model model, Constants constants, Types types, FieldDef resourceField, FieldDef supportField) {
            this.model = model;
            this.constants = constants;
            this.types = types;
            this.resourceField = resourceField;
            this.supportField = supportField;
        }

        ExpressionDef argument(Param param) {
            return argument(param.element.getGenericType());
        }

        /**
         * The {@code Argument} of a type: a constant of {@code Argument} for the common types, or
         * a constant of the router shared by every value of the same type.
         */
        ExpressionDef argument(ClassElement type) {
            String signature = signature(type);
            ExpressionDef existing = arguments.get(signature);
            if (existing != null) {
                return existing;
            }
            String builtIn = BUILT_IN_ARGUMENTS.get(signature);
            ExpressionDef argument = builtIn != null
                ? ARGUMENT.getStaticField(builtIn, ARGUMENT)
                : constants.add("A", ARGUMENT, JaxRsRoutesGenerator.argument(type, this));
            arguments.put(signature, argument);
            return argument;
        }

        RouteModel routeModel(Route route, int index, @Nullable RequestType root) {
            ResourceMethod method = route.method;
            ExpressionDef entityArgument = null;
            int annotatedEntity = -1;
            for (Param param : method.params) {
                if (param.kind == ParamKind.BEAN) {
                    continue;
                }
                ExpressionDef argument = argument(param);
                if (param.kind == ParamKind.ENTITY) {
                    if (!param.element.getDeclaredAnnotationNames().isEmpty()) {
                        // the readers see the annotations of the parameter, see RouteSupport#entityArgument
                        annotatedEntity = List.of(method.method.getParameters()).indexOf(param.element);
                    }
                    // the route receives the bytes of an optional entity: the JAX-RS readers read it,
                    // see RouteSupport#entity
                    entityArgument = types.support.getStaticField("ENTITY", ARGUMENT);
                }
            }
            ClassElement valueType = method.async ? firstTypeArgument(method.returnType) : method.returnType;
            ExpressionDef returnType = valueType == null || valueType.isVoid() ? ARGUMENT.getStaticField("VOID", ARGUMENT) : argument(valueType);
            VariableDef.StaticField metadata = constants.add("ROUTE", types.metadata, types.metadata.instantiate(
                ExpressionDef.constant(ClassTypeDef.erasure(route.owner)),
                ExpressionDef.constant(method.method.getName()),
                TypeDef.CLASS.array().instantiate(Arrays.stream(method.method.getParameters())
                    .map(p -> (ExpressionDef) ExpressionDef.constant(TypeDef.erasure(p.getType()))).toList()),
                strings(method.produces),
                strings(method.consumes),
                ExpressionDef.constant(ClassTypeDef.erasure(model.resource))
            ));
            boolean body = method.entity != null && method.entity.kind == ParamKind.ENTITY && !method.httpMethod.equals("GET");
            // a form is read for a type created per request when the method can have one and reads no entity
            boolean form = method.form || usesForm(route, root) && !NO_BODY_METHODS.contains(method.httpMethod) && method.entity == null;
            return new RouteModel("route" + index, route, entityArgument, annotatedEntity, valueType, returnType, metadata, form, body && !form);
        }

        private boolean usesForm(Route route, @Nullable RequestType root) {
            if (root != null && root.usesForm()) {
                return true;
            }
            for (Locator locator : route.locators) {
                for (Param param : locator.params) {
                    if (param.kind == ParamKind.FORM || param.kind == ParamKind.BEAN && beanUsesForm(param.element.getType(), 0)) {
                        return true;
                    }
                }
                if (locator.created != null && locator.created.usesForm()) {
                    return true;
                }
            }
            for (Param param : route.method.params) {
                if (param.kind == ParamKind.BEAN && beanUsesForm(param.element.getType(), 0)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The handler function of a route: calls the method of the route.
         */
        ExpressionDef handlerLambda(VariableDef.This aThis, RouteModel route) {
            String handlerType = handlerType(route);
            if (route.route.method.async && (route.form || route.body)) {
                // read the form or the entity from the request, then call the method of the route
                TypeDef read = route.form ? types.form : TypeDef.of(byte[].class);
                return types.type(ROUTER + handlerType).getLambda(Map.of()).implement((lambdaThis, lambdaParams) ->
                    aThis.field(supportField).invoke(route.form ? "formAsync" : "entityAsync", ClassTypeDef.of(COMPLETION_STAGE),
                        lambdaParams.get(0), lambdaParams.get(1),
                        types.type(ASYNC_HANDLER).getLambda(Map.of("T", read)).implement((valueThis, valueParams) ->
                            aThis.invoke(route.name, TypeDef.OBJECT, new ArrayList<ExpressionDef>(valueParams)).returning())
                    ).returning());
            }
            Map<String, TypeDef> typeVariables = handlerType.endsWith("BodyRequestHandler") ? Map.of("B", TypeDef.OBJECT) : Map.of();
            return types.type(ROUTER + handlerType).getLambda(typeVariables).implement((lambdaThis, lambdaParams) ->
                aThis.invoke(route.name, TypeDef.OBJECT, new ArrayList<ExpressionDef>(lambdaParams)).returning());
        }

        private String handlerType(RouteModel route) {
            if (route.route.method.async) {
                return "AsyncRequestHandler";
            } else if (route.form) {
                return "FormRequestHandler";
            } else if (route.body) {
                return "BodyRequestHandler";
            }
            return "RequestHandler";
        }

        /**
         * The method that creates an instance of a type per request: with the values of the
         * request as the {@code @Parameter}s of its constructor, and injected into its fields and
         * setters.
         */
        MethodDef createMethod(RequestType requestType) {
            ClassTypeDef type = ClassTypeDef.erasure(requestType.type);
            return MethodDef.builder(requestType.method)
                .addModifiers(Modifier.PRIVATE)
                .addParameter("type", TypeDef.CLASS)
                .addParameter("request", types.request)
                .addParameter("pathVariables", types.pathVariables)
                .addParameter("form", types.form)
                .returns(type)
                .build((aThis, params) -> {
                    Scope scope = new Scope(aThis, aThis.field(supportField), params.get(1), params.get(2), params.get(3), null, null);
                    List<ExpressionDef> names = new ArrayList<>();
                    List<ExpressionDef> values = new ArrayList<>();
                    for (Param param : requestType.constructorParams) {
                        names.add(ExpressionDef.constant(param.element.getName()));
                        values.add(value(param, scope));
                    }
                    ExpressionDef instance = scope.support.invoke("create", TypeDef.OBJECT,
                        params.get(0),
                        TypeDef.STRING.array().instantiate(names),
                        TypeDef.OBJECT.array().instantiate(values)
                    ).cast(type);
                    if (requestType.members.isEmpty()) {
                        return instance.returning();
                    }
                    return instance.newLocal("instance", local -> {
                        List<StatementDef> statements = new ArrayList<>();
                        for (Member member : requestType.members) {
                            statements.add(inject(local, member, scope));
                        }
                        statements.add(local.returning());
                        return StatementDef.multi(statements);
                    });
                });
        }

        private StatementDef inject(VariableDef instance, Member member, Scope scope) {
            ExpressionDef value = value(member.param, scope);
            FieldElement field = member.field;
            if (field != null) {
                // the source generator renders a field access only on the generated class itself
                return scope.support.invoke("setField", TypeDef.VOID, instance,
                    ExpressionDef.constant(ClassTypeDef.erasure(field.getDeclaringType())),
                    ExpressionDef.constant(field.getName()), value);
            }
            // a member that is not a field is a setter
            MethodElement setter = Objects.requireNonNull(member.setter);
            if (model.accessible(setter)) {
                return instance.invoke(setter, value);
            }
            return scope.support.invoke("invokeSetter", TypeDef.VOID, instance,
                ExpressionDef.constant(ClassTypeDef.erasure(setter.getDeclaringType())),
                ExpressionDef.constant(setter.getName()),
                ExpressionDef.constant(TypeDef.erasure(setter.getParameters()[0].getType())), value);
        }

        /**
         * The method of a route: creates or gets the resource, calls the locators, reads the
         * parameters, calls the resource method and returns the response.
         */
        /**
         * The instance a route calls: the resource, or the target a locator located at runtime,
         * followed through the locators known at compile time.
         */
        private ExpressionDef instance(VariableDef.This aThis, Scope scope, @Nullable RequestType root, List<Locator> locators) {
            VariableDef request = scope.request;
            VariableDef pathVariables = scope.pathVariables;
            ClassTypeDef resourceType = ClassTypeDef.erasure(model.resource);
            ExpressionDef instance;
            if (model.located) {
                // located at runtime, and already matched by its locator
                instance = pathVariables.invoke("locatedTarget", TypeDef.OBJECT, ExpressionDef.constant(resourceType)).cast(resourceType);
            } else {
                instance = root == null
                    ? aThis.field(resourceField).invoke("get", TypeDef.OBJECT).cast(resourceType)
                    : aThis.invoke(root.method, resourceType, ExpressionDef.constant(resourceType), request, pathVariables, formOrNull(scope));
                // the matched resources and URIs of UriInfo
                instance = scope.support.invoke("matched", TypeDef.OBJECT, request, instance, ExpressionDef.constant(model.rootSegments)).cast(resourceType);
            }
            for (Locator locator : locators) {
                List<ExpressionDef> arguments = new ArrayList<>();
                for (Param param : locator.params) {
                    arguments.add(value(param, scope));
                }
                // a locator returning null is a 404
                ExpressionDef located = scope.support.invoke("located", TypeDef.OBJECT, instance.invoke(locator.method, arguments));
                ClassTypeDef locatedType = ClassTypeDef.erasure(locator.type);
                if (locator.created != null) {
                    // the locator returned the class: an instance is created for the request
                    instance = aThis.invoke(locator.created.method, locatedType, located.cast(TypeDef.CLASS), request, pathVariables, formOrNull(scope));
                } else {
                    instance = located.cast(locatedType);
                }
                instance = scope.support.invoke("matched", TypeDef.OBJECT, request, instance, ExpressionDef.constant(locator.segments)).cast(locatedType);
            }
            return instance;
        }

        /**
         * The method of a locator whose target is known only at runtime: the target, for the
         * router to route the rest of the path with the routes of its class.
         */
        MethodDef locateMethod(RuntimeLocator locator, String name, @Nullable RequestType root) {
            return MethodDef.builder(name)
                .addModifiers(Modifier.PRIVATE)
                .addParameter("request", types.request)
                .addParameter("pathVariables", types.pathVariables)
                .returns(TypeDef.OBJECT)
                .addThrows(ClassTypeDef.of(Exception.class))
                .build((aThis, params) -> {
                    Scope scope = new Scope(aThis, aThis.field(supportField), params.get(0), params.get(1), null, null, null);
                    ExpressionDef instance = instance(aThis, scope, root, locator.locators);
                    List<ExpressionDef> arguments = new ArrayList<>();
                    for (Param param : locator.params) {
                        arguments.add(value(param, scope));
                    }
                    // a locator returning null is a 404
                    ExpressionDef located = scope.support.invoke("located", TypeDef.OBJECT, instance.invoke(locator.method, arguments));
                    StatementDef result = scope.support.invoke("matched", TypeDef.OBJECT, params.get(0), located,
                        ExpressionDef.constant(model.located ? -1 : segments(locator.prefix))).returning();
                    if (throwsThrowable(locator.method) || locator.locators.stream().anyMatch(l -> throwsThrowable(l.method))) {
                        result = StatementDef.doTry(result).doCatch(Throwable.class, throwable ->
                            types.support.invokeStatic("rethrow", ClassTypeDef.of(RuntimeException.class), throwable).doThrow());
                    }
                    return result;
                });
        }

        MethodDef routeMethod(RouteModel route, @Nullable RequestType root) {
            ResourceMethod method = route.route.method;
            String handlerType = handlerType(route);
            MethodDef.MethodDefBuilder builder = MethodDef.builder(route.name)
                .addModifiers(Modifier.PRIVATE)
                .addParameter("request", types.request)
                .addParameter("pathVariables", types.pathVariables);
            if (route.form) {
                builder.addParameter("form", types.form);
            } else if (route.body) {
                builder.addParameter("body", TypeDef.OBJECT);
            }
            builder.returns(handlerType.startsWith("Async")
                ? TypeDef.parameterized(ClassTypeDef.of(java.util.concurrent.CompletionStage.class), TypeDef.wildcardSubtypeOf(types.response))
                : types.response);
            builder.addThrows(ClassTypeDef.of(Exception.class));
            return builder.build((aThis, params) -> {
                VariableDef request = params.get(0);
                VariableDef pathVariables = params.get(1);
                VariableDef third = params.size() > 2 ? params.get(2) : null;
                Scope scope = new Scope(aThis, aThis.field(supportField), request, pathVariables,
                    route.form ? third : null, route.body ? third : null, route);

                ExpressionDef instance = instance(aThis, scope, root, route.route.locators);
                List<ExpressionDef> arguments = new ArrayList<>();
                for (Param param : method.params) {
                    arguments.add(value(param, scope));
                }
                ExpressionDef call = instance.invoke(method.method, arguments);
                StatementDef result;
                if (method.returnType.isVoid()) {
                    result = StatementDef.multi(
                        (StatementDef) call,
                        scope.support.invoke("noContent", TypeDef.OBJECT).returning()
                    );
                } else if (method.async) {
                    result = call.invoke("thenApply", ClassTypeDef.of(COMPLETION_STAGE),
                        types.type(Function.class.getName()).getLambda(Map.of("T", TypeDef.OBJECT, "R", TypeDef.OBJECT)).implement((lambdaThis, lambdaParams) ->
                            response(route, lambdaParams.get(0), scope).returning())
                    ).returning();
                } else if (method.produces.isEmpty()) {
                    // the type of the response from the JAX-RS writers of its entity
                    result = scope.support.invoke("negotiate", types.response, request, response(route, call, scope), route.returnType).returning();
                } else {
                    // the entity has the negotiated type, also when a HEAD request drops it (JAX-RS 3.8)
                    result = scope.support.invoke("produced", types.response, pathVariables, response(route, call, scope)).returning();
                }
                if (!method.produces.isEmpty()) {
                    // a negotiated type that is not concrete is not acceptable
                    result = StatementDef.multi(scope.support.invoke("acceptable", TypeDef.VOID, pathVariables), result);
                }
                if (throwsThrowable(method.method) || route.route.locators.stream().anyMatch(l -> throwsThrowable(l.method))) {
                    // a route handler can only throw exceptions
                    result = StatementDef.doTry(result).doCatch(Throwable.class, throwable ->
                        types.support.invokeStatic("rethrow", ClassTypeDef.of(RuntimeException.class), throwable).doThrow());
                }
                return result;
            });
        }

        /**
         * The response of a result, converted as its declared type says.
         */
        private ExpressionDef response(RouteModel route, ExpressionDef result, Scope scope) {
            ClassElement type = route.valueType;
            VariableDef support = scope.support;
            if (type == null || type.getName().equals(Object.class.getName()) || type.isGenericPlaceholder() || type.isWildcard()) {
                // known only at runtime
                return support.invoke("anyResponse", TypeDef.OBJECT, scope.request, result, route.returnType);
            }
            if (type.isAssignable(JAX_RS_RESPONSE)) {
                return support.invoke("jaxRsResponse", TypeDef.OBJECT, result.cast(ClassTypeDef.of(JAX_RS_RESPONSE)));
            }
            if (type.isAssignable(HTTP_RESPONSE)) {
                return support.invoke("httpResponse", TypeDef.OBJECT, result.cast(ClassTypeDef.of(HTTP_RESPONSE)));
            }
            if (type.isAssignable(GENERIC_ENTITY)) {
                // the type of the generic entity selects the message body writer
                return support.invoke("genericEntityResponse", TypeDef.OBJECT, scope.request, result.cast(ClassTypeDef.of(GENERIC_ENTITY)));
            }
            if (!type.isArray() && !type.getTypeArguments().isEmpty()) {
                // the declared type, with its type arguments, selects the message body writer
                return support.invoke("genericEntityResponse", TypeDef.OBJECT, scope.request, result, route.returnType);
            }
            return support.invoke("entityResponse", TypeDef.OBJECT, result);
        }

        private ExpressionDef formOrNull(Scope scope) {
            return scope.form == null ? ExpressionDef.nullValue() : scope.form;
        }

        /**
         * The bytes of the entity the route receives, {@code null} for a route without one.
         */
        private ExpressionDef entityBytes(Scope scope) {
            return scope.body == null ? ExpressionDef.nullValue() : scope.body.cast(TypeDef.of(byte[].class));
        }

        /**
         * The value of a parameter, read from the request and converted.
         */
        ExpressionDef value(Param param, Scope scope) {
            ExpressionDef defaultValue = param.defaultValue == null ? ExpressionDef.nullValue() : ExpressionDef.constant(param.defaultValue);
            ExpressionDef name = param.name == null ? ExpressionDef.nullValue() : ExpressionDef.constant(param.name);
            ExpressionDef encoded = ExpressionDef.constant(param.encoded);
            VariableDef support = scope.support;
            if (param.kind == ParamKind.BEAN) {
                ClassTypeDef beanType = ClassTypeDef.erasure(param.element.getType());
                return support.invoke("beanParam", TypeDef.OBJECT,
                    ExpressionDef.constant(beanType), scope.request, scope.pathVariables, formOrNull(scope)).cast(beanType);
            }
            ExpressionDef argument = argument(param);
            ExpressionDef value = switch (param.kind) {
                case PATH -> support.invoke("pathParam", TypeDef.OBJECT, scope.request, scope.pathVariables, name, argument, defaultValue, encoded);
                case QUERY -> support.invoke("queryParam", TypeDef.OBJECT, scope.request, name, argument, defaultValue, encoded);
                case MATRIX -> support.invoke("matrixParam", TypeDef.OBJECT, scope.request, name, argument, defaultValue, encoded);
                case HEADER -> support.invoke("headerParam", TypeDef.OBJECT, scope.request, name, argument, defaultValue);
                case COOKIE -> support.invoke("cookieParam", TypeDef.OBJECT, scope.request, name, argument, defaultValue);
                // read from the query when the route reads no form
                case FORM -> support.invoke("formParam", TypeDef.OBJECT, scope.request, formOrNull(scope), name, argument, defaultValue, encoded);
                case FORM_ENTITY -> support.invoke("formEntity", TypeDef.OBJECT, formOrNull(scope), argument);
                case CONTEXT -> support.invoke("context", TypeDef.OBJECT, scope.request, argument, name);
                case ENTITY -> scope.route != null && scope.route.annotatedEntity() >= 0
                    // the readers see the annotations of the parameter
                    ? support.invoke("entity", TypeDef.OBJECT, scope.request, entityBytes(scope),
                        scope.route.metadata(), ExpressionDef.constant(scope.route.annotatedEntity()), argument)
                    : support.invoke("entity", TypeDef.OBJECT, scope.request, entityBytes(scope), argument);
                case BEAN -> throw new IllegalStateException("Handled above");
            };
            return value.cast(TypeDef.erasure(param.element.getType()));
        }
    }

    /**
     * An expression creating the {@code Argument} of a type, with its type arguments.
     */
    private static ExpressionDef argument(ClassElement type, Generator generator) {
        List<ExpressionDef> values = new ArrayList<>();
        values.add(ExpressionDef.constant(TypeDef.erasure(type)));
        if (!type.isArray() && !type.isPrimitive()) {
            for (ClassElement typeArgument : type.getTypeArguments().values()) {
                values.add(generator.argument(typeArgument));
            }
        }
        return ARGUMENT.invokeStatic("of", ARGUMENT, values);
    }

    /**
     * The generic signature of a type, which identifies its {@code Argument}.
     */
    private static String signature(ClassElement type) {
        if (type.isGenericPlaceholder() || type.isWildcard()) {
            return Object.class.getName();
        }
        StringBuilder signature = new StringBuilder(type.getName());
        if (type.isArray()) {
            signature.append("[]".repeat(Math.max(1, type.getArrayDimensions())));
            return signature.toString();
        }
        if (!type.isPrimitive() && !type.getTypeArguments().isEmpty()) {
            signature.append('<');
            boolean first = true;
            for (ClassElement typeArgument : type.getTypeArguments().values()) {
                if (!first) {
                    signature.append(',');
                }
                signature.append(signature(typeArgument));
                first = false;
            }
            signature.append('>');
        }
        return signature.toString();
    }

    private static ExpressionDef strings(List<String> values) {
        return TypeDef.STRING.array().instantiate(values.stream().map(v -> (ExpressionDef) ExpressionDef.constant(v)).toList());
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

    private enum ParamKind {
        PATH, QUERY, MATRIX, HEADER, COOKIE, FORM, FORM_ENTITY, CONTEXT, ENTITY, BEAN
    }

    /**
     * A value a resource method, locator, constructor, field or setter reads.
     */
    private static final class Param {
        ParamKind kind;
        final @Nullable String name;
        final TypedElement element;
        final @Nullable String defaultValue;
        final boolean encoded;
        final @Nullable RequestType bean;

        Param(ParamKind kind, @Nullable String name, TypedElement element, @Nullable String defaultValue, boolean encoded, @Nullable RequestType bean) {
            this.kind = kind;
            this.name = name;
            this.element = element;
            this.defaultValue = defaultValue;
            this.encoded = encoded;
            this.bean = bean;
        }
    }

    /**
     * A field or setter injected per request.
     *
     * @param param  The value
     * @param field  The field
     * @param setter The setter
     */
    private record Member(Param param, @Nullable FieldElement field, @Nullable MethodElement setter) {
    }

    /**
     * A type created per request.
     *
     * @param type              The type
     * @param method            The name of the router method that creates it
     * @param constructorParams The constructor parameters read from the request
     * @param members           The fields and setters read from the request
     */
    private record RequestType(ClassElement type, String method, List<Param> constructorParams, List<Member> members) {

        boolean usesForm() {
            return constructorParams.stream().anyMatch(p -> p.kind == ParamKind.FORM || p.kind == ParamKind.BEAN && beanUsesForm(p.element.getType(), 0))
                || members.stream().anyMatch(m -> m.param.kind == ParamKind.FORM || m.param.kind == ParamKind.BEAN && beanUsesForm(m.param.element.getType(), 0));
        }
    }

    /**
     * A sub-resource locator of a route.
     *
     * @param method  The locator
     * @param params  Its parameters
     * @param type    The type it returns, or the type of the class it returns
     * @param created How the class it returns is created, or {@code null} if it returns an instance
     * @param segments The number of path segments the resource it locates matches
     */
    private record Locator(MethodElement method, List<Param> params, ClassElement type, @Nullable RequestType created, int segments) {
    }

    /**
     * A sub-resource locator whose target is located at runtime.
     *
     * @param method   The locator
     * @param params   Its parameters
     * @param locators The locators leading to its type, from the resource
     * @param prefix   The template of the prefix it matches
     */
    private record RuntimeLocator(MethodElement method, List<Param> params, List<Locator> locators, String prefix) {
    }

    /**
     * A route: the locators leading to a resource method.
     *
     * @param method   The resource method
     * @param locators The locators, from the resource
     * @param owner    The type of the resource method
     */
    private record Route(ResourceMethod method, List<Locator> locators, ClassElement owner) {
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

    /**
     * What a generated method needs to read a value.
     *
     * @param router        The router
     * @param support       The route support field
     * @param request       The request parameter
     * @param pathVariables The path variables parameter
     * @param form          The form, if the route reads one
     * @param body          The body parameter of a route with an entity
     */
    private record Scope(VariableDef.This router, VariableDef support, VariableDef request, VariableDef pathVariables,
                         @Nullable VariableDef form, @Nullable VariableDef body, @Nullable RouteModel route) {
    }

    /**
     * A route of the router, with its constants.
     *
     * @param name           The name of the route method
     * @param route          The route
     * @param entityArgument The nullable body argument constant
     * @param valueType      The declared type of the result, of the stage of an asynchronous method
     * @param returnType     The argument constant of the result
     * @param metadata       The route metadata constant
     * @param form           Whether the route reads a form
     * @param body           Whether the route reads an entity
     */
    private record RouteModel(String name, Route route, @Nullable ExpressionDef entityArgument, int annotatedEntity, @Nullable ClassElement valueType,
                              ExpressionDef returnType, VariableDef.StaticField metadata, boolean form, boolean body) {
    }
}
