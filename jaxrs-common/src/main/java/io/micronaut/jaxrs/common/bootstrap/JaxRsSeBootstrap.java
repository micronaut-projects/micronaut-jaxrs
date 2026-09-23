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
package io.micronaut.jaxrs.common.bootstrap;

import io.micronaut.core.annotation.Internal;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;

import java.util.concurrent.CompletionStage;

/**
 * Starts a server for an {@link Application} (JAX-RS 2.3.1): implemented by the server module,
 * found with the service loader.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
public interface JaxRsSeBootstrap {

    /**
     * @param application   The application
     * @param configuration The configuration
     * @return The running instance
     */
    CompletionStage<SeBootstrap.Instance> bootstrap(Application application, SeBootstrap.Configuration configuration);

    /**
     * @param applicationClass The class of the application
     * @param configuration    The configuration
     * @return The running instance
     */
    CompletionStage<SeBootstrap.Instance> bootstrap(Class<? extends Application> applicationClass, SeBootstrap.Configuration configuration);
}
