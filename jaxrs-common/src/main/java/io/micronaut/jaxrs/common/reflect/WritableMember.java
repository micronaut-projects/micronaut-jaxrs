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

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationMetadataProvider;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.beans.BeanPropertyMember;
import io.micronaut.core.type.Argument;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.ElementType;
import java.util.ArrayList;
import java.util.List;

/**
 * A field or setter of an introspected class that can be set: a property, or a field of a super
 * class that a field of the same name hides, which the introspection only exposes as a member of
 * the property when it is generated with {@code members = true}.
 *
 * @param <B> The type of the bean
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
public sealed interface WritableMember<B> extends AnnotationMetadataProvider {

    /**
     * @return The type and the annotations of the value
     */
    Argument<Object> asArgument();

    /**
     * @return The type of the value
     */
    default Class<Object> getType() {
        return asArgument().getType();
    }

    /**
     * @param bean The bean
     * @return The current value, or {@code null} for a member that cannot be read
     */
    @Nullable Object read(B bean);

    /**
     * @param bean  The bean
     * @param value The value to set
     */
    void write(B bean, @Nullable Object value);

    /**
     * The members of an introspection that can be set: its writable properties, then the fields
     * of super classes they hide.
     *
     * @param introspection The introspection
     * @param <B>           The type of the bean
     * @return The members
     */
    @SuppressWarnings("unchecked")
    static <B> List<WritableMember<B>> of(BeanIntrospection<B> introspection) {
        List<WritableMember<B>> members = new ArrayList<>();
        for (BeanProperty<B, Object> property : introspection.getBeanProperties()) {
            if (!property.isReadOnly()) {
                members.add(new Property<>(property));
            }
            // the first field is the one of the property, the others are hidden by it
            boolean first = true;
            for (BeanPropertyMember<B, ?> member : property.getMembers()) {
                if (member.getElementType() != ElementType.FIELD) {
                    continue;
                }
                if (!first && member.isWritable()) {
                    members.add(new HiddenField<>((BeanPropertyMember<B, Object>) member));
                }
                first = false;
            }
        }
        return members;
    }

    /**
     * A property.
     *
     * @param property The property
     * @param <B>      The type of the bean
     */
    record Property<B>(BeanProperty<B, Object> property) implements WritableMember<B> {
        @Override
        public AnnotationMetadata getAnnotationMetadata() {
            return property.getAnnotationMetadata();
        }

        @Override
        public Argument<Object> asArgument() {
            return property.asArgument();
        }

        @Override
        public @Nullable Object read(B bean) {
            return property.isWriteOnly() ? null : property.get(bean);
        }

        @Override
        public void write(B bean, @Nullable Object value) {
            property.set(bean, value);
        }
    }

    /**
     * A field of a super class hidden by a field of the same name.
     *
     * @param member The member of the property
     * @param <B>    The type of the bean
     */
    record HiddenField<B>(BeanPropertyMember<B, Object> member) implements WritableMember<B> {
        @Override
        public AnnotationMetadata getAnnotationMetadata() {
            return member.getAnnotationMetadata();
        }

        @Override
        public Argument<Object> asArgument() {
            return member.asArgument();
        }

        @Override
        public @Nullable Object read(B bean) {
            return member.isReadable() ? member.read(bean) : null;
        }

        @Override
        public void write(B bean, @Nullable Object value) {
            member.write(bean, value);
        }
    }
}
