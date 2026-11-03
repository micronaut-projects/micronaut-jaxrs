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

import io.micronaut.context.annotation.BeanProperties;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.ws.rs.ext.Provider;

import java.util.List;
import java.util.Set;

/**
 * Introspects the classes implementing a JAX-RS provider contract that are not annotated with
 * {@code @Provider}, which a client or an application registers by their class, e.g. with
 * {@code Configurable.register(Class)} or {@code Application.getClasses()}: their constructor,
 * annotations ({@code @Priority}, {@code @ConstrainedTo}, {@code @Produces}, {@code @Consumes}...),
 * {@code @Context} members, and the type arguments of the contracts they implement, so that they
 * are instantiated and selected without reflection.
 *
 * @author Denis Stepanov
 * @since 5.3.0
 */
@Internal
public final class JaxRsProviderContractVisitor implements TypeElementVisitor<Object, Object> {

    private static final List<String> PROVIDER_CONTRACTS = List.of(
        "jakarta.ws.rs.ext.MessageBodyReader",
        "jakarta.ws.rs.ext.MessageBodyWriter",
        "jakarta.ws.rs.container.ContainerRequestFilter",
        "jakarta.ws.rs.container.ContainerResponseFilter",
        "jakarta.ws.rs.client.ClientRequestFilter",
        "jakarta.ws.rs.client.ClientResponseFilter",
        "jakarta.ws.rs.ext.ReaderInterceptor",
        "jakarta.ws.rs.ext.WriterInterceptor",
        "jakarta.ws.rs.core.Feature",
        "jakarta.ws.rs.container.DynamicFeature",
        "jakarta.ws.rs.ext.ParamConverterProvider",
        "jakarta.ws.rs.ext.ContextResolver",
        "jakarta.ws.rs.ext.ExceptionMapper",
        "jakarta.ws.rs.client.RxInvokerProvider"
    );

    @Override
    public int getOrder() {
        // after the visitor of the resources and of the @Provider classes
        return JaxRsTypeElementVisitor.POSITION - 1;
    }

    @NonNull
    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public Set<String> getSupportedAnnotationNames() {
        return Set.of("*");
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (element.isAbstract() || element.isInterface() || element.isEnum()
            || element.hasStereotype(Provider.class) || element.isPrivate()) {
            return;
        }
        for (String contract : PROVIDER_CONTRACTS) {
            if (element.isAssignable(contract)) {
                introspect(element);
                context.importTypeHierarchy(element, element);
                return;
            }
        }
    }

    private static void introspect(ClassElement element) {
        if (element.hasAnnotation(Introspected.class)) {
            element.annotate(Introspected.class, builder -> builder
                .member(BeanProperties.MEMBER_INCLUDE_INJECTED, true)
                .member("members", true));
        } else {
            element.annotate(Introspected.class, builder -> builder
                .member("accessKind", new Introspected.AccessKind[]{Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD})
                .member("visibility", new Introspected.Visibility[]{Introspected.Visibility.ANY})
                .member(BeanProperties.MEMBER_INCLUDE_INJECTED, true)
                .member("members", true));
        }
    }
}
