package io.micronaut.jaxrs.runtime;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.jaxrs.runtime.ext.bind.JaxRsHttpHeaders;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.Assert;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import javax.ws.rs.core.*;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@MicronautTest
class HeadersTest {

    @Inject HeaderClient headerClient;

    @Inject @Client("/api")
    HttpClient httpClient;

    @Inject @Client("/")
    HttpClient rootClient;

    @Test
    void testMicronautResponse() {
        String result = rootClient.toBlocking().retrieve("/api/micronaut/test");
        assertEquals(
                "ok",
                result
        );
    }

    @Test
    void testCacheControl() {
        final CacheControl cacheControl = new CacheControl();
        cacheControl.setNoCache(true);
        cacheControl.setNoStore(true);
        final HttpResponse<String> response = headerClient.cacheControl(cacheControl);
        final String result = response.body();

        assertEquals(
                "no-cache, no-transform, no-store",
                result
        );


        final CacheControl cc = response.getHeaders()
                .getFirst(javax.ws.rs.core.HttpHeaders.CACHE_CONTROL, CacheControl.class)
                .get();
        Assertions.assertTrue(
                cc.isNoCache()
        );
        Assertions.assertTrue(
                cc.isNoStore()
        );
    }

    @Test
    void testContentType() {
        final String result = headerClient.contentType(MediaType.valueOf("application/json"));

        assertEquals(
                "application/json",
                result
        );
    }

    @Test
    void testNullMediaType() {
        NullPointerException npe = assertThrows(NullPointerException.class, () -> MediaType.valueOf(null));
        assertEquals("Argument [type] cannot be null", npe.getMessage());
    }

    @Test
    void testToStringNewMediaType() {
        MediaType mediaType = MediaType.valueOf("foo/*");
        assertEquals("foo/*", mediaType.toString());
    }

    @Test
    void testToStringCacheControl() {
        CacheControl cacheControl = new CacheControl();
        cacheControl.setMustRevalidate(true);
        cacheControl.setNoCache(true);
        cacheControl.setNoTransform(true);
        cacheControl.setProxyRevalidate(true);
        cacheControl.setPrivate(true);
        cacheControl.setMaxAge(0);
        cacheControl.setSMaxAge(0);
        cacheControl.getCacheExtension().put("foo", "bar");

        assertEquals(
            "no-cache, must-revalidate, no-transform, proxy-revalidate, s-maxage=0, max-age=0, private, foo=\"bar\"",
            cacheControl.toString()
        );
    }


    @Test
    void testCookie() {
        final String result = headerClient.cookie(new Cookie("foo", "bar"));

        assertEquals(
                "bar",
                result
        );
    }

    @Test
    void testEtag() {
        final String result = headerClient.etag(new EntityTag("foo"));

        assertEquals(
                "foo",
                result
        );
    }

    @Test
    void testLink() {
        final HttpResponse<String> response = headerClient.link(Link.fromResource(HeadersResource.class).build());
        final String result = response.body();

        assertEquals(
                "</headers>",
                result
        );


        assertEquals(
                "/blah",
                response.getHeaders().getFirst(javax.ws.rs.core.HttpHeaders.LINK, Link.class)
                    .get().getUri().toString()
        );
    }

    @Test
    void testAcceptHeader() {
        final MutableHttpRequest<Object> request = HttpRequest.GET("/headers");
        request.accept(new io.micronaut.http.MediaType("text/plain"), new io.micronaut.http.MediaType("application/json;q=0.7"), new io.micronaut.http.MediaType(
                "application/xml;q=0.9"
        ));
        final HttpResponse<String> response = httpClient.toBlocking().exchange(request, String.class);

        JaxRsHttpHeaders jaxRsHttpHeaders = new JaxRsHttpHeaders(response.getHeaders());

        final List<MediaType> acceptableMediaTypes = jaxRsHttpHeaders.getAcceptableMediaTypes();
        assertEquals(
                3,
                acceptableMediaTypes.size()
        );

        assertEquals(
                "plain",
                acceptableMediaTypes.get(0).getSubtype()
        );
    }

    @Test
    void testAcceptLanguageHeader() {
        final MutableHttpRequest<Object> request = HttpRequest.GET("/headers");
        request.header(HttpHeaders.ACCEPT_LANGUAGE, "fr;q=0.7,en;q=0.9");
        final HttpResponse<String> response = httpClient.toBlocking().exchange(request, String.class);

        JaxRsHttpHeaders jaxRsHttpHeaders = new JaxRsHttpHeaders(response.getHeaders());

        final List<Locale> acceptableLanguages = jaxRsHttpHeaders.getAcceptableLanguages();
        assertEquals(
                2,
                acceptableLanguages.size()
        );

        assertEquals(
                "en",
                acceptableLanguages.get(0).getLanguage()
        );
    }
}
