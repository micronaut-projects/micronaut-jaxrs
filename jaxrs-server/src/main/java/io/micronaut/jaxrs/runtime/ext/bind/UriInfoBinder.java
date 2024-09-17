/*
 * Copyright 2017-2022 original authors
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
package io.micronaut.jaxrs.runtime.ext.bind;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder;
import io.micronaut.jaxrs.container.ApplicationPathProvider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.UriInfo;

import java.util.Optional;

/**
 * Binds the {@link  UriInfo} of the {@link jakarta.ws.rs.core.Context}.
 *
 * @author Dan Hollingsworth
 * @since 3.3.0
 */
@Singleton
@Internal
public final class UriInfoBinder implements TypedRequestArgumentBinder<UriInfo> {

    private static final Argument<UriInfo> ARGUMENT = Argument.of(UriInfo.class);
    private final ApplicationPathProvider applicationPathProvider;

    public UriInfoBinder(ApplicationPathProvider applicationPathProvider) {
        this.applicationPathProvider = applicationPathProvider;
    }

    @Override
    public BindingResult<UriInfo> bind(ArgumentConversionContext<UriInfo> uriInfo, HttpRequest<?> source) {
        return () -> Optional.of(new UriInfoImpl(source, applicationPathProvider.getPath()));
    }

    @Override
    public Argument<UriInfo> argumentType() {
        return ARGUMENT;
    }
}
