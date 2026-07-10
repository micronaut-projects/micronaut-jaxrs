package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MicronautTest
@Property(name = "spec.name", value = "ResponseMediaSelectionTest")
class ResponseMediaSelectionTest {

    @Inject
    @Client("/api/media-selection")
    HttpClient client;

    @Test
    void exactAcceptUsesServerQualityBeforeDeclaredProducedSpecificity() {
        HttpRequest<?> request = HttpRequest.GET("/weighted-text")
            .accept(MediaType.TEXT_XML_TYPE);

        HttpResponse<String> response = client.toBlocking().exchange(request, String.class);

        assertEquals("text/*", response.body());
    }

    @Test
    void exactAcceptTieUsesDeclaredProducedSpecificity() {
        HttpRequest<?> request = HttpRequest.GET("/weighted-tie")
            .accept(MediaType.of("testi/text"));

        HttpResponse<String> response = client.toBlocking().exchange(request, String.class);

        assertEquals("testi/text", response.body());
    }

    @Requires(property = "spec.name", value = "ResponseMediaSelectionTest")
    @Path("/media-selection")
    static final class MediaSelectionResource {

        @GET
        @Path("weighted-text")
        @Produces("text/*")
        public String textStar() {
            return "text/*";
        }

        @GET
        @Path("weighted-text")
        @Produces("text/xml;qs=0.7")
        public String textXml() {
            return "text/xml";
        }

        @GET
        @Path("weighted-tie")
        @Produces("testi/*")
        public String testStar() {
            return "testi/*";
        }

        @GET
        @Path("weighted-tie")
        @Produces("testi/text")
        public String testText() {
            return "testi/text";
        }
    }
}
