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
import io.micronaut.core.annotation.AnnotationValueProvider;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.beans.BeanMethod;
import io.micronaut.core.io.service.SoftServiceLoader;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.TypeHierarchies;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.annotation.AnnotationMetadataHierarchy;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The {@link JaxRsReflection} of the metadata the annotation processors generate, without
 * reflection.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
final class MetadataJaxRsReflection implements JaxRsReflection {

    private static final String MICRONAUT_ANNOTATIONS = "io.micronaut.";

    // the application annotations of the methods of a class, by executable method or by MethodKey
    private static final ClassValue<Map<Object, MethodAnnotations>> METHOD_ANNOTATIONS = new ClassValue<>() {
        @Override
        protected Map<Object, MethodAnnotations> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    @Override
    public boolean isReflective() {
        return false;
    }

    /**
     * The annotations of the introspection of a class. The annotations of a class without one,
     * e.g. a bean, are the ones of its bean definition: the ones of a class the annotation
     * processors never saw need reflection, they are empty.
     */
    @Override
    public AnnotationMetadata annotationMetadata(Class<?> type) {
        BeanIntrospection<?> introspection = introspection(type);
        return introspection == null ? AnnotationMetadata.EMPTY_METADATA : introspection.getAnnotationMetadata();
    }

    @Override
    public AnnotationMetadata annotationMetadata(Method method) {
        // the method of an introspected class, e.g. of a resource: its annotations were recorded
        // at compile time, a Method given by the application is matched by name and parameter types
        BeanIntrospection<?> introspection = introspection(method.getDeclaringClass());
        if (introspection != null) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            for (BeanMethod<?, Object> beanMethod : introspection.getBeanMethods()) {
                if (beanMethod.getName().equals(method.getName()) && Arrays.equals(Argument.toClassArray(beanMethod.getArguments()), parameterTypes)) {
                    // the annotations of the method, like the ones of reflection: not the ones of its class
                    AnnotationMetadata metadata = beanMethod.getAnnotationMetadata();
                    return metadata instanceof AnnotationMetadataHierarchy hierarchy ? hierarchy.getDeclaredMetadata() : metadata;
                }
            }
        }
        throw new UnsupportedOperationException(unsupported("The annotations of the method " + method));
    }

    @Override
    public AnnotationMetadata annotationMetadata(Annotation[] annotations) {
        if (annotations.length == 0) {
            return AnnotationMetadata.EMPTY_METADATA;
        }
        // the members of the annotations Micronaut synthesized, else only the types: the members of
        // other annotations are read with reflection
        MutableAnnotationMetadata metadata = new MutableAnnotationMetadata();
        for (Annotation annotation : annotations) {
            Class<? extends Annotation> type = annotation.annotationType();
            if (annotation instanceof AnnotationValueProvider<?> provider) {
                metadata.addDeclaredAnnotation(type.getName(), provider.annotationValue().getValues());
            } else if (type != null) {
                metadata.addDeclaredAnnotation(type.getName(), Map.of());
            }
        }
        return metadata;
    }

    @Override
    public Annotation[] annotations(AnnotationMetadata metadata, ClassLoader classLoader) {
        return metadata.synthesizeAll();
    }

    @Override
    public AnnotationMetadata methodAnnotations(ExecutableMethod<?, ?> method) {
        return methodAnnotationsOf(method).metadata();
    }

    private static MethodAnnotations methodAnnotationsOf(ExecutableMethod<?, ?> method) {
        return METHOD_ANNOTATIONS.get(method.getDeclaringType())
            .computeIfAbsent(method, m -> new MethodAnnotations(applicationAnnotations(method.getAnnotationMetadata())));
    }

    @Override
    public AnnotationMetadata methodAnnotations(Class<?> type, String name, Class<?>... parameterTypes) {
        MethodAnnotations cached = METHOD_ANNOTATIONS.get(type).get(new MethodKey(name, List.of(parameterTypes)));
        if (cached != null) {
            return cached.metadata();
        }
        BeanIntrospection<?> introspection = introspection(type);
        if (introspection != null) {
            for (BeanMethod<?, Object> method : introspection.getBeanMethods()) {
                if (method.getName().equals(name) && Arrays.equals(Argument.toClassArray(method.getArguments()), parameterTypes)) {
                    return METHOD_ANNOTATIONS.get(type)
                        .computeIfAbsent(new MethodKey(name, List.of(parameterTypes)),
                            key -> new MethodAnnotations(applicationAnnotations(method.getAnnotationMetadata())))
                        .metadata();
                }
            }
        }
        // like the reflective implementation, which fails for a method it cannot find: the method
        // of a class the processors did not introspect needs reflection
        throw new UnsupportedOperationException(unsupported("The annotations of the method " + type.getName() + "#" + name));
    }

