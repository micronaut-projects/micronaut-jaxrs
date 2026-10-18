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
import jakarta.ws.rs.ext.Providers;
import org.jspecify.annotations.Nullable;

/**
 * The {@link Providers} of the client configuration that reads or writes an entity on the
 * current thread: the providers of a client, which the copies of a configuration share, use the
 * ones of the configuration of the current invocation (JAX-RS 9.2.6).
 *
 * @author Denis Stepanov
 * @since 5.3.0
 */
@Internal
public final class JaxRsCurrentProviders {

    private static final ThreadLocal<@Nullable Providers> CURRENT = new ThreadLocal<>();

    private JaxRsCurrentProviders() {
    }

    /**
     * @return The providers of the entity read or written on the current thread, if any
     */
    public static @Nullable Providers current() {
        return CURRENT.get();
    }

    /**
     * Set the providers of the current thread.
     *
     * @param providers The providers, {@code null} for none
     * @return The previous ones, to restore with {@link #restore(Providers)}
     */
    public static @Nullable Providers set(@Nullable Providers providers) {
        Providers previous = CURRENT.get();
        if (providers == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(providers);
        }
        return previous;
    }

    /**
     * Restore the providers of the current thread.
     *
     * @param previous The ones {@link #set(Providers)} returned
     */
    public static void restore(@Nullable Providers previous) {
        set(previous);
    }
}
