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
package io.micronaut.jaxrs.processor;

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.TypedElement;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.inject.writer.GeneratedFile;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;

/**
 * Generates the String conversions of the types of the JAX-RS parameters of a class, of its fields
 * and of the parameters of its methods and constructors, also of the elements of a collection of
 * them: the conversion of a type with a public static {@code valueOf(String)} or
 * {@code fromString(String)} method, or a public constructor of a String (JAX-RS 3.2), which the
 * runtime loads as services, without reflection.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
final class StringConversions {

    private static final String SERVICE = "io.micronaut.jaxrs.common.reflect.StringConversion";
    private static final String SUFFIX = "$JaxRsStringConversion";
    private static final List<Class<? extends Annotation>> PARAMETERS = List.of(QueryParam.class, MatrixParam.class,
        HeaderParam.class, CookieParam.class, FormParam.class, PathParam.class);
    private static final Set<String> COLLECTIONS = Set.of(List.class.getName(), Set.class.getName(),
        SortedSet.class.getName(), Collection.class.getName());

    // the classes and types whose conversion this compilation generated: a class owns the
    // conversions of its own types, which an incremental build deletes with it
    private final Set<String> generated = new HashSet<>();

    void reset() {
        generated.clear();
    }

    /**
     * Generate the conversions of the types of the parameters of a class.
     *
     * @param element The class
     * @param context The visitor context
     */
    void generate(ClassElement element, VisitorContext context) {
        if (context.getClassElement(SERVICE).isEmpty()) {
            // the runtime is not on the classpath
            return;
        }
        for (FieldElement field : element.getEnclosedElements(ElementQuery.ALL_FIELDS)) {
            generate(field, element, context);
        }
        for (MethodElement method : element.getEnclosedElements(ElementQuery.ALL_METHODS)) {
            ParameterElement[] parameters = method.getParameters();
            if (parameters.length == 1) {
                // a setter annotated with a parameter annotation
                generate(method, parameters[0], element, context);
            }
            for (ParameterElement parameter : parameters) {
                generate(parameter, parameter, element, context);
            }
        }
        for (ConstructorElement constructor : element.getEnclosedElements(ElementQuery.CONSTRUCTORS)) {
            for (ParameterElement parameter : constructor.getParameters()) {
                generate(parameter, parameter, element, context);
            }
        }
    }

    private void generate(FieldElement field, ClassElement owner, VisitorContext context) {
        generate(field, field, owner, context);
    }

    private void generate(Element annotated, TypedElement element, ClassElement owner, VisitorContext context) {
        if (PARAMETERS.stream().noneMatch(annotated::hasAnnotation)) {
            return;
        }
        ClassElement type = element.getGenericType();
        if (COLLECTIONS.contains(type.getName())) {
            type = type.getFirstTypeArgument().orElse(null);
            if (type == null) {
                return;
            }
        } else if (type.isArray()) {
            // the elements of an array (JAX-RS 3.2)
            type = type.fromArray();
        }
        if (type.isPrimitive() || type.isArray() || type.getPackageName().startsWith("java.") || !isAccessible(type)
            || generated.contains(owner.getName() + " " + type.getName())) {
            return;
        }
        String factory = factory(type);
        if (factory == null) {
            return;
        }
        generated.add(owner.getName() + " " + type.getName());
        // in the package of the class, which does not split the package of a library type, unless
        // the type is reachable only from its own package
        String packageName = isPublic(type) ? owner.getPackageName() : type.getPackageName();
        // the source name, also of a nested class whose name has a $
        String typeName = type.getCanonicalName();
        // unique for each class and type: the escaped binary names have no $
        String simpleName = escape(owner.getName()) + "$" + escape(type.getName()) + SUFFIX;
        String className = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        GeneratedFile file = context.visitGeneratedSourceFile(packageName, simpleName, owner).orElse(null);
        if (file == null) {
            return;
        }
        try (Writer writer = file.openWriter()) {
            if (!packageName.isEmpty()) {
                writer.write("package " + packageName + ";\n\n");
            }
            writer.write("""
                /** The String conversion of %1$s, generated for the JAX-RS parameters. */
                @io.micronaut.core.annotation.Generated
                public final class %2$s implements %3$s {
                    @Override
                    public Class<?> type() {
                        return %1$s.class;
                    }

                    @Override
                    public Object convert(String value) throws Exception {
                        try {
                            return %4$s(value);
                        } catch (Exception | Error e) {
                            throw e;
                        } catch (Throwable e) {
                            // declared by the factory
                            throw new java.lang.reflect.InvocationTargetException(e);
                        }
                    }
                }
                """.formatted(typeName, simpleName, SERVICE, factory.replace("TYPE", typeName)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        context.visitServiceDescriptor(SERVICE, className, owner);
    }

    /**
     * The factory of a type, in the order of the reflective conversion: {@code valueOf} before
     * {@code fromString}, except for an enum, then the constructor.
     */
    private static @Nullable String factory(ClassElement type) {
        String[] factories = type.isEnum() ? new String[]{"fromString", "valueOf"} : new String[]{"valueOf", "fromString"};
        for (String name : factories) {
            boolean found = type.getEnclosedElements(ElementQuery.ALL_METHODS.named(name)).stream()
                .anyMatch(method -> method.isStatic() && method.isPublic() && isStringParameter(method.getParameters())
                    && method.getGenericReturnType().isAssignable(type) && isInherited(method, type));
            if (found) {
                return "TYPE." + name;
            }
        }
        if (type.isEnum()) {
            // the implicit valueOf of an enum
            return "TYPE.valueOf";
        }
        boolean instantiable = !type.isAbstract() && !type.isInterface()
            && (type.getEnclosingType().isEmpty() || type.isStatic());
        if (instantiable && type.getEnclosedElements(ElementQuery.CONSTRUCTORS).stream()
            .anyMatch(constructor -> constructor.isPublic() && isStringParameter(constructor.getParameters()))) {
            return "new TYPE";
        }
        return null;
    }

    private static boolean isStringParameter(ParameterElement[] parameters) {
        return parameters.length == 1 && !parameters[0].getType().isArray()
            && parameters[0].getType().getName().equals(String.class.getName());
    }

    /**
     * Whether a static method is one of the type: the static methods of an interface are not
     * inherited, by the types that implement it nor by its subinterfaces.
     */
    private static boolean isInherited(MethodElement method, ClassElement type) {
        ClassElement declaring = method.getDeclaringType();
        return !declaring.isInterface() || declaring.getName().equals(type.getName());
    }

    /**
     * A binary name as a part of an identifier, without collisions: {@code _} is escaped, and the
     * package and nesting separators are replaced.
     */
    private static String escape(String binaryName) {
        StringBuilder escaped = new StringBuilder(binaryName.length() + 8);
        for (int i = 0; i < binaryName.length(); i++) {
            char c = binaryName.charAt(i);
            switch (c) {
                case '.' -> escaped.append("_0");
                case '_' -> escaped.append("_1");
                case '$' -> escaped.append("_2");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private static boolean isPublic(ClassElement type) {
        for (ClassElement current = type; current != null; current = current.getEnclosingType().orElse(null)) {
            if (!current.isPublic()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAccessible(ClassElement type) {
        for (ClassElement current = type; current != null; current = current.getEnclosingType().orElse(null)) {
            if (current.isPrivate()) {
                return false;
            }
        }
        return true;
    }
}
