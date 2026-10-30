package io.micronaut.jaxrs.common;

import io.micronaut.buffer.netty.NettyByteBufferFactory;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.BeanRegistration;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.http.ByteBodyHttpResponse;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpResponseWrapper;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.body.ByteBody;
import io.micronaut.http.body.ByteBodyFactory;
import io.micronaut.http.body.CloseableByteBody;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.body.ResponseBodyWriter;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A writer interceptor that fails after proceeding: the response the writer wrote is released.
 */
class WriterInterceptorReleaseTest {

    @Test
    void theWrittenResponseIsReleasedWhenAnInterceptorFails() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "WriterInterceptorReleaseTest"))) {
            TrackingWriter writer = new TrackingWriter();
            List<BeanRegistration<WriterInterceptor>> interceptors = new ArrayList<>(context.getBeanRegistrations(WriterInterceptor.class));
            var genericWriter = new JaxRsGenericEntityMessageBodyWriter<Payload>(
                context.getBean(JaxRsContainerMessageBodyHandlerRegistry.class),
                new SingleWriterRegistry(writer),
                interceptors,
                metadata -> true,
                routeInterceptors(context));
            ByteBodyFactory bodyFactory = ByteBodyFactory.createDefault(NettyByteBufferFactory.DEFAULT);
            MutableHttpResponse<GenericEntity<Payload>> response = HttpResponse.ok();
            assertThrows(IllegalStateException.class, () -> genericWriter.write(bodyFactory, HttpRequest.GET("/"), response,
                genericEntityArgument(), MediaType.TEXT_PLAIN_TYPE, new GenericEntity<>(new Payload("x"), Payload.class)));
            assertTrue(writer.released());
        }
    }

    @Test
    void theStreamInstalledByAnInterceptorIsClosedWhenItFails() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "WriterInterceptorReleaseTest.installed"))) {
            TrackingWriter writer = new TrackingWriter();
            List<BeanRegistration<WriterInterceptor>> interceptors = new ArrayList<>(context.getBeanRegistrations(WriterInterceptor.class));
            var genericWriter = new JaxRsGenericEntityMessageBodyWriter<Payload>(
                context.getBean(JaxRsContainerMessageBodyHandlerRegistry.class),
                new SingleWriterRegistry(writer),
                interceptors,
                metadata -> true,
                routeInterceptors(context));
            ByteBodyFactory bodyFactory = ByteBodyFactory.createDefault(NettyByteBufferFactory.DEFAULT);
            MutableHttpResponse<GenericEntity<Payload>> response = HttpResponse.ok();
            InstallingInterceptor interceptor = context.getBean(InstallingInterceptor.class);
            assertThrows(IllegalStateException.class, () -> genericWriter.write(bodyFactory, HttpRequest.GET("/"), response,
                genericEntityArgument(), MediaType.TEXT_PLAIN_TYPE, new GenericEntity<>(new Payload("x"), Payload.class)));
            assertTrue(interceptor.closed);
        }
    }

    @Test
    void theStreamInstalledByAnInterceptorIsClosedWhenItFailsInTheClassicWrite() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("spec.name", "WriterInterceptorReleaseTest.installed"))) {
            List<BeanRegistration<WriterInterceptor>> interceptors = new ArrayList<>(context.getBeanRegistrations(WriterInterceptor.class));
            var genericWriter = new JaxRsGenericEntityMessageBodyWriter<Payload>(
                context.getBean(JaxRsContainerMessageBodyHandlerRegistry.class),
                new SingleWriterRegistry(new TrackingWriter()),
                interceptors,
                metadata -> true,
                routeInterceptors(context));
            InstallingInterceptor interceptor = context.getBean(InstallingInterceptor.class);
            var out = new java.io.ByteArrayOutputStream();
            assertThrows(IllegalStateException.class, () -> genericWriter.writeTo(genericEntityArgument(), MediaType.TEXT_PLAIN_TYPE,
                new GenericEntity<>(new Payload("x"), Payload.class), HttpResponse.ok().getHeaders(), out));
            assertTrue(interceptor.closed);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BeanProvider<JaxRsRouteInterceptors> routeInterceptors(ApplicationContext context) {
        return (BeanProvider<JaxRsRouteInterceptors>) context.getBean(Argument.of(BeanProvider.class, JaxRsRouteInterceptors.class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Argument<GenericEntity<Payload>> genericEntityArgument() {
        return (Argument) Argument.of(GenericEntity.class, Payload.class);
    }

    @Requires(property = "spec.name", value = "WriterInterceptorReleaseTest")
    @Singleton
    static class FailingInterceptor implements WriterInterceptor {
        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
            context.proceed();
            throw new IllegalStateException("failed after proceed");
        }
    }

    @Requires(property = "spec.name", value = "WriterInterceptorReleaseTest.installed")
    @Singleton
    static class InstallingInterceptor implements WriterInterceptor {
        // whether the installed stream was closed
        volatile boolean closed;

        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
            context.setOutputStream(new java.io.FilterOutputStream(context.getOutputStream()) {
                @Override
                public void close() throws IOException {
                    closed = true;
                    super.close();
                }
            });
            context.proceed();
            throw new IllegalStateException("failed after proceed");
        }
    }

    record Payload(String value) {
    }

    private record SingleWriterRegistry(TrackingWriter writer) implements MessageBodyHandlerRegistry {
        @Override
        public <T> Optional<MessageBodyReader<T>> findReader(Argument<T> type, List<MediaType> mediaType) {
            return Optional.empty();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> Optional<MessageBodyWriter<T>> findWriter(Argument<T> type, List<MediaType> mediaType) {
            return Optional.of((MessageBodyWriter<T>) writer);
        }
    }

    private static final class TrackingWriter implements ResponseBodyWriter<Object> {
        // whether the response written was closed
        private volatile boolean closed;

        boolean released() {
            return closed;
        }

        private static CloseableByteBody body(ByteBodyFactory bodyFactory, Object object) {
            return bodyFactory.copyOf(String.valueOf(object), StandardCharsets.UTF_8);
        }

        @Override
        public ByteBodyHttpResponse<?> write(ByteBodyFactory bodyFactory, HttpRequest<?> request, MutableHttpResponse<Object> httpResponse,
                                             Argument<Object> type, MediaType mediaType, Object object) {
            return new TrackedResponse(httpResponse, body(bodyFactory, object));
        }

        @Override
        public CloseableByteBody writePiece(ByteBodyFactory bodyFactory, HttpRequest<?> request, HttpResponse<?> response,
                                            Argument<Object> type, MediaType mediaType, Object object) {
            return body(bodyFactory, object);
        }

        @Override
        public void writeTo(Argument<Object> type, MediaType mediaType, Object object, MutableHeaders outgoingHeaders,
                            OutputStream outputStream) {
            try {
                outputStream.write(String.valueOf(object).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        private final class TrackedResponse extends HttpResponseWrapper<Object> implements ByteBodyHttpResponse<Object> {
            private final CloseableByteBody body;

            TrackedResponse(HttpResponse<Object> delegate, CloseableByteBody body) {
                super(delegate);
                this.body = body;
            }

            @Override
            public ByteBody byteBody() {
                return body;
            }

            @Override
            public void close() {
                closed = true;
                body.close();
            }
        }
    }
}
