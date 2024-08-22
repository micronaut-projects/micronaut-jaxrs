/*
 * Copyright 2017-2024 original authors
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

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.ContextlessMessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.netty.DefaultHttpClient;
import jakarta.ws.rs.client.Entity;

import java.util.List;
import java.util.Optional;

/**
 * Implementation of {@link JaxRsClientBuilder} based on the Netty Micronaut HTTP Client. 
 * @author Sergio del Amo
 * @since 4.6.0
 */
public final class NettyJaxRsClientBuilder extends JaxRsClientBuilder {

    @Override
    protected void registerConfiguration(HttpClient httpClient, JaxRsConfiguration jaxRsConfiguration) {
        super.registerConfiguration(httpClient, jaxRsConfiguration);

        if (httpClient instanceof DefaultHttpClient defaultHttpClient) {
            MessageBodyHandlerRegistry handlerRegistry = defaultHttpClient.getHandlerRegistry();
            if (handlerRegistry instanceof ContextlessMessageBodyHandlerRegistry contextlessMessageBodyHandlerRegistry) {
                defaultHttpClient.setHandlerRegistry(createMessageBodyHandlerRegistry(contextlessMessageBodyHandlerRegistry));
                jaxRsConfiguration.register(defaultHttpClient.getHandlerRegistry().findReader(Argument.STRING, List.of(MediaType.ALL_TYPE)).get());
                jaxRsConfiguration.register(defaultHttpClient.getHandlerRegistry().findReader(Argument.of(byte[].class), List.of(MediaType.ALL_TYPE)).get());
                jaxRsConfiguration.register(defaultHttpClient.getHandlerRegistry().findWriter(Argument.STRING, List.of(MediaType.ALL_TYPE)).get());
                jaxRsConfiguration.register(defaultHttpClient.getHandlerRegistry().findWriter(Argument.of(byte[].class), List.of(MediaType.ALL_TYPE)).get());
            }
        }
    }

    private static MessageBodyHandlerRegistry createMessageBodyHandlerRegistry(ContextlessMessageBodyHandlerRegistry handlerRegistry) {
        return new MessageBodyHandlerRegistry() {

            @Override
            public <T> Optional<MessageBodyReader<T>> findReader(@NonNull Argument<T> type, @Nullable List<MediaType> mediaType) {
                return handlerRegistry.findReader(type, mediaType);
            }

            @Override
            public <T> Optional<MessageBodyWriter<T>> findWriter(@NonNull Argument<T> type, @NonNull List<MediaType> mediaType) {
                if (type.getType().equals(Entity.class)) {
                    return Optional.of((MessageBodyWriter<T>) new JaxRsEntityMessageBodyWriter(handlerRegistry, mediaType));
                }
                return handlerRegistry.findWriter(type, mediaType);
            }
        };
    }
}
