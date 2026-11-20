/*
 * Copyright 2017-2024 original authors
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

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationMetadataProvider;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.Executable;
import io.micronaut.core.type.ReturnType;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import io.micronaut.validation.validator.Validator;
import jakarta.inject.Singleton;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.executable.ExecutableType;
import jakarta.validation.executable.ValidateOnExecution;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.core.Response;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Bean Validation of the resources with the validator of Micronaut Validation (JAX-RS chapter
 * 7): the fields and properties of a resource and its class constraints, from its introspection,
 * the parameters of its resource methods and sub-resource locators, including the bean parameters,
 * and the return values of its resource methods, the entity of a returned {@link Response} with
 * {@code @Valid} and the value of a returned {@link CompletionStage}. A violation is a
 * {@link JaxRsConstraintViolationException}, of a return value or not (JAX-RS 7.6).
 *
 * <p>The methods follow the executable validation rules of Bean Validation (JAX-RS 7.4): a
 * resource method that is a getter is validated only with
 * {@code @ValidateOnExecution(type = GETTER_METHODS)} or {@code ALL}, and none with
 * {@code NONE}. The groups are the default one, or the ones of {@code @Validated} of Micronaut
 * Validation on the method or its class; {@code @Valid} cascades with the conversions of
 * {@code @ConvertGroup}.</p>
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Singleton
@Internal
@Requires(classes = {Validator.class, jakarta.validation.ConstraintViolationException.class})
@Requires(beans = Validator.class)
// like the validator of Micronaut Validation, disabled with micronaut.validator.enabled
@Requires(property = "micronaut.validator.enabled", value = "true", defaultValue = "true")
final class JaxRsBeanValidation implements JaxRsResourceValidation {

    private static final String VALIDATED = "io.micronaut.validation.Validated";
    private static final Class<?>[] DEFAULT_GROUPS = new Class<?>[0];

    private final Validator validator;
    private final Map<Executable<?, ?>, MethodPlan> methods = new ConcurrentHashMap<>();
    private final Map<Class<?>, ResourcePlan> resources = new ConcurrentHashMap<>();

    JaxRsBeanValidation(Validator validator) {
        this.validator = validator;
    }

    @Override
    public void validateResource(Object resource) {
        ResourcePlan plan = resources.computeIfAbsent(resource.getClass(), JaxRsBeanValidation::resourcePlan);
        if (plan.introspection() == null) {
            return;
        }
        Set<ConstraintViolation<Object>> violations = validate(plan.introspection(), resource, plan.groups());
        if (!violations.isEmpty()) {
            throw new JaxRsConstraintViolationException(violations, false);
        }
    }

    @Override
    public void validateParameters(Object resource, String name, Executable<?, ?> method, Argument<?> returnType, @Nullable Object[] arguments) {
        MethodPlan plan = plan(name, method, returnType);
        if (!plan.parameters() && plan.beanParameters().length == 0) {
            return;
        }
        Set<ConstraintViolation<?>> violations = new HashSet<>();
        if (plan.parameters()) {
            violations.addAll(validator.forExecutables().validateParameters(resource, plan.method(), arguments, plan.groups()));
        }
        for (BeanParameter parameter : plan.beanParameters()) {
            Object value = arguments[parameter.index()];
            if (value != null) {
                // a bean parameter without @Valid, which the parameters do not cascade to
                violations.addAll(validate(parameter.introspection(), value, plan.groups()));
            }
        }
        if (!violations.isEmpty()) {
            throw new JaxRsConstraintViolationException(violations, false);
        }
    }

    @Override
    public @Nullable Object validateReturnValue(Object resource, String name, Executable<?, ?> method, Argument<?> returnType, @Nullable Object result) {
        MethodPlan plan = plan(name, method, returnType);
        if (plan.async() != null && result instanceof CompletionStage<?> stage) {
            ExecutableMethod<Object, Object> async = plan.async();
            return stage.thenApply(value -> {
                validateReturnValue(resource, async, value, plan.groups());
                return value;
            });
        }
        if (plan.returnValue()) {
            validateReturnValue(resource, plan.method(), result, plan.groups());
        }
        if (plan.responseEntity() && result instanceof Response response && response.getEntity() != null) {
            // the entity of a response the method validates with @Valid
            Object entity = response.getEntity();
            BeanIntrospection<Object> introspection = introspection(entity.getClass());
            if (introspection != null) {
                Set<ConstraintViolation<Object>> violations = validate(introspection, entity, plan.groups());
                if (!violations.isEmpty()) {
                    throw new JaxRsConstraintViolationException(violations, true);
                }
            }
        }
        return result;
    }

    private void validateReturnValue(Object resource, ExecutableMethod<Object, Object> method, @Nullable Object value, Class<?>[] groups) {
        Set<ConstraintViolation<Object>> violations = validator.forExecutables().validateReturnValue(resource, method, value, groups);
        if (!violations.isEmpty()) {
            throw new JaxRsConstraintViolationException(violations, true);
        }
    }

    @SuppressWarnings("unchecked")
    private Set<ConstraintViolation<Object>> validate(BeanIntrospection<?> introspection, Object value, Class<?>[] groups) {
        return validator.validate((BeanIntrospection<Object>) introspection, value, groups);
    }

    private MethodPlan plan(String name, Executable<?, ?> method, Argument<?> returnType) {
        MethodPlan plan = methods.get(method);
        if (plan == null) {
            plan = methods.computeIfAbsent(method, m -> methodPlan(name, m, returnType));
        }
        return plan;
    }

    @SuppressWarnings("unchecked")
    private static MethodPlan methodPlan(String name, Executable<?, ?> executable, Argument<?> returnType) {
        Argument<?>[] arguments = executable.getArguments();
        AnnotationMetadata metadata = executable instanceof AnnotationMetadataProvider provider
            ? provider.getAnnotationMetadata() : AnnotationMetadata.EMPTY_METADATA;
        ExecutableMethod<Object, Object> method = executable instanceof ExecutableMethod<?, ?> executableMethod
            ? (ExecutableMethod<Object, Object>) executableMethod
            : new Adapted((Class<Object>) executable.getDeclaringType(), name, arguments, returnType, metadata);
        Class<?>[] groups = metadata.classValues(VALIDATED, "groups");
        if (groups.length == 0) {
            groups = DEFAULT_GROUPS;
        }
        if (!isValidatedOnExecution(name, arguments, returnType, metadata)) {
            return new MethodPlan(method, false, new BeanParameter[0], false, false, null, groups);
        }
        boolean parameters = metadata.getDeclaredMetadata().hasStereotype(Constraint.class);
        List<BeanParameter> beanParameters = new ArrayList<>();
        for (int i = 0; i < arguments.length; i++) {
            AnnotationMetadata argumentMetadata = arguments[i].getAnnotationMetadata();
            boolean cascaded = argumentMetadata.hasStereotype(Valid.class);
            parameters |= cascaded || requiresValidation(arguments[i]);
            if (!cascaded && argumentMetadata.hasAnnotation(BeanParam.class)) {
                BeanIntrospection<Object> introspection = introspection(arguments[i].getType());
                if (introspection != null && hasConstraints(introspection)) {
                    beanParameters.add(new BeanParameter(i, introspection));
                }
            }
        }
        Class<?> raw = returnType.getType();
        boolean returns = raw != void.class && raw != Void.class;
        ExecutableMethod<Object, Object> async = null;
        boolean returnValue = false;
        if (returns && CompletionStage.class.isAssignableFrom(raw)) {
            // the value of the stage: the constraints of the method and of its type argument
            Argument<?> value = returnType.getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
            Argument<?> constrained = Argument.of(value.getType(), value.getName(),
                metadata.getDeclaredMetadata().hasStereotype(Constraint.class) || metadata.getDeclaredMetadata().hasStereotype(Valid.class)
                    ? metadata : value.getAnnotationMetadata(), value.getTypeParameters());
            if (requiresValidation(constrained) || requiresValidation(value)) {
                async = new Adapted((Class<Object>) executable.getDeclaringType(), name, arguments,
                    requiresValidation(constrained) ? constrained : value, AnnotationMetadata.EMPTY_METADATA);
            }
        } else if (returns && !Response.class.isAssignableFrom(raw)) {
            returnValue = requiresValidation(method.getReturnType().asArgument());
        }
        // a response is not a bean: @Valid validates its entity
        boolean responseEntity = returns && Response.class.isAssignableFrom(raw) && method.getReturnType().asArgument().getAnnotationMetadata().hasStereotype(Valid.class);
        return new MethodPlan(method, parameters, beanParameters.toArray(BeanParameter[]::new), returnValue, responseEntity, async, groups);
    }

    /**
     * Whether a method is validated on execution (JAX-RS 7.4): not a getter, unless its
     * {@code @ValidateOnExecution}, or the one of its class, includes getters.
     */
    private static boolean isValidatedOnExecution(String name, Argument<?>[] arguments, Argument<?> returnType, AnnotationMetadata metadata) {
        ExecutableType[] types = metadata.enumValues(ValidateOnExecution.class, "type", ExecutableType.class);
        Set<ExecutableType> declared = types.length == 0 ? Set.of(ExecutableType.IMPLICIT) : Set.of(types);
        if (declared.contains(ExecutableType.NONE) && declared.size() == 1) {
            return false;
        }
        if (declared.contains(ExecutableType.ALL)) {
            return true;
        }
        return isGetter(name, arguments, returnType) ? declared.contains(ExecutableType.GETTER_METHODS)
            : declared.contains(ExecutableType.NON_GETTER_METHODS) || declared.contains(ExecutableType.IMPLICIT);
    }

    private static boolean isGetter(String name, Argument<?>[] arguments, Argument<?> returnType) {
        if (arguments.length != 0) {
            return false;
        }
        Class<?> raw = returnType.getType();
        if (name.length() > 3 && name.startsWith("get") && raw != void.class) {
            return true;
        }
        return name.length() > 2 && name.startsWith("is") && raw == boolean.class;
    }

    private static boolean requiresValidation(Argument<?> argument) {
        AnnotationMetadata metadata = argument.getAnnotationMetadata();
        if (metadata.hasStereotype(Constraint.class) || metadata.hasStereotype(Valid.class)) {
            return true;
        }
        for (Argument<?> typeArgument : argument.getTypeParameters()) {
            if (requiresValidation(typeArgument)) {
                return true;
            }
        }
        return false;
    }

    private static ResourcePlan resourcePlan(Class<?> type) {
        // the class of a bean with advice is a subclass of the resource class
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            BeanIntrospection<Object> introspection = introspection(c);
            if (introspection != null) {
                Class<?>[] groups = introspection.classValues(VALIDATED, "groups");
                return new ResourcePlan(hasConstraints(introspection) ? introspection : null, groups.length == 0 ? DEFAULT_GROUPS : groups);
            }
        }
        return new ResourcePlan(null, DEFAULT_GROUPS);
    }

    private static boolean hasConstraints(BeanIntrospection<?> introspection) {
        if (introspection.hasStereotype(Constraint.class)) {
            return true;
        }
        for (BeanProperty<?, Object> property : introspection.getBeanProperties()) {
            if (property.hasStereotype(Constraint.class) || property.hasStereotype(Valid.class) || requiresValidation(property.asArgument())) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable BeanIntrospection<Object> introspection(Class<?> type) {
        Optional<BeanIntrospection<Object>> introspection = BeanIntrospector.SHARED.findIntrospection((Class<Object>) type);
        return introspection.orElseGet(() -> (BeanIntrospection<Object>) JaxRsReflection.get().introspection(type));
    }

    private record ResourcePlan(@Nullable BeanIntrospection<Object> introspection, Class<?>[] groups) {
    }

    private record BeanParameter(int index, BeanIntrospection<Object> introspection) {
    }

    /**
     * What is validated of a method.
     *
     * @param method         The method the validator validates
     * @param parameters     Whether its parameters are validated
     * @param beanParameters Its bean parameters without {@code @Valid} whose classes have constraints
     * @param returnValue    Whether its return value is validated
     * @param responseEntity Whether the entity of the response it returns is validated
     * @param async          The method whose return value is the value of the stage it returns, {@code null} if it is not validated
     * @param groups         The groups
     */
    private record MethodPlan(ExecutableMethod<Object, Object> method, boolean parameters, BeanParameter[] beanParameters,
                              boolean returnValue, boolean responseEntity, @Nullable ExecutableMethod<Object, Object> async,
                              Class<?>[] groups) {
    }

    /**
     * A method of an introspection, or the value of a stage, as the validator validates an
     * executable method: its parameters and its return type with their annotations.
     */
    private static final class Adapted implements ExecutableMethod<Object, Object> {

        private final Class<Object> declaringType;
        private final String name;
        private final Argument<?>[] arguments;
        private final Argument<Object> returnType;
        private final AnnotationMetadata metadata;

        @SuppressWarnings("unchecked")
        Adapted(Class<Object> declaringType, String name, Argument<?>[] arguments, Argument<?> returnType, AnnotationMetadata metadata) {
            this.declaringType = declaringType;
            this.name = name;
            this.arguments = arguments;
            this.returnType = (Argument<Object>) returnType;
            this.metadata = metadata;
        }

        @Override
        public Class<Object> getDeclaringType() {
            return declaringType;
        }

        @Override
        public String getMethodName() {
            return name;
        }

        @Override
        public Argument<?>[] getArguments() {
            return arguments;
        }

        @Override
        public Method getTargetMethod() {
            throw new UnsupportedOperationException("The method " + name + " is validated from its metadata");
        }

        @Override
        public ReturnType<Object> getReturnType() {
            return new ReturnType<>() {
                @Override
                public Class<Object> getType() {
                    return returnType.getType();
                }

                @Override
                public Argument<Object> asArgument() {
                    return returnType;
                }

                @Override
                public Map<String, Argument<?>> getTypeVariables() {
                    return returnType.getTypeVariables();
                }

                @Override
                public AnnotationMetadata getAnnotationMetadata() {
                    return returnType.getAnnotationMetadata();
                }
            };
        }

        @Override
        public @Nullable Object invoke(Object instance, @Nullable Object... arguments) {
            throw new UnsupportedOperationException("The method " + name + " is validated, not called");
        }

        @Override
        public AnnotationMetadata getAnnotationMetadata() {
            return metadata;
        }

        @Override
        public String toString() {
            return declaringType.getName() + "." + name + Arrays.toString(arguments);
        }
    }
}
