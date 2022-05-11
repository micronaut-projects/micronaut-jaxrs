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

import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder;
import jakarta.inject.Singleton;

import javax.ws.rs.core.UriInfo;
import java.util.Optional;

/**
 * @author Dan Hollingsworth
 * @since 3.3.0
 */
@Singleton
public class UriInfoBinder implements TypedRequestArgumentBinder<UriInfo> {

    private static final Argument<UriInfo> ARGUMENT = Argument.of(UriInfo.class);

    @Override
    public BindingResult<UriInfo> bind(ArgumentConversionContext<UriInfo> uriInfo, HttpRequest<?> source) {
        return () -> Optional.of(new UriInfoImpl(source));
    }

    @Override
    public Argument<UriInfo> argumentType() {
        return ARGUMENT;
    }
}
