package io.micronaut.jaxrs.common

import io.micronaut.core.annotation.AnnotationMetadata
import jakarta.ws.rs.Priorities
import io.micronaut.core.type.Argument
import io.micronaut.core.type.Headers
import io.micronaut.http.MediaType
import io.micronaut.http.simple.SimpleHttpHeaders
import jakarta.ws.rs.ext.ReaderInterceptor
import jakarta.ws.rs.ext.ReaderInterceptorContext
import spock.lang.Specification

class InterceptedReadSpec extends Specification {

    void "an interceptor changes immutable headers the reader sees"() {
        given:
        def source = new SimpleHttpHeaders()
        source.add("X-A", "a")
        // e.g. the immutable headers of a servlet request
        Headers immutable = [
                names : { -> source.names() },
                getAll: { CharSequence name -> source.getAll(name) },
                get   : { CharSequence name -> source.get(name) }
        ] as Headers
        ReaderInterceptor interceptor = { ReaderInterceptorContext ctx ->
            ctx.headers.putSingle("X-B", "b")
            ctx.proceed()
        } as ReaderInterceptor

        when:
        def read = new JaxRsInterceptedRead<String>([JaxRsRouteInterceptors.registration(interceptor, AnnotationMetadata.EMPTY_METADATA, Priorities.USER)], { true }) {
            @Override
            protected String readFromAfterInterception(Argument<Object> type, MediaType mediaType, Headers httpHeaders, InputStream inputStream) {
                return httpHeaders.get("X-A") + httpHeaders.get("X-B")
            }
        }.intercept(Argument.STRING, MediaType.TEXT_PLAIN_TYPE, immutable, new ByteArrayInputStream(new byte[0]))

        then:
        read == "ab"
    }

    void "a null entity of the reader stays null"() {
        given:
        ReaderInterceptor interceptor = { ReaderInterceptorContext ctx -> ctx.proceed() } as ReaderInterceptor

        when:
        def read = new JaxRsInterceptedRead<Integer>([JaxRsRouteInterceptors.registration(interceptor, AnnotationMetadata.EMPTY_METADATA, Priorities.USER)], { true }) {
            @Override
            protected Integer readFromAfterInterception(Argument<Object> type, MediaType mediaType, Headers httpHeaders, InputStream inputStream) {
                return null
            }
        }.intercept(Argument.of(Integer), MediaType.TEXT_PLAIN_TYPE, new SimpleHttpHeaders(), new ByteArrayInputStream(new byte[0]))

        then:
        read == null
    }
}
