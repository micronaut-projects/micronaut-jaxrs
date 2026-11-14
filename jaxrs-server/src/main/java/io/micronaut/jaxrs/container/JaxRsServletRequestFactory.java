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
package io.micronaut.jaxrs.container;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.HttpRequest;

import java.util.Map;

/**
 * Creates the servlet request of a {@code @Context} servlet request value when the server is not
 * a servlet container. Implemented by the micronaut-jaxrs-servlet module: without it, the servlet
 * request types are not bound by the JAX-RS server.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
public interface JaxRsServletRequestFactory {

    /**
     * @param request The request
     * @param contextPath The context path of the application
     * @param attributes The properties of the request, the attributes of the servlet request
     * @return The servlet request emulating the request
     */
    Object create(HttpRequest<?> request, String contextPath, Map<String, Object> attributes);
}
