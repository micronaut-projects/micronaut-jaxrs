/*
 * Copyright 2017-2025 original authors
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

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationMetadataProvider;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.jaxrs.common.NameBindingPredicate;
import io.micronaut.web.router.RouteAttributes;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NameBinding;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The container name binding predicate.
 *
 * @author Denis Stepanov
 * @since 4.9
 */
@Singleton
@Internal
final class ContainerNameBindingPredicate implements NameBindingPredicate {

    private final AnnotationMetadata application;

    ContainerNameBindingPredicate(ApplicationProvider applicationProvider) {
        this.application = applicationProvider.getAnnotationMetadata();
    }

    @Override
    public boolean test(AnnotationMetadata component) {
        AnnotationMetadata route = ServerRequestContext.currentRequest()
            .flatMap(request -> RouteAttributes.getRouteInfo(request).map(AnnotationMetadataProvider::getAnnotationMetadata))
            .orElse(AnnotationMetadata.EMPTY_METADATA);
        Set<String> namedFilters = new HashSet<>(route.getAnnotationNamesByStereotype(NameBinding.class));
        namedFilters.addAll(application.getAnnotationNamesByStereotype(NameBinding.class));
        List<String> filterNamedBinding = component.getAnnotationNamesByStereotype(NameBinding.class);
        return namedFilters.containsAll(filterNamedBinding);
    }
}
