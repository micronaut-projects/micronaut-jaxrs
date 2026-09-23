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
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.SseBroadcaster;
import jakarta.ws.rs.sse.SseEventSink;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Sends an event to the sinks registered with it (JAX-RS 9.5).
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
final class JaxRsSseBroadcaster implements SseBroadcaster {

    private final List<SseEventSink> sinks = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<SseEventSink, Throwable>> onError = new CopyOnWriteArrayList<>();
    private final List<Consumer<SseEventSink>> onClose = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    @Override
    public void onError(BiConsumer<SseEventSink, Throwable> onError) {
        this.onError.add(onError);
    }

    @Override
    public void onClose(Consumer<SseEventSink> onClose) {
        this.onClose.add(onClose);
    }

    @Override
    public void register(SseEventSink sink) {
        if (closed) {
            throw new IllegalStateException("The broadcaster is closed");
        }
        sinks.add(sink);
        if (sink instanceof JaxRsSseEventSink eventSink) {
            // the client disconnected, or the application closed the sink
            eventSink.onClose(() -> closed(sink));
        }
    }

    @Override
    public CompletionStage<?> broadcast(OutboundSseEvent event) {
        if (closed) {
            throw new IllegalStateException("The broadcaster is closed");
        }
        CompletableFuture<?>[] sent = sinks.stream().map(sink -> {
            if (sink.isClosed()) {
                closed(sink);
                return CompletableFuture.completedFuture(null);
            }
            return sink.send(event).toCompletableFuture().whenComplete((result, failure) -> {
                if (failure != null) {
                    for (BiConsumer<SseEventSink, Throwable> callback : onError) {
                        callback.accept(sink, failure);
                    }
                }
            });
        }).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(sent);
    }

    @Override
    public void close() {
        close(true);
    }

    @Override
    public void close(boolean cascading) {
        closed = true;
        for (SseEventSink sink : sinks) {
            if (cascading) {
                try {
                    sink.close();
                } catch (IOException e) {
                    for (BiConsumer<SseEventSink, Throwable> callback : onError) {
                        callback.accept(sink, e);
                    }
                }
            }
            closed(sink);
        }
    }

    private void closed(SseEventSink sink) {
        if (sinks.remove(sink)) {
            for (Consumer<SseEventSink> callback : onClose) {
                callback.accept(sink);
            }
        }
    }
}
