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

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.server.netty.configuration.NettyHttpServerConfiguration;
import jakarta.inject.Singleton;

/**
 * The form limits of the Netty server, which the url-encoded entities JAX-RS parses itself have
 * too: a bean only with the Netty server, absent e.g. on a servlet container.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Singleton
@Requires(classes = NettyHttpServerConfiguration.class)
@Requires(beans = NettyHttpServerConfiguration.class)
@Internal
final class NettyFormLimits {

    private final NettyHttpServerConfiguration configuration;

    /**
     * @param configuration The configuration of the Netty server
     */
    NettyFormLimits(NettyHttpServerConfiguration configuration) {
        this.configuration = configuration;
    }

    /**
     * @return The maximum number of form fields of the Netty server
     */
    int maxFields() {
        return configuration.getFormMaxFields();
    }
}
