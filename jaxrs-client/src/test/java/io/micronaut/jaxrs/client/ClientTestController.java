package io.micronaut.jaxrs.client;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.CustomHttpMethod;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.sse.Event;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import reactor.core.publisher.Mono;

@Requires(property = "spec.name", value = "JaxRsClient")
@Controller("/client")
class ClientTestController {

    static final AtomicInteger POSTED = new AtomicInteger();
    // the requests to /held that arrived, which are never answered
    static final Semaphore HELD = new Semaphore(0);

    @Get(value = "/held", produces = MediaType.TEXT_PLAIN)
    Mono<String> held() {
        return Mono.<String>never()
            .doOnSubscribe(subscription -> HELD.release());
    }

    @Get(value = "/slow", produces = MediaType.TEXT_PLAIN)
    Mono<String> slow() {
        return Mono.just("slow").delayElement(Duration.ofMillis(1500));
    }

    @Post(value = "/posted", consumes = MediaType.ALL, produces = MediaType.TEXT_PLAIN)
    String posted(@Body byte @Nullable [] body) {
        return String.valueOf(POSTED.incrementAndGet());
    }

    @Get(value = "/hello", produces = MediaType.TEXT_PLAIN)
    String hello() {
        return "hello";
    }

    @Get(value = "/empty", produces = MediaType.APPLICATION_JSON)
    HttpResponse<?> empty() {
        return HttpResponse.noContent();
    }

    @Get(value = "/missing", produces = MediaType.TEXT_PLAIN)
    HttpResponse<String> missing() {
        return HttpResponse.<String>notFound().body("the error").contentType(MediaType.TEXT_PLAIN_TYPE);
    }

    @Post(value = "/echo", consumes = MediaType.ALL, produces = MediaType.TEXT_PLAIN)
    String echo(HttpRequest<?> request, @Body byte @Nullable [] body) {
        return request.getContentType().map(MediaType::toString).orElse("none") + "|"
            + (body == null ? "" : new String(body, StandardCharsets.UTF_8));
    }

    @Get(value = "/links")
    HttpResponse<?> links() {
        // a relative link and an absolute one, in one header
        return HttpResponse.ok().header("Link", "<first>; rel=\"first\", <http://other/last>; rel=\"last\"");
    }

    @CustomHttpMethod(method = "PURGE", value = "/method", produces = MediaType.TEXT_PLAIN)
    String purge(HttpRequest<?> request) {
        return request.getMethodName();
    }

    @Get(value = "/header/{name}", produces = MediaType.TEXT_PLAIN)
    String header(HttpRequest<?> request, String name) {
        return request.getHeaders().get(name, String.class).orElse("none");
    }

    @Get(value = "/events", produces = MediaType.TEXT_EVENT_STREAM)
    HttpResponse<Publisher<Event<String>>> events(HttpRequest<?> request) {
        if (!"Bearer token".equals(request.getHeaders().get("Authorization"))) {
            return HttpResponse.unauthorized();
        }
        return HttpResponse.ok(Flux.just(Event.of("one"), Event.of("two")));
    }

    @Post(value = "/gunzip", consumes = MediaType.ALL, produces = MediaType.TEXT_PLAIN)
    String gunzip(HttpRequest<?> request, @Body byte @Nullable [] body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body;
        if ("gzip".equals(request.getHeaders().get("X-Encoding"))) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                bytes = in.readAllBytes();
            }
        }
        return request.getContentType().map(MediaType::toString).orElse("none") + "|" + new String(bytes, StandardCharsets.UTF_8);
    }

    @Get(value = "/no-events", produces = MediaType.TEXT_EVENT_STREAM)
    HttpResponse<?> noEvents() {
        return HttpResponse.noContent();
    }

    @Get(value = "/plain-events", produces = {MediaType.TEXT_EVENT_STREAM, MediaType.TEXT_PLAIN})
    HttpResponse<String> plainEvents() {
        return HttpResponse.ok("not events").contentType(MediaType.TEXT_PLAIN_TYPE);
    }

    private final java.util.concurrent.atomic.AtomicInteger dropped = new java.util.concurrent.atomic.AtomicInteger();

    @Get(value = "/dropping", produces = MediaType.TEXT_EVENT_STREAM)
    Publisher<Event<String>> dropping() {
        // the connection breaks after an event
        int connection = dropped.incrementAndGet();
        return Flux.concat(Flux.just(Event.of("connection " + connection).id(String.valueOf(connection))),
            reactor.core.publisher.Mono.delay(java.time.Duration.ofMillis(100)).then(reactor.core.publisher.Mono.error(new IllegalStateException("dropped"))));
    }

    @Get(value = "/endless", produces = MediaType.TEXT_EVENT_STREAM)
    Publisher<Event<String>> endless() {
        return Flux.interval(java.time.Duration.ofMillis(50)).map(i -> Event.of("tick " + i));
    }

    @Get(value = "/flood", produces = MediaType.TEXT_EVENT_STREAM)
    Publisher<Event<String>> flood() {
        return Flux.range(0, 100_000).map(i -> Event.of("event " + i));
    }

    @Get(value = "/created-events", produces = MediaType.TEXT_EVENT_STREAM)
    HttpResponse<Publisher<Event<String>>> createdEvents() {
        return HttpResponse.<Publisher<Event<String>>>status(HttpStatus.CREATED).body(Flux.just(Event.of("one")));
    }

    @Get(value = "/unavailable", produces = MediaType.TEXT_EVENT_STREAM)
    HttpResponse<?> unavailable() {
        return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
