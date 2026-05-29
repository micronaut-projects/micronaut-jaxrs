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
package io.micronaut.jaxrs.reflection;

import io.micronaut.jaxrs.common.MicronautRuntimeDelegate;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JaxRsReflectionSeBootstrapProviderTest {

    @Test
    void classBootstrapInstantiatesApplicationInReflectionFallback() throws ExecutionException, InterruptedException {
        ReflectionApplication.CONSTRUCTED.set(0);
        JaxRsReflectionSeBootstrapProvider provider = new JaxRsReflectionSeBootstrapProvider();
        SeBootstrap.Configuration configuration = new MicronautRuntimeDelegate()
            .createConfigurationBuilder()
            .property(SeBootstrap.Configuration.PORT, 0)
            .build();

        SeBootstrap.Instance instance = provider.bootstrap(ReflectionApplication.class, configuration)
            .toCompletableFuture()
            .get();
        try {
            assertEquals(1, ReflectionApplication.CONSTRUCTED.get());
        } finally {
            instance.stop().toCompletableFuture().get();
        }
    }

    static final class ReflectionApplication extends Application {
        static final AtomicInteger CONSTRUCTED = new AtomicInteger();

        private ReflectionApplication() {
            CONSTRUCTED.incrementAndGet();
        }
    }
}
