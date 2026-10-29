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

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NextMajorVersion;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.bind.annotation.Bindable;
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
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.annotation.MutableAnnotationMetadataDelegate;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.TypedElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.context.annotation.BeanProperties;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import jakarta.inject.Named;
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
import jakarta.ws.rs.core.Application;
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
import java.util.Map;
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
    private static final String JAX_RS_PACKAGE = "jakarta.ws.rs.";
    private static final String HTTP_ANNOTATION_PACKAGE = "io.micronaut.http.annotation.";
    // the class annotations that stay inherited: the path a subclass is routed with, and the ones
    // that are not annotations of a resource
    private static final Set<String> KEPT_CLASS_ANNOTATIONS = Set.of(Path.class.getName(), Provider.class.getName(),
        "jakarta.ws.rs.ApplicationPath");
    private static final Map<String, String> MAPPED_CLASS_ANNOTATIONS = Map.of(
        jakarta.ws.rs.Produces.class.getName(), Produces.class.getName(),
        jakarta.ws.rs.Consumes.class.getName(), Consumes.class.getName());
    private static final Class<?>[] BINDABLE_TYPES = new Class<?>[] {Context.class, SecurityContext.class, UriInfo.class};
    @Nullable
    private ClassElement currentClassElement;
    private boolean runtimeRoutes;

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
            if (!element.isAbstract()) {
                // its annotations, e.g. @Priority, and its @Context fields and setters, which an
                // instance registered by the application is injected with, without reflection
                introspect(element);
            }
            if (!element.isAbstract()) {
                // the type arguments of the provider interfaces it implements, e.g. the T of a
                // ContextResolver<T>, without reflection
                context.importTypeHierarchy(element, element);
                providerConstructor(element);
            }
            return;
        }
        discardInheritedClassAnnotations(element);
        if (element.isAssignable(Application.class) && !element.isAbstract()) {
            // its @ApplicationPath, constructor and @Context members, without reflection
            introspect(element);
        }
        // the @Path of a class maps to the annotation of a resource method or locator: the
        // methods of the class are not all resource methods
        element.removeStereotype(HttpMethodMappingMapper.RESOURCE_METHOD);
        element.removeAnnotation(HttpMethodMappingMapper.RESOURCE_METHOD);
        currentClassElement = element;
        runtimeRoutes = context.getLanguage() == VisitorContext.Language.JAVA && JaxRsResources.isSupported(context);
        if (!runtimeRoutes) {
            // the runtime routes need the JAX-RS server, and are enabled for Java: other languages,
            // and compilations without the server, keep the resources as controllers
            if (element.hasAnnotation(Path.class) && !element.isAbstract()) {
                element.stringValue(Path.class).ifPresent(p -> {
                    element.annotate(Controller.class, builder -> builder.value(p));
                    element.annotate(UriMapping.class, builder -> builder.value(p));
                });
            }
            return;
        }
        boolean resource = element.hasAnnotation(Path.class) || !element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyInstance()
            .annotated(metadata -> metadata.hasStereotype(HttpMethod.class))).isEmpty();
        boolean routed = !element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyInstance()
            .annotated(metadata -> metadata.hasStereotype(HttpMethod.class) || metadata.hasDeclaredAnnotation(Path.class))).isEmpty();
        if ((routed || JaxRsResources.isCreatedPerRequest(element)) && !element.isInterface()) {
            // the target of a sub-resource locator, or a resource created per request, also one
            // without resource methods: the runtime routes read its resource methods, locators,
            // constructor and request members
            introspect(element);
        }
        if (!resource && !element.isAbstract() && !element.isInterface() && !JaxRsResources.requestMembers(element).isEmpty()) {
            // a @BeanParam type: initialized at runtime from its introspection, with the values of the request
            introspect(element);
            return;
        }
        if (!element.isAbstract() && !element.isInterface() && !element.hasStereotype(Controller.class)
            && (element.hasAnnotation(Path.class) || !element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyInstance()
                .annotated(metadata -> metadata.hasStereotype(HttpMethod.class))).isEmpty())) {
            // a resource or a sub-resource: a bean whose methods are routed at runtime. JAX-RS
            // creates a resource for every request, unless it has a scope, e.g. a singleton
            ConstructorElement requestConstructor = JaxRsResources.requestConstructor(element);
            boolean shared = JaxRsResources.isShared(element);
            if (!element.hasStereotype(AnnotationUtil.SCOPE)) {
                element.annotate(Prototype.class);
            }
            if (shared && !JaxRsResources.requestMembers(element).stream().allMatch(member -> member.hasAnnotation(Context.class))) {
                context.warn("The values of the request in the fields and setters of a singleton resource are not injected: only its @Context ones are, with proxies of the current request", element);
            }
            if (requestConstructor != null) {
                requestConstructor.annotate(Inject.class);
                for (ParameterElement parameter : requestConstructor.getParameters()) {
                    // a singleton gets proxies of the current request as its @Context values
                    if (JaxRsResources.isRequestAnnotated(parameter) && !(shared && parameter.hasAnnotation(Context.class))) {
                        parameter.annotate(Parameter.class);
                        if (!parameter.isPrimitive()) {
                            parameter.annotate(Nullable.class);
                        }
                    }
                }
            }
        }
    }

    /**
     * The constructor a provider is created with: the public one with the most parameters, which
     * are all {@code @Context} ones (JAX-RS 4.1.3), unless one is annotated with {@code @Inject}.
     */
    private static void providerConstructor(ClassElement element) {
        List<ConstructorElement> constructors = element.getEnclosedElements(ElementQuery.CONSTRUCTORS);
        if (constructors.size() < 2 || constructors.stream().anyMatch(c -> c.hasAnnotation(Inject.class))) {
            return;
        }
        ConstructorElement selected = null;
        for (ConstructorElement constructor : constructors) {
            if (constructor.isPublic() && Arrays.stream(constructor.getParameters()).allMatch(p -> p.hasAnnotation(Context.class))
                && (selected == null || constructor.getParameters().length > selected.getParameters().length)) {
                selected = constructor;
            }
        }
        if (selected != null && selected.getParameters().length > 0) {
            selected.annotate(Inject.class);
        }
    }

    @Override
    public void visitMethod(MethodElement element, VisitorContext context) {
        discardInheritedAnnotations(element);
        if (runtimeRoutes && element.hasAnnotation(Context.class) && !element.hasStereotype(HttpMethod.class)
            && JaxRsResources.isCreatedPerRequest(element.getOwningType())) {
            // a setter injected by the runtime routes
            element.removeAnnotation(Inject.class);
        }
        // the methods of an interface are public without the modifier
        boolean isPublic = element.isPublic() || element.getDeclaringType().isInterface() && !element.isPrivate();
        if (runtimeRoutes && !isPublic && (element.hasStereotype(HttpMethod.class) || element.hasDeclaredAnnotation(Path.class))) {
            // routed at runtime only when public
            element.removeStereotype(HttpMethodMappingMapper.RESOURCE_METHOD);
            element.removeAnnotation(HttpMethodMappingMapper.RESOURCE_METHOD);
            // not a resource method nor a sub-resource locator: only public methods are (JAX-RS 3.3.1),
            // the runtime routes leave it out
            context.warn("The method is not public, so it is not a JAX-RS resource method or sub-resource locator, and it is not routed", element);
        }
        if (element.hasStereotype(HttpMethod.class) && element.isPrivate() && runtimeRoutes) {
            // a private method cannot be executable
            element.removeAnnotationIf(annotation -> annotation.getAnnotationName().startsWith("io.micronaut.http.annotation."));
            return;
        }
        if (element.hasStereotype(HttpMethod.class)) {
            if (!runtimeRoutes && currentClassElement != null && !currentClassElement.hasAnnotation(Controller.class) && !currentClassElement.isAbstract()) {
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
            final ParameterElement[] parameters = element.getParameters();
            for (ParameterElement parameter : parameters) {
                final List<Class<? extends Annotation>> unsupported = getUnsupportedParameterAnnotations();
                for (Class<? extends Annotation> annType : unsupported) {
                    if (parameter.hasAnnotation(annType)) {
                        context.fail("Unsupported JAX-RS annotation used on method: " + annType.getName(), parameter);
                    }
                }
                visitParamOrField(parameter);
                String parameterTypeName = parameter.getType().getName();
                if (JAX_RS_BINDING_ANNOTATIONS.stream().noneMatch(parameter::hasAnnotation)
                    && JAX_RS_BINDING_TYPES.stream().noneMatch(cl -> cl.equals(parameterTypeName))) {
                    // unannotated, implicit @Body
                    parameter.annotate(Body.class);
                    parameter.annotate(Nullable.class); // JAX-RS controller bodies are nullable by default
                }
            }
        }
    }

    @Override
    public void visitField(FieldElement element, VisitorContext context) {
        visitParamOrField(element);
        if (runtimeRoutes) {
            // the runtime routes inject the fields of a type created per request
            if (element.hasAnnotation(Context.class) && JaxRsResources.isCreatedPerRequest(element.getOwningType())) {
                element.removeAnnotation(Inject.class);
            }
            return;
        }
        if (element.hasAnnotation(HeaderParam.class) ||
            element.hasAnnotation(QueryParam.class) ||
            element.hasAnnotation(FormParam.class) ||
            element.hasAnnotation(MatrixParam.class) ||
            element.hasAnnotation(PathParam.class) ||
            element.hasAnnotation(CookieParam.class) ||
            element.hasAnnotation(BeanParam.class)
        ) {
            context.fail("Request scoped bean parameters are currently not supported", element); // todo
        }
    }

    /**
     * The JAX-RS annotations of a class are not inherited by its subclasses (JAX-RS 3.6), e.g. its
     * {@code @Produces}, nor the Micronaut ones they are mapped to. The {@code @Path} of a
     * superclass is kept: a subclass is routed with it.
     */
    private static void discardInheritedClassAnnotations(ClassElement element) {
        AnnotationMetadata declared = element.getDeclaredMetadata();
        for (String name : element.getAnnotationNames()) {
            if (name.startsWith(JAX_RS_PACKAGE) && !KEPT_CLASS_ANNOTATIONS.contains(name) && !declared.hasDeclaredAnnotation(name)) {
                element.removeAnnotation(name);
                String mapped = MAPPED_CLASS_ANNOTATIONS.get(name);
                if (mapped != null && !declared.hasDeclaredAnnotation(mapped)) {
                    element.removeAnnotation(mapped);
                }
            }
        }
    }

    /**
     * The annotations of a super method are inherited only when the method declares no JAX-RS
     * annotation, on itself or on its parameters, otherwise they are all ignored (JAX-RS 3.6): the
     * method, and its parameters, lose the JAX-RS annotations they inherit, and the Micronaut ones
     * these are mapped to.
     */
    private static void discardInheritedAnnotations(MethodElement element) {
        if (element.getOverriddenMethods().isEmpty()) {
            return;
        }
        boolean declares = declaresJaxRsAnnotation(element.getDeclaredMethodAnnotationMetadata());
        for (ParameterElement parameter : element.getParameters()) {
            declares |= declaresJaxRsAnnotation(parameter.getAnnotationMetadata().getDeclaredMetadata());
        }
        if (!declares) {
            inheritFromEffectiveMethod(element);
            return;
        }
        discardInherited(element, element.getMethodAnnotationMetadata(), element.getDeclaredMethodAnnotationMetadata());
        for (ParameterElement parameter : element.getParameters()) {
            discardInherited(parameter, parameter, parameter.getAnnotationMetadata().getDeclaredMetadata());
        }
    }

    /**
     * A method without JAX-RS annotations inherits those of one overridden method (JAX-RS 3.6): the
     * nearest one of a superclass that declares any, else one of an interface. The annotations of
     * the others, which are merged with them, are discarded.
     */
    private static void inheritFromEffectiveMethod(MethodElement element) {
        List<MethodElement> declaring = element.getOverriddenMethods().stream()
            .filter(JaxRsTypeElementVisitor::declaresJaxRsAnnotation)
            .toList();
        if (declaring.size() < 2) {
            // the merged annotations are the ones of the only method that declares any
            return;
        }
        MethodElement effective = null;
        for (ClassElement type = element.getOwningType().getSuperType().orElse(null); type != null && effective == null;
             type = type.getSuperType().orElse(null)) {
            String typeName = type.getName();
            effective = declaring.stream().filter(method -> method.getDeclaringType().getName().equals(typeName)).findFirst().orElse(null);
        }
        if (effective == null) {
            effective = declaring.stream().filter(method -> method.getDeclaringType().isInterface()).findFirst().orElse(declaring.get(0));
        }
        inherit(element, element.getMethodAnnotationMetadata(), element.getDeclaredMethodAnnotationMetadata(), effective.getDeclaredMethodAnnotationMetadata());
        ParameterElement[] parameters = element.getParameters();
        ParameterElement[] effectiveParameters = effective.getParameters();
        for (int i = 0; i < parameters.length && i < effectiveParameters.length; i++) {
            inherit(parameters[i], parameters[i], parameters[i].getAnnotationMetadata().getDeclaredMetadata(),
                effectiveParameters[i].getAnnotationMetadata().getDeclaredMetadata());
        }
    }

    /**
     * Replace the inherited JAX-RS annotations, and the Micronaut ones they are mapped to, with the
     * ones of the effective overridden method.
     */
    private static void inherit(MutableAnnotationMetadataDelegate<?> element, AnnotationMetadata metadata, AnnotationMetadata declared,
                                AnnotationMetadata source) {
        discardInherited(element, metadata, declared);
        for (String name : source.getDeclaredAnnotationNames()) {
            AnnotationValue<Annotation> value = source.getDeclaredAnnotation(name);
            if (value != null && isRequestAnnotation(source, name)) {
                element.annotate(value);
            }
        }
    }

    private static boolean declaresJaxRsAnnotation(MethodElement method) {
        boolean declares = declaresJaxRsAnnotation(method.getDeclaredMethodAnnotationMetadata());
        for (ParameterElement parameter : method.getParameters()) {
            declares |= declaresJaxRsAnnotation(parameter.getAnnotationMetadata().getDeclaredMetadata());
        }
        return declares;
    }

    private static boolean declaresJaxRsAnnotation(AnnotationMetadata declared) {
        // a custom request method designator too, e.g. a PATCH
        return declared.getDeclaredAnnotationNames().stream().anyMatch(name -> name.startsWith(JAX_RS_PACKAGE))
            || declared.hasDeclaredStereotype(HttpMethod.class);
    }

    private static boolean isRequestAnnotation(AnnotationMetadata metadata, String name) {
        return name.startsWith(JAX_RS_PACKAGE) || name.startsWith(HTTP_ANNOTATION_PACKAGE)
            || metadata.getAnnotationNamesByStereotype(HttpMethod.class.getName()).contains(name);
    }

    private static void discardInherited(MutableAnnotationMetadataDelegate<?> element, AnnotationMetadata metadata, AnnotationMetadata declared) {
        Set<String> declaredNames = declared.getDeclaredAnnotationNames();
        for (String name : metadata.getAnnotationNames()) {
            if (!declaredNames.contains(name) && isRequestAnnotation(metadata, name)) {
                element.removeAnnotation(name);
            }
        }
        for (String stereotype : List.of(HttpMethod.class.getName(), HttpMethodMapping.class.getName(), UriMapping.class.getName())) {
            if (!declared.hasStereotype(stereotype)) {
                element.removeStereotype(stereotype);
            }
        }
    }

    private static void visitParamOrField(TypedElement parameter) {
        mapParam(parameter, HeaderParam.class, Header.class);
        mapParam(parameter, FormParam.class, Body.class);
        mapParam(parameter, CookieParam.class, CookieValue.class);
        mapParam(parameter, PathParam.class, PathVariable.class);
        if (parameter.hasAnnotation(QueryParam.class)) {
            annotateQueryParam(parameter);
        }
    }

    /**
     * Introspect a class with its fields and methods of any visibility, including its injection
     * points: its {@code @Context} fields and setters, mapped to {@code @Inject}, are set on an
     * instance that is not a bean. A class already annotated with {@code @Introspected} keeps its
     * members, it only includes its injection points.
     */
    private static void introspect(ClassElement element) {
        if (element.hasAnnotation(Introspected.class)) {
            element.annotate(Introspected.class, builder -> builder.member(BeanProperties.MEMBER_INCLUDE_INJECTED, true));
        } else {
            element.annotate(Introspected.class, builder -> builder
                .member("accessKind", new Introspected.AccessKind[]{Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD})
                .member("visibility", new Introspected.Visibility[]{Introspected.Visibility.ANY})
                .member(BeanProperties.MEMBER_INCLUDE_INJECTED, true));
        }
    }

    private static void annotateQueryParam(TypedElement parameter) {
        AnnotationValueBuilder<QueryParam> builder = AnnotationValue.builder(QueryParam.class);
        annotateDefaultAndNullable(parameter, builder);
        parameter.annotate(
            builder
            .stereotype(AnnotationValue.builder(Bindable.class).build()).build()
        );
    }

    private static void annotateDefaultAndNullable(TypedElement parameter, AnnotationValueBuilder<?> builder) {
        if (!parameter.isNonNull()) {
            if (parameter.isPrimitive()) {
                if (parameter.getType().isAssignable(boolean.class)) {
                    builder.member("defaultValue", "false");
                } else {
                    builder.member("defaultValue", "0");
                }
            } else {
                parameter.annotate(Nullable.class);
            }
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

    private List<Class<? extends Annotation>> getUnsupportedParameterAnnotations() {
        return runtimeRoutes
            ? List.of()
            : Arrays.asList(MatrixParam.class, BeanParam.class, Encoded.class);
    }

    @Override
    public void start(VisitorContext visitorContext) {
        for (Class<?> type : BINDABLE_TYPES) {
            visitorContext.getClassElement(type).ifPresent(bindable -> bindable.annotate(Bindable.class));
        }
    }
}
