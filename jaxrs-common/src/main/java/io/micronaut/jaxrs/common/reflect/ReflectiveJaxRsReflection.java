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
package io.micronaut.jaxrs.common.reflect;

import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.core.reflect.InstantiationUtils;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.reflection.ReflectionAnnotations;
import io.micronaut.reflection.ReflectionArguments;
import io.micronaut.reflection.ReflectionBeanDefinition;
import io.micronaut.reflection.ReflectionBeanIntrospection;
import jakarta.ws.rs.HttpMethod;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The {@link JaxRsReflection} of the {@code micronaut-reflection} module: the service the
 * service loader finds when the module is on the classpath.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
public final class ReflectiveJaxRsReflection implements JaxRsReflection {

    private static final Set<Introspected.AccessKind> FIELDS_AND_METHODS = Set.of(Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD);

    // the descriptions, owned by the class they describe so that they do not retain its class loader
    private static final ClassValue<Optional<BeanIntrospection<?>>> MEMBER_INTROSPECTIONS = new ClassValue<>() {
        @Override
        protected Optional<BeanIntrospection<?>> computeValue(Class<?> type) {
            return ReflectionBeanIntrospection.isIntrospectable(type)
                ? Optional.of(ReflectionBeanIntrospection.of(type, FIELDS_AND_METHODS))
                : Optional.empty();
        }
    };
    private static final ClassValue<Optional<BeanIntrospection<?>>> INSTANTIATING_INTROSPECTIONS = new ClassValue<>() {
        @Override
        protected Optional<BeanIntrospection<?>> computeValue(Class<?> type) {
            return ReflectionBeanIntrospection.isIntrospectable(type)
                ? Optional.of(ReflectionBeanIntrospection.of(type))
                : Optional.empty();
        }
    };
    // the inherited annotations of the methods of a class, by method
    private static final ClassValue<Map<Method, InheritedAnnotations>> INHERITED_ANNOTATIONS = new ClassValue<>() {
        @Override
        protected Map<Method, InheritedAnnotations> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    @Override
    public boolean isReflective() {
        return true;
    }

    @Override
    public AnnotationMetadata annotationMetadata(Class<?> type) {
        return ReflectionAnnotations.metadataOf(type);
    }

    @Override
    public AnnotationMetadata annotationMetadata(Method method) {
        // with the JAX-RS annotations it inherits (JAX-RS 3.6), as the metadata recorded at compile time
        return inherited(method.getDeclaringClass(), method).metadata();
    }

    @Override
    public AnnotationMetadata annotationMetadata(Annotation[] annotations) {
        if (annotations.length == 0) {
            return AnnotationMetadata.EMPTY_METADATA;
        }
        List<Annotation> real = new ArrayList<>(annotations.length);
        for (Annotation annotation : annotations) {
            if (annotation.annotationType() != null) {
                // a fake annotation of a test has no type
                real.add(annotation);
            }
        }
        return ReflectionAnnotations.metadataOf(real.toArray(Annotation[]::new));
    }

    @Override
    public Annotation[] annotations(AnnotationMetadata metadata, ClassLoader classLoader) {
        List<Annotation> annotations = new ArrayList<>();
        for (String name : metadata.getAnnotationNames()) {
            AnnotationValue<Annotation> value = metadata.getAnnotation(name);
            if (value == null) {
                continue;
            }
            try {
                annotations.add(ReflectionAnnotations.synthesize(value, classLoader));
            } catch (IllegalArgumentException e) {
                // not an annotation the provider can see
            }
        }
        return annotations.toArray(Annotation[]::new);
    }

    @Override
    public AnnotationMetadata methodAnnotations(ExecutableMethod<?, ?> method) {
        return inherited(method.getDeclaringType(), method.getTargetMethod()).metadata();
    }

    @Override
    public Annotation[] annotations(ExecutableMethod<?, ?> method) {
        // a copy, which the caller may change
        return inherited(method.getDeclaringType(), method.getTargetMethod()).annotations().clone();
    }

    @Override
    public AnnotationMetadata methodAnnotations(Class<?> resourceClass, ExecutableMethod<?, ?> method) {
        return inherited(resourceClass, method.getTargetMethod()).metadata();
    }

    @Override
    public Annotation[] annotations(Class<?> resourceClass, ExecutableMethod<?, ?> method) {
        // a copy, which the caller may change
        return inherited(resourceClass, method.getTargetMethod()).annotations().clone();
    }

    @Override
    public Method targetMethod(ExecutableMethod<?, ?> method) {
        return method.getTargetMethod();
    }

    @Override
    public AnnotationMetadata methodAnnotations(Class<?> type, String name, Class<?>... parameterTypes) {
        return inherited(type, targetMethod(type, name, parameterTypes)).metadata();
    }

    @Override
    public Method targetMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        return ReflectionUtils.getRequiredMethod(type, name, parameterTypes);
    }

    @Override
    public @Nullable Argument<?> resolveGeneric(Class<?> type, Class<?> genericType) {
        return ReflectionArguments.resolveGenericToArgument(type, genericType);
    }

    @Override
    public Argument<?> argument(Type type) {
        return ReflectionArguments.of(type);
    }

