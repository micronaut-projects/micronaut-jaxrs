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
package io.micronaut.jaxrs.client;

import io.micronaut.core.annotation.Internal;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.sse.SseEventSource;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Micronaut-backed {@link SseEventSource.Builder}.
 *
 * @since 5.0
 */
@Internal
public final class JaxRsSseEventSourceBuilder extends SseEventSource.Builder {
    private WebTarget target;
    private long reconnectDelay = JaxRsSseEventSource.DEFAULT_RECONNECT_DELAY_MILLIS;
    private TimeUnit reconnectDelayUnit = TimeUnit.MILLISECONDS;

    @Override
    protected SseEventSource.Builder target(WebTarget target) {
        this.target = Objects.requireNonNull(target, "target");
        return this;
    }

    @Override
    public SseEventSource.Builder reconnectingEvery(long delay, TimeUnit unit) {
        this.reconnectDelay = delay;
        this.reconnectDelayUnit = Objects.requireNonNull(unit, "unit");
        return this;
    }

    @Override
    public SseEventSource build() {
        if (target == null) {
            throw new IllegalStateException("SSE event source target is required");
        }
        if (target instanceof JaxRsWebTarget jaxRsWebTarget) {
            JaxRsConfiguration configuration = jaxRsWebTarget.getConfiguration();
            ScheduledExecutorService scheduler = configuration.getScheduledExecutorService();
            JaxRsClient owner = jaxRsWebTarget.client();
            JaxRsSseEventSource eventSource = new JaxRsSseEventSource(
                target,
                reconnectDelayUnit.toMillis(reconnectDelay),
                scheduler,
                false,
                null,
                true
            );
            owner.registerCloseable(eventSource);
            return eventSource;
        }
        return new JaxRsSseEventSource(target, reconnectDelayUnit.toMillis(reconnectDelay));
    }
}
