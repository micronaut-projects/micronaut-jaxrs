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
package io.micronaut.jaxrs.runtime.core;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpAttributes;
import io.micronaut.http.HttpRequest;
import io.micronaut.runtime.http.scope.RequestAware;
import io.micronaut.runtime.http.scope.RequestScope;
import io.micronaut.web.router.MethodBasedRouteInfo;
import io.micronaut.web.router.RouteInfo;
import io.micronaut.web.router.RouteMatch;
import jakarta.ws.rs.container.ResourceInfo;

import java.lang.reflect.Method;

/**
 * A {@link RequestScope} bean implementing the JAX-RS ResourceInfo to access the resource class and resource method matched by the current request.
 * Methods in this class MAY return {@code null} if a resource class and method have not been matched.
 *
 * @author Tim Yates
 * @since 4.1.0
 */
@RequestScope
public class JaxRsResourceInfo implements RequestAware, ResourceInfo {

    private RouteInfo<?> routeInfo;

    @Override
    public void setRequest(HttpRequest<?> request) {
        routeInfo = request.getAttribute(HttpAttributes.ROUTE_MATCH, RouteMatch.class).map(RouteMatch::getRouteInfo).orElse(null);
    }

    @Nullable
    @Override
    public Method getResourceMethod() {
        if (routeInfo instanceof MethodBasedRouteInfo<?, ?> methodBasedRouteInfo) {
            return methodBasedRouteInfo.getTargetMethod().getTargetMethod();
        }
        return null;
    }

    @Nullable
    @Override
    public Class<?> getResourceClass() {
        return routeInfo == null ? null : routeInfo.getDeclaringType();
    }
}
