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
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.io.service.SoftServiceLoader;
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.ExecutableMethod;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.function.Function;

/**
 * What the JAX-RS implementation reads or does with reflection: of a class the annotation
 * processors never saw, of the annotations the JAX-RS API hands over as objects, and the
 * reflective members the JAX-RS API returns.
 *
 * <p>Without the {@code micronaut-reflection} module on the classpath, it uses only the metadata
 * the annotation processors generate: introspections and bean definitions, and fails with a clear
 * error where JAX-RS needs a reflective member. With the module, the implementation the service
 * loader finds uses reflection.</p>
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
public interface JaxRsReflection {

    /**
     * The class of the {@code micronaut-reflection} module that enables reflection.
     */
    String REFLECTION_MODULE_CLASS = "io.micronaut.reflection.ReflectionBeanIntrospection";

    /**
     * @return The implementation: reflective when the {@code micronaut-reflection} module is on
     * the classpath, else the one of the generated metadata
     */
    static JaxRsReflection get() {
        return Holder.INSTANCE;
    }

    /**
     * @return Whether reflection is used
     */
    boolean isReflective();

    /**
     * The annotations of a class: without reflection, the ones of its introspection, else none,
     * e.g. for a class the annotation processors never saw. The annotations of a bean are the
     * ones of its bean definition, see {@code BeanRegistration#getBeanDefinition()}.
     *
     * @param type A class
     * @return The annotations of the class
     */
    AnnotationMetadata annotationMetadata(Class<?> type);

    /**
     * The annotations of a method the JAX-RS API hands over, e.g. to {@code UriBuilder.path(Method)}.
     *
     * @param method The method
     * @return Its annotations
     */
    AnnotationMetadata annotationMetadata(Method method);

    /**
     * The metadata of annotations the JAX-RS API hands over as objects, e.g. the ones of an entity.
     * Without reflection, the members of an annotation Micronaut did not synthesize are not known:
     * reading them calls the methods of the annotation interface, which needs reflection.
     *
     * @param annotations The annotations
     * @return The metadata
     */
    AnnotationMetadata annotationMetadata(Annotation[] annotations);

    /**
     * The annotations of metadata as objects whose types are the ones a class loader defines, for
     * a provider that compares them by identity.
     *
     * @param metadata    The metadata
     * @param classLoader The class loader of the provider
     * @return The annotations
     */
    Annotation[] annotations(AnnotationMetadata metadata, ClassLoader classLoader);

    /**
     * The annotations of a resource method, not merged with the ones of its class, as JAX-RS hands
     * them to the providers.
     *
     * @param method The method
     * @return The annotations
     */
    AnnotationMetadata methodAnnotations(ExecutableMethod<?, ?> method);

    /**
     * The annotations of a resource method as objects, as JAX-RS hands them to the filters.
     *
     * @param method The method
     * @return The annotations
     */
    Annotation[] annotations(ExecutableMethod<?, ?> method);

    /**
     * @param method An executable method
     * @return The method, which the JAX-RS API returns, e.g. from {@code ResourceInfo}
     */
    Method targetMethod(ExecutableMethod<?, ?> method);

    /**
     * The annotations of a resource method of a class that is not a bean, e.g. of a sub-resource,
     * not merged with the ones of its class.
     *
     * @param type           The class
     * @param name           The name of the method
     * @param parameterTypes The parameter types of the method
     * @return The annotations
     * @throws UnsupportedOperationException without reflection, for a method of a class without
     * an introspection, like the reflective implementation for a method it cannot find
     */
    AnnotationMetadata methodAnnotations(Class<?> type, String name, Class<?>... parameterTypes);

    /**
     * @param type           The class
     * @param name           The name of the method
     * @param parameterTypes The parameter types of the method
     * @return The method of a class that is not a bean, which the JAX-RS API returns
     */
    Method targetMethod(Class<?> type, String name, Class<?>... parameterTypes);

    /**
     * The type of a generic type a class implements or extends, with its type arguments, e.g. the
     * {@code MessageBodyReader<Foo>} of a reader.
     *
     * @param type        The class
     * @param genericType The generic type
     * @return The type, {@code null} when it is not known
     */
    @Nullable Argument<?> resolveGeneric(Class<?> type, Class<?> genericType);

    /**
     * The argument of a type the JAX-RS API gives, e.g. the generic type of a reader, a writer or
     * a {@code GenericType}: with reflection, any type, a wildcard, a type variable and a generic
     * array included; without it, a class or a parameterized type, a wildcard or a type variable
     * among its type arguments making it raw.
     *
     * @param type The type
     * @return The argument
     */
    Argument<?> argument(Type type);

    /**
     * The interfaces a class or an interface directly implements or extends, e.g. to know the
     * distance of a type to the type of a provider (JAX-RS 4.2.2).
     *
     * @param type The class
     * @return The interfaces, {@code null} when they are not known without reflection
     */
    default Class<?> @Nullable [] interfaces(Class<?> type) {
        return null;
    }

    /**
     * @param type A class
     * @param <T>  The type
     * @return Its introspection, {@code null} when it has none
     */
    <T> @Nullable BeanIntrospection<T> introspection(Class<T> type);

    /**
     * Instantiate a class that is not a bean.
     *
     * @param type The class
     * @param <T>  The type
     * @return The instance
     */
    <T> T instantiate(Class<T> type);

    /**
     * How JAX-RS converts a string to a class without a converter: its static {@code valueOf} or
     * {@code fromString} method, or its constructor with a string. Without reflection, the
     * constructor with a string of its introspection, or its static factory method annotated with
     * {@code @Creator}: an introspection has no other static methods.
     *
     * @param type The class
     * @return The conversion, {@code null} when the class has none
     */
    @Nullable Function<String, Object> stringConversion(Class<?> type);

    /**
     * Set the fields of an instance annotated with an annotation, which are not set yet.
     *
     * @param instance   The instance
     * @param annotation The annotation
     * @param values     The value of the type of a field, {@code null} for none
     */
    void injectFields(Object instance, Class<? extends Annotation> annotation, Function<Class<?>, @Nullable Object> values);

    /**
     * The definition of a class the annotation processors never saw, as a bean found by each of
     * its types.
     *
     * @param type The class
     * @param <T>  The type
     * @return The definition, {@code null} when there is none
     */
    <T> @Nullable RuntimeBeanDefinition<T> beanDefinition(Class<T> type);

    /**
     * The implementation.
     */
    final class Holder {
        static final JaxRsReflection INSTANCE = load();

        private Holder() {
        }

        private static JaxRsReflection load() {
            ClassLoader classLoader = JaxRsReflection.class.getClassLoader();
            if (ClassUtils.isPresent(REFLECTION_MODULE_CLASS, classLoader)) {
                JaxRsReflection reflection = SoftServiceLoader.load(JaxRsReflection.class, classLoader).firstAvailable().orElse(null);
                if (reflection != null) {
                    return reflection;
                }
            }
            return new MetadataJaxRsReflection();
        }
    }
}
