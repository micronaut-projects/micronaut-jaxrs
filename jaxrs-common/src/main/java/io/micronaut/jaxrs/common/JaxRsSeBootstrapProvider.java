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
package io.micronaut.jaxrs.common;

import io.micronaut.core.annotation.Internal;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Provider hook for Jakarta REST SE bootstrap implementations.
 */
@Internal
public interface JaxRsSeBootstrapProvider {

    /**
     * Starts a Jakarta REST application.
     *
     * @param application The application
     * @param configuration The requested bootstrap configuration
     * @return The bootstrap instance
     */
    CompletionStage<SeBootstrap.Instance> bootstrap(Application application, SeBootstrap.Configuration configuration);

    /**
     * Starts a Jakarta REST application from an application class.
     *
     * @param applicationClass The application class
     * @param configuration The requested bootstrap configuration
     * @return The bootstrap instance
     */
    default CompletionStage<SeBootstrap.Instance> bootstrap(Class<? extends Application> applicationClass, SeBootstrap.Configuration configuration) {
        return CompletableFuture.failedStage(new UnsupportedOperationException("SE bootstrap by application class is not supported by this provider"));
    }
}
