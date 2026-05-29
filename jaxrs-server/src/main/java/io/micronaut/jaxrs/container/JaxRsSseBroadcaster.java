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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Default {@link SseBroadcaster} implementation.
 */
@Internal
final class JaxRsSseBroadcaster implements SseBroadcaster {
    private final List<SseEventSink> sinks = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<SseEventSink, Throwable>> errorListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<SseEventSink>> closeListeners = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    @Override
    public void onError(BiConsumer<SseEventSink, Throwable> onError) {
        errorListeners.add(onError);
    }

    @Override
    public void onClose(Consumer<SseEventSink> onClose) {
        closeListeners.add(onClose);
    }

    @Override
    public void register(SseEventSink sink) {
        if (closed) {
            throw new IllegalStateException("SseBroadcaster is closed");
        }
        sinks.add(sink);
        if (sink instanceof JaxRsSseEventSink jaxRsSink) {
            jaxRsSink.onClose(this::sinkClosed);
        }
    }

    @Override
    public CompletionStage<?> broadcast(OutboundSseEvent event) {
        if (closed) {
            throw new IllegalStateException("SseBroadcaster is closed");
        }
        List<CompletableFuture<?>> stages = new ArrayList<>(sinks.size());
        for (SseEventSink sink : sinks) {
            if (!sink.isClosed()) {
                stages.add(send(sink, event));
            }
        }
        return CompletableFuture.allOf(stages.toArray(CompletableFuture[]::new));
    }

    @Override
    public void close() {
        close(true);
    }

    @Override
    public void close(boolean closeSinks) {
        if (closed) {
            return;
        }
        closed = true;
        if (closeSinks) {
            for (SseEventSink sink : sinks) {
                try {
                    sink.close();
                } catch (IOException e) {
                    notifyError(sink, e);
                }
            }
        }
        sinks.clear();
    }

    private CompletableFuture<?> send(SseEventSink sink, OutboundSseEvent event) {
        try {
            return sink.send(event)
                .exceptionally(throwable -> {
                    notifyError(sink, throwable);
                    return null;
                })
                .toCompletableFuture();
        } catch (Throwable e) {
            notifyError(sink, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    private void sinkClosed(SseEventSink sink) {
        sinks.remove(sink);
        for (Consumer<SseEventSink> closeListener : closeListeners) {
            closeListener.accept(sink);
        }
    }

    private void notifyError(SseEventSink sink, Throwable throwable) {
        for (BiConsumer<SseEventSink, Throwable> errorListener : errorListeners) {
            errorListener.accept(sink, throwable);
        }
    }
}
