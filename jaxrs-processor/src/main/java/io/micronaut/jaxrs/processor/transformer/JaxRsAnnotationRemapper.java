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
package io.micronaut.jaxrs.processor.transformer;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.annotation.AnnotationRemapper;
import io.micronaut.inject.visitor.VisitorContext;

import java.lang.annotation.Inherited;
import java.util.List;

/**
 * Makes the JAX-RS annotations inherited: registered for each package of JAX-RS annotations, the
 * remappers are keyed by package.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
public class JaxRsAnnotationRemapper implements AnnotationRemapper {

    private final String packageName;

    /**
     * The remapper of the annotations of {@code jakarta.ws.rs}.
     */
    public JaxRsAnnotationRemapper() {
        this("jakarta.ws.rs");
    }

    JaxRsAnnotationRemapper(String packageName) {
        this.packageName = packageName;
    }

    @Override
    public String getPackageName() {
        return packageName;
    }

    @Override
    public List<AnnotationValue<?>> remap(AnnotationValue<?> annotation, VisitorContext visitorContext) {
        return List.of(
            annotation.mutate().stereotype(
                AnnotationValue.builder(Inherited.class).build()
            ).build());
    }

    /**
     * The remapper of the annotations of {@code jakarta.ws.rs.core}.
     */
    @Internal
    public static final class CorePackage extends JaxRsAnnotationRemapper {
        public CorePackage() {
            super("jakarta.ws.rs.core");
        }
    }

    /**
     * The remapper of the annotations of {@code jakarta.ws.rs.container}.
     */
    @Internal
    public static final class ContainerPackage extends JaxRsAnnotationRemapper {
        public ContainerPackage() {
            super("jakarta.ws.rs.container");
        }
    }

    /**
     * The remapper of the annotations of {@code jakarta.ws.rs.ext}.
     */
    @Internal
    public static final class ExtPackage extends JaxRsAnnotationRemapper {
        public ExtPackage() {
            super("jakarta.ws.rs.ext");
        }
    }
}
