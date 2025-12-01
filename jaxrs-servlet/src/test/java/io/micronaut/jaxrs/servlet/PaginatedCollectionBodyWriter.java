package io.micronaut.jaxrs.servlet;


import io.micronaut.context.exceptions.ConfigurationException;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.codec.CodecException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.OutputStream;
import java.util.List;

/**
 * An internal class for writing {@link PaginatedCollection} to the response.
 *
 * @param <T> the collection type of the paginated response
 */
@Singleton
@Produces(MediaType.APPLICATION_JSON)
@Order(-1)
final class PaginatedCollectionBodyWriter<T> implements MessageBodyWriter<PaginatedCollection<T>> {

    private final MessageBodyHandlerRegistry registry;
    private final MessageBodyWriter<T> bodyWriter;
    private final Argument<T> bodyType;

    @Inject
    PaginatedCollectionBodyWriter(MessageBodyHandlerRegistry registry) {
        this(registry, null, null);
    }

    private PaginatedCollectionBodyWriter(MessageBodyHandlerRegistry registry, @Nullable MessageBodyWriter<T> bodyWriter, @Nullable Argument<T> bodyType) {
        this.registry = registry;
        this.bodyWriter = bodyWriter;
        this.bodyType = bodyType;
    }

    @Override
    public MessageBodyWriter<PaginatedCollection<T>> createSpecific(Argument<PaginatedCollection<T>> type) {
        Argument<T> bt = type.getTypeParameters()[0];
        MessageBodyWriter<T> writer = registry.findWriter(bt, List.of(MediaType.APPLICATION_JSON_TYPE))
            .orElseThrow(() -> new ConfigurationException("No JSON message writer present"));
        return new PaginatedCollectionBodyWriter<>(registry, writer, bt);
    }

    @Override
    public void writeTo(Argument<PaginatedCollection<T>> type, MediaType mediaType, PaginatedCollection<T> paginated,
                        MutableHeaders headers, OutputStream outputStream) throws CodecException {
        if (bodyType != null && bodyWriter != null) {
            bodyWriter.writeTo(bodyType, mediaType, paginated.getItems(), headers, outputStream);
        } else {
            throw new ConfigurationException("No JSON message writer present");
        }
    }

    @Override
    public boolean isWriteable(Argument<PaginatedCollection<T>> type, MediaType mediaType) {
        return bodyType == null || bodyWriter == null || bodyWriter.isWriteable(bodyType, mediaType);
    }

    @Override
    public boolean isBlocking() {
        return bodyWriter != null && bodyWriter.isBlocking();
    }

}
