package io.micronaut.jaxrs.common;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.TypeConverter;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.body.MessageBodyWriter;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An entity of another type an interceptor sets is converted with the converters of the context.
 */
class InterceptedEntityConversionTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void anEntityOfAnotherTypeIsConvertedWithTheConvertersOfTheContext() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "InterceptedEntityConversionTest"))) {
            var writer = new JaxRsGenericEntityMessageBodyWriter<Payload>(
                context.getBean(JaxRsContainerMessageBodyHandlerRegistry.class),
                new ToStringRegistry(),
                new ArrayList<>(context.getBeanRegistrations(WriterInterceptor.class)),
                metadata -> true,
                (BeanProvider<JaxRsRouteInterceptors>) context.getBean(Argument.of(BeanProvider.class, JaxRsRouteInterceptors.class)),
                context.getBean(ConversionService.class));
            var out = new ByteArrayOutputStream();
            writer.writeTo((Argument) Argument.of(GenericEntity.class, Payload.class), MediaType.TEXT_PLAIN_TYPE,
                new GenericEntity<>(new Payload("x"), Payload.class), HttpResponse.ok().getHeaders(), out);
            assertEquals("Payload[value=converted 7]", out.toString(StandardCharsets.UTF_8));
        }
    }

    @Requires(property = "spec.name", value = "InterceptedEntityConversionTest")
    @Singleton
    static class ReplacingInterceptor implements WriterInterceptor {
        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
            context.setEntity(7);
            context.proceed();
        }
    }

    @Requires(property = "spec.name", value = "InterceptedEntityConversionTest")
    @Singleton
    static class IntegerToPayloadConverter implements TypeConverter<Integer, Payload> {
        @Override
        public Optional<Payload> convert(Integer object, Class<Payload> targetType, ConversionContext context) {
            return Optional.of(new Payload("converted " + object));
        }
    }

    record Payload(String value) {
    }

    // writes the value as its string
    private static final class ToStringRegistry implements MessageBodyHandlerRegistry {
        @Override
        public <T> Optional<MessageBodyReader<T>> findReader(Argument<T> type, List<MediaType> mediaType) {
            return Optional.empty();
        }

        @Override
        public <T> Optional<MessageBodyWriter<T>> findWriter(Argument<T> type, List<MediaType> mediaType) {
            return Optional.of((t, m, value, headers, out) -> {
                try {
                    out.write(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