    @Override
    public Method targetMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        // the one method the metadata names: no members are scanned
        return ResourceMethods.find(type, name, parameterTypes);
    }

    /**
     * The annotations of the application of a method, without the ones the processors map them to.
     */
    private static AnnotationMetadata applicationAnnotations(AnnotationMetadata metadata) {
        AnnotationMetadata methodMetadata = metadata instanceof AnnotationMetadataHierarchy hierarchy ? hierarchy.getDeclaredMetadata() : metadata;
        MutableAnnotationMetadata annotations = new MutableAnnotationMetadata();
        for (String name : methodMetadata.getAnnotationNames()) {
            if (!name.startsWith(MICRONAUT_ANNOTATIONS)) {
                AnnotationValue<Annotation> value = methodMetadata.getAnnotation(name);
                annotations.addDeclaredAnnotation(name, value == null ? Map.of() : value.getValues());
            }
        }
        return annotations;
    }

    @Override
    public Annotation[] annotations(ExecutableMethod<?, ?> method) {
        // a copy, which the caller may change
        return methodAnnotationsOf(method).annotations().clone();
    }

    @Override
    public Method targetMethod(ExecutableMethod<?, ?> method) {
        // the one method the metadata names: no members are scanned
        return ResourceMethods.find(method.getDeclaringType(), method.getMethodName(), method.getArgumentTypes());
    }

    @Override
    public @Nullable Argument<?> resolveGeneric(Class<?> type, Class<?> genericType) {
        // the generic hierarchy the processor recorded, e.g. of a provider
        return TypeHierarchies.find(type)
            .flatMap(hierarchy -> hierarchy.getTypeClosure(hierarchy.getDeclaration()).stream()
                .filter(superType -> superType.getType() == genericType)
                .findFirst())
            // else the generic signature of the class, e.g. of an anonymous InvocationCallback
            .orElseGet(() -> GenericSignatures.resolve(type, genericType));
    }

    @Override
    public Argument<?> argument(Type type) {
        return Argument.of(type);
    }

    @Override
    public <T> @Nullable BeanIntrospection<T> introspection(Class<T> type) {
        BeanIntrospection<T> introspection = BeanIntrospector.SHARED.findIntrospection(type).orElse(null);
        ClassLoader classLoader = type.getClassLoader();
        if (introspection != null && introspection.getBeanType() != type && classLoader != null) {
            // the introspection of a class of the same name of another class loader, e.g. the one
            // of an application deployed in its own class loader: the one of the class loader of the class
            introspection = BeanIntrospector.forClassLoader(classLoader).findIntrospection(type)
                .filter(found -> found.getBeanType() == type)
                .orElse(null);
        }
        return introspection;
    }

    @Override
    public <T> T instantiate(Class<T> type) {
        BeanIntrospection<T> introspection = introspection(type);
        if (introspection == null) {
            throw new IllegalStateException(unsupported("An instance of the class " + type.getName()
                + ", which is not a bean nor introspected,"));
        }
        return introspection.instantiate();
    }

    @Override
    public @Nullable Function<String, Object> stringConversion(Class<?> type) {
        StringConversion conversion = StringConversions.BY_TYPE.get(type).orElse(null);
        if (conversion != null) {
            return value -> convert(conversion, value);
        }
        BeanIntrospection<?> introspection = introspection(type);
        if (introspection == null) {
            return null;
        }
        Argument<?>[] arguments = introspection.getConstructorArguments();
        if (arguments.length == 1 && arguments[0].getType() == String.class) {
            return introspection::instantiate;
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void injectFields(Object instance, Class<? extends Annotation> annotation, Function<Class<?>, @Nullable Object> values) {
        BeanIntrospection<Object> introspection = (BeanIntrospection<Object>) introspection(instance.getClass());
        if (introspection == null) {
            return;
        }
        // the properties and the fields of super classes they hide
        List<WritableMember<Object>> members = new ArrayList<>();
        for (WritableMember<Object> member : WritableMember.of(introspection)) {
            if (member.getAnnotationMetadata().hasAnnotation(annotation)) {
                members.add(member);
            }
        }
        for (WritableMember<Object> member : members) {
            Object value = values.apply(member.getType());
            if (value != null && member.read(instance) == null) {
                member.write(instance, value);
            }
        }
    }

    @Override
    public <T> @Nullable RuntimeBeanDefinition<T> beanDefinition(Class<T> type) {
        return null;
    }

    private static Object convert(StringConversion conversion, String value) {
        try {
            return conversion.convert(value);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private static String unsupported(String what) {
        return what + " needs reflection: add io.micronaut:micronaut-reflection to the runtime classpath";
    }

    /**
     * The name and the parameter types of a method.
     *
     * @param name           The name
     * @param parameterTypes The parameter types
     */
    private record MethodKey(String name, List<Class<?>> parameterTypes) {
    }

    /**
     * The application annotations of a method, as metadata and synthesized once.
     */
    private static final class MethodAnnotations {
        private final AnnotationMetadata metadata;
        private volatile Annotation @Nullable [] annotations;

        MethodAnnotations(AnnotationMetadata metadata) {
            this.metadata = metadata;
        }

        AnnotationMetadata metadata() {
            return metadata;
        }

        Annotation[] annotations() {
            Annotation[] synthesized = annotations;
            if (synthesized == null) {
                synthesized = metadata.synthesizeAll();
                annotations = synthesized;
            }
            return synthesized;
        }
    }

    /**
     * The String conversions the processor generated, by type: the services of the class loader of
     * the type, which may not be the one of this class, loaded once by class loader.
     */
    private static final class StringConversions {

        static final ClassValue<Optional<StringConversion>> BY_TYPE = new ClassValue<>() {
            @Override
            protected Optional<StringConversion> computeValue(Class<?> type) {
                ClassLoader classLoader = type.getClassLoader();
                if (classLoader == null) {
                    return Optional.empty();
                }
                Map<Class<?>, StringConversion> conversions;
                synchronized (BY_CLASS_LOADER) {
                    conversions = BY_CLASS_LOADER.computeIfAbsent(classLoader, StringConversions::load);
                }
                return Optional.ofNullable(conversions.get(type));
            }
        };

        private static final Map<ClassLoader, Map<Class<?>, StringConversion>> BY_CLASS_LOADER = new WeakHashMap<>();

        private static Map<Class<?>, StringConversion> load(ClassLoader classLoader) {
            Map<Class<?>, StringConversion> conversions = new HashMap<>();
            for (StringConversion conversion : SoftServiceLoader.load(StringConversion.class, classLoader).collectAll()) {
                conversions.putIfAbsent(conversion.type(), conversion);
            }
            return conversions;
        }
    }
}
