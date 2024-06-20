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
package io.micronaut.jaxrs.runtime.core;

import io.micronaut.core.annotation.Internal;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Variant;

import java.util.Date;
import java.util.List;

/**
 * The bean context implementation of {@link Request}.
 *
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Internal
@Singleton
final class JaxRsContextRequest implements Request {
    @Override
    public String getMethod() {
        return "";
    }

    @Override
    public Variant selectVariant(List<Variant> variants) {
        return null;
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions(EntityTag eTag) {
        return null;
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions(Date lastModified) {
        return null;
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions(Date lastModified, EntityTag eTag) {
        return null;
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions() {
        return null;
    }
}
