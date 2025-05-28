/*
 * Copyright 2017-2023 original authors
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
package io.micronaut.validation.tck.runtime;

import io.micronaut.context.annotation.Executable;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Vetoed;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.Application;

@Internal
public final class TestClassVisitor implements TypeElementVisitor<Object, Object> {

    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public int getOrder() {
        return 88;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        process(element, context);
    }

    private void process(ClassElement element, VisitorContext context) {
        if (element.getName().startsWith("ee.jakarta.tck.ws.rs") && !element.isEnum()) {
            element.annotate(Introspected.class, builder -> {
                builder.member("accessKind", new Introspected.AccessKind[]{Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD});
                builder.member("visibility", Introspected.Visibility.ANY);
            });

            element.getMethods().forEach(ce -> {
                if (ce.isStatic() || !ce.isAccessible()) {
                    ce.annotate(Vetoed.class);
                } else {
                    ce.annotate(Executable.class);
                }
            });
        }
        if (element.isAssignable(Application.class)) {
            element.annotate(Singleton.class);
            element.annotate(Named.class);
            return;
        }
        // Fix dynamic providers
        switch (element.getName()) {
            case "ee.jakarta.tck.ws.rs.spec.provider.exceptionmapper.ExceptionFromMappedExceptionMapper":
            case "ee.jakarta.tck.ws.rs.spec.client.typedentities.EntityMessageWriter":
            case "ee.jakarta.tck.ws.rs.spec.client.typedentities.EntityMessageReader":
            case "ee.jakarta.tck.ws.rs.spec.filter.lastvalue.SecondReaderInterceptor":
            case "ee.jakarta.tck.ws.rs.spec.filter.lastvalue.SecondWriterInterceptor":
                element.annotate(Singleton.class);
                element.annotate(Named.class);
        }
    }
}