    @Override
    public Class<?>[] interfaces(Class<?> type) {
        return type.getInterfaces();
    }

    @Override
    public <T> @Nullable BeanIntrospection<T> introspection(Class<T> type) {
        BeanIntrospection<T> introspection = BeanIntrospector.SHARED.findIntrospection(type).orElse(null);
        if (introspection != null) {
            return introspection;
        }
        return reflective(MEMBER_INTROSPECTIONS, type);
    }

    @SuppressWarnings("unchecked")
    private static <T> @Nullable BeanIntrospection<T> reflective(ClassValue<Optional<BeanIntrospection<?>>> introspections, Class<T> type) {
        return (BeanIntrospection<T>) introspections.get(type).orElse(null);
    }

    @Override
    public <T> T instantiate(Class<T> type) {
        BeanIntrospection<T> introspection = BeanIntrospector.SHARED.findIntrospection(type).orElse(null);
        if (introspection != null) {
            return introspection.instantiate();
        }
        // a reflective introspection reaches a class or constructor that is not public
        BeanIntrospection<T> reflective = reflective(INSTANTIATING_INTROSPECTIONS, type);
        return reflective != null ? reflective.instantiate() : InstantiationUtils.instantiate(type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T instantiate(Class<T> type, Function<Class<?>, @Nullable Object> values) {
        Constructor<?>[] constructors = type.getConstructors();
        Arrays.sort(constructors, Comparator.comparingInt((Constructor<?> c) -> c.getParameterCount()).reversed());
        for (Constructor<?> constructor : constructors) {
            if (constructor.getParameterCount() == 0) {
                // the one of the introspection, which reaches a class that is not public
                break;
            }
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            Object[] arguments = new Object[parameterTypes.length];
            boolean usable = true;
            for (int i = 0; i < parameterTypes.length && usable; i++) {
                arguments[i] = values.apply(parameterTypes[i]);
                usable = arguments[i] != null;
            }
            if (usable) {
                // e.g. the public constructor of a nested class that is not public
                constructor.trySetAccessible();
                return (T) invoke(() -> constructor.newInstance(arguments));
            }
        }
        return instantiate(type);
    }

    @Override
    public @Nullable Function<String, Object> stringConversion(Class<?> type) {
        if (type.isPrimitive() || type.isArray() || ClassUtils.isJavaLangType(type)) {
            return null;
        }
        // valueOf before fromString, except for an enum
        for (String factory : type.isEnum() ? new String[]{"fromString", "valueOf"} : new String[]{"valueOf", "fromString"}) {
            Method method = ReflectionUtils.findMethod(type, factory, String.class).orElse(null);
            // a public one: a private helper of the type is not its conversion
            if (method != null && Modifier.isStatic(method.getModifiers()) && Modifier.isPublic(method.getModifiers())
                && type.isAssignableFrom(method.getReturnType())) {
                return value -> invoke(() -> method.invoke(null, value));
            }
        }
        Constructor<?> constructor = ReflectionUtils.findConstructor(type, String.class).orElse(null);
        if (constructor != null && Modifier.isPublic(constructor.getModifiers())) {
            return value -> invoke(() -> constructor.newInstance(value));
        }
        return null;
    }

    /**
     * The annotations of a method of a class, which may implement it with the method of a
     * superclass, e.g. one without annotations implementing an annotated method of an interface
     * of the class: they are cached by class.
     */
    private InheritedAnnotations inherited(Class<?> type, Method method) {
        Class<?> owner = method.getDeclaringClass().isAssignableFrom(type) ? type : method.getDeclaringClass();
        return INHERITED_ANNOTATIONS.get(owner).computeIfAbsent(method, m -> {
            Annotation[] annotations = inheritedAnnotations(owner, m);
            return new InheritedAnnotations(annotations, annotationMetadata(annotations));
        });
    }

    /**
     * The annotations of a method, with the JAX-RS ones it inherits (JAX-RS 3.6): those of the
     * method it overrides, of a superclass first, then of an interface, when it declares no JAX-RS
     * annotation, on itself or on its parameters.
     */
    private static Annotation[] inheritedAnnotations(Class<?> owner, Method method) {
        if (declaresJaxRsAnnotation(method)) {
            return method.getAnnotations();
        }
        Method inherited = inheritedMethod(owner, method);
        if (inherited == null) {
            return method.getAnnotations();
        }
        Set<Annotation> annotations = new LinkedHashSet<>(List.of(method.getAnnotations()));
        annotations.addAll(List.of(inherited.getAnnotations()));
        return annotations.toArray(Annotation[]::new);
    }

    private static @Nullable Method inheritedMethod(Class<?> owner, Method method) {
        List<Class<?>> interfaces = new ArrayList<>();
        // from the class the method belongs to: the interfaces of a subclass of the class that
        // declares it are overridden by it too
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            if (type != method.getDeclaringClass()) {
                Method overridden = declaredMethod(type, method);
                if (overridden != null && declaresJaxRsAnnotation(overridden)) {
                    return overridden;
                }
            }
            interfaces.addAll(List.of(type.getInterfaces()));
        }
        // the interfaces after the superclasses, the nearest first
        for (int i = 0; i < interfaces.size(); i++) {
            Class<?> type = interfaces.get(i);
            Method overridden = declaredMethod(type, method);
            if (overridden != null && declaresJaxRsAnnotation(overridden)) {
                return overridden;
            }
            interfaces.addAll(List.of(type.getInterfaces()));
        }
        return null;
    }

    /**
     * The method of a type that a method overrides: the one with the same parameter types, else, for
     * a generic one, e.g. {@code get(T)} overridden by {@code get(String)}, the one whose erased
     * parameter types the class of the method bridges to it.
     */
    private static @Nullable Method declaredMethod(Class<?> type, Method method) {
        try {
            Method declared = type.getDeclaredMethod(method.getName(), method.getParameterTypes());
            if (!declared.isBridge()) {
                return overridable(declared) ? declared : null;
            }
        } catch (NoSuchMethodException e) {
            // a generic method, which is erased to other parameter types
        }
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (Method declared : type.getDeclaredMethods()) {
            if (declared.isBridge() || declared.isSynthetic() || !overridable(declared) || !declared.getName().equals(method.getName())
                || declared.getParameterCount() != parameterTypes.length) {
                continue;
            }
            // a generic method is overridden through a bridge method with its erased parameter types
            if (isAssignable(declared.getParameterTypes(), parameterTypes) && hasBridge(method, declared.getParameterTypes())) {
                return declared;
            }
        }
        return null;
    }

    private static boolean isAssignable(Class<?>[] types, Class<?>[] values) {
        for (int i = 0; i < types.length; i++) {
            if (!types[i].isAssignableFrom(values[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasBridge(Method method, Class<?>[] parameterTypes) {
        try {
            return method.getDeclaringClass().getDeclaredMethod(method.getName(), parameterTypes).isBridge();
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean overridable(Method method) {
        return !Modifier.isPrivate(method.getModifiers()) && !Modifier.isStatic(method.getModifiers());
    }

    private static boolean declaresJaxRsAnnotation(Method method) {
        if (hasJaxRsAnnotation(method.getDeclaredAnnotations())) {
            return true;
        }
        for (Annotation[] parameterAnnotations : method.getParameterAnnotations()) {
            if (hasJaxRsAnnotation(parameterAnnotations)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasJaxRsAnnotation(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            Class<? extends Annotation> type = annotation.annotationType();
            // a custom request method designator too, e.g. a PATCH
            if (type.getName().startsWith("jakarta.ws.rs.") || type.isAnnotationPresent(HttpMethod.class)) {
                return true;
            }
        }
        return false;
    }

    private static Object invoke(ReflectiveCall call) {
        try {
            return call.call();
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw cause instanceof RuntimeException runtime ? runtime : new IllegalArgumentException(cause.getMessage(), cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void injectFields(Object instance, Class<? extends Annotation> annotation, Function<Class<?>, @Nullable Object> values) {
        // the fields of any visibility, of the class and its super classes, and the bean setters: a
        // generated introspection, which describes the members the class can reach, may leave them out
        BeanIntrospection<Object> introspection = reflective(MEMBER_INTROSPECTIONS, (Class<Object>) instance.getClass());
        if (introspection == null) {
            return;
        }
        for (BeanProperty<Object, Object> property : introspection.getBeanProperties()) {
            if (!property.hasAnnotation(annotation) || property.isReadOnly()) {
                continue;
            }
            Object value = values.apply(property.getType());
            if (value != null) {
                try {
                    if (property.isWriteOnly() || property.get(instance) == null) {
                        property.set(instance, value);
                    }
                } catch (RuntimeException e) {
                    // a field that cannot be set is left as it is
                }
            }
        }
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> @Nullable RuntimeBeanDefinition<T> beanDefinition(Class<T> type) {
        if (!ReflectionBeanDefinition.isDefinable(type)) {
            return null;
        }
        // found by each of its types: a runtime definition is only indexed by the types it exposes
        return ReflectionBeanDefinition.builder((Class) type).exposedTypes(supertypes(type)).build();
    }

    /**
     * A class and every class and interface it extends or implements, but {@code Object}.
     */
    private static Class<?>[] supertypes(Class<?> type) {
        Set<Class<?>> types = new LinkedHashSet<>();
        List<Class<?>> pending = new ArrayList<>();
        pending.add(type);
        while (!pending.isEmpty()) {
            Class<?> next = pending.remove(pending.size() - 1);
            if (next == null || next == Object.class || !types.add(next)) {
                continue;
            }
            pending.add(next.getSuperclass());
            pending.addAll(List.of(next.getInterfaces()));
        }
        return types.toArray(Class<?>[]::new);
    }

    /**
     * The annotations of a method, with the inherited ones, and their metadata.
     *
     * @param annotations The annotations, never handed out
     * @param metadata    The metadata
     */
    private record InheritedAnnotations(Annotation[] annotations, AnnotationMetadata metadata) {
    }

    /**
     * A reflective call.
     */
    @FunctionalInterface
    private interface ReflectiveCall {
        Object call() throws ReflectiveOperationException;
    }
}
