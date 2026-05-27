package io.micronaut.jaxrs.container;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.jaxrs.runtime.ext.bind.UriInfoImpl;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

@MicronautTest
class UriInfoTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void testInjectUriInfo() {
        //This endpoint has an instance of UriInfo injected
        HttpRequest<String> request = HttpRequest.GET("/api/uri-info");
        //In the HTTP response body the endpoint prints the path from the injected UriInfo
        String respBody = client.toBlocking().retrieve(request, String.class);
        Assertions.assertEquals("test path: /uri-info", respBody);
    }

    @Test
    void testMatchedUris() {
        HttpRequest<String> request = HttpRequest.GET("/api/matched/uris");
        String respBody = client.toBlocking().retrieve(request, String.class);
        Assertions.assertEquals("matched/uris,matched", respBody);
    }

    @Test
    void testMatchedResources() {
        HttpRequest<String> request = HttpRequest.GET("/api/matched/resources");
        String respBody = client.toBlocking().retrieve(request, String.class);
        Assertions.assertEquals(TestMatchedUriInfo.class.getName(), respBody);
    }

    @Test
    void testMatchedResourceTemplate() {
        HttpRequest<String> request = HttpRequest.GET("/api/matched/template/abc");
        String respBody = client.toBlocking().retrieve(request, String.class);
        Assertions.assertEquals("/api/matched/template/{id:[a-z]+}", respBody);
    }

    @Test
    void testMatchedSubResourceTemplate() {
        HttpRequest<String> request = HttpRequest.GET("/api/matched/subtemplate/abc/def");
        String respBody = client.toBlocking().retrieve(request, String.class);
        Assertions.assertEquals("/api/matched/subtemplate/{id:[a-z]+}/{child:[a-z]+}", respBody);
    }

    @Test
    void testAbsolutePath() {
        UriInfo actualUri = new UriInfoImpl(HttpRequest.GET("http://example.com/foo/?bar=baz&bar=bam"));
        Assertions.assertEquals("http://example.com/foo/", actualUri.getAbsolutePath().toString());
    }

    @Test
    void testBaseUri() {
        UriInfo actualUri = new UriInfoImpl(HttpRequest.GET("http://example.com/foo/?bar=baz&bar=bam"));
        Assertions.assertEquals("http://example.com/", actualUri.getBaseUri().toString());

        UriInfo actualUriWithBasePath = new UriInfoImpl(HttpRequest.GET("http://example.com/foo/bar?baz=bam"), "/foo");
        Assertions.assertEquals("http://example.com/foo/", actualUriWithBasePath.getBaseUri().toString());
    }

    @Test
    void testPath() {
        jakarta.ws.rs.core.UriInfo expectedUri = new UriInfoImpl(HttpRequest.GET("http://example.com/foo/?bar=baz&bar=bam"));

        UriInfo actualUri = new UriInfoImpl(HttpRequest.GET("http://example.com/foo/?bar=baz&bar=bam"));
        Assertions.assertEquals(expectedUri.getPath(), actualUri.getPath());
        Assertions.assertEquals(expectedUri.getPath(true), actualUri.getPath(true));
    }

    @Test
    void testEncodedPath() {
        jakarta.ws.rs.core.UriInfo expectedUri = new UriInfoImpl(HttpRequest.GET("http://example.com/foo/?bar=baz&bar=bam"));

        UriInfo actualUri = new UriInfoImpl(HttpRequest.GET("http://example.com/foo/?bar=baz&bar=bam"));
        Assertions.assertEquals(expectedUri.getPath(false), actualUri.getPath(false));
    }

    @Test
    void testPathSegments() {
        jakarta.ws.rs.core.UriInfo expectedUri = new UriInfoImpl(HttpRequest.GET("http://example.com/foo;color=red/bar;color=green/?baz=bam"));

        UriInfo actualUri =
            new UriInfoImpl(HttpRequest.GET("http://example.com/foo;color=red/bar;color=green?baz=bam"));
        Assertions.assertEquals(expectedUri.getPathSegments().get(0).getPath(),
            actualUri.getPathSegments().get(0).getPath());
        Assertions.assertEquals(expectedUri.getPathSegments(true).get(0).getPath(),
            actualUri.getPathSegments(true).get(0).getPath());
    }

    @Test
    void testEncodedPathSegments() {
        jakarta.ws.rs.core.UriInfo expectedUri = new UriInfoImpl(HttpRequest.GET("http://example.com/foo;color=red/bar;color=green/?baz=bam"));

        UriInfo actualUri =
            new UriInfoImpl(HttpRequest.GET("http://example.com/foo;color=red/bar;color=green/?baz=bam"));
        Assertions.assertEquals(expectedUri.getPathSegments(false).get(0).getPath(),
            actualUri.getPathSegments(false).get(0).getPath());
    }

    @Test
    void testRawEncodedPathSegments() {
        UriInfo actualUri =
            new UriInfoImpl(HttpRequest.GET("http://example.com/foo%20bar;color=red%20blue/?baz=bam"));

        Assertions.assertEquals("foo%20bar", actualUri.getPathSegments(false).get(0).getPath());
        Assertions.assertEquals("red%20blue", actualUri.getPathSegments(false).get(0).getMatrixParameters().getFirst("color"));
    }

    @Test
    void testPathMatrixParameters() {
        jakarta.ws.rs.core.UriInfo expectedUri = new UriInfoImpl(HttpRequest.GET("http://example.com/foo;color=red;color=green/bar;color=blue/?baz=bam"));

        UriInfo actualUri =
            new UriInfoImpl(HttpRequest.GET("http://example.com/foo;color=red;color=green/bar;color=blue/?baz=bam"));
        Assertions.assertEquals(
            Arrays.asList("red", "green"),
            actualUri.getPathSegments().get(0).getMatrixParameters().get("color"));
        Assertions.assertEquals(
            expectedUri.getPathSegments().get(0).getMatrixParameters().get("color"),
            actualUri.getPathSegments().get(0).getMatrixParameters().get("color"));

        Assertions.assertEquals(
            Arrays.asList("blue"),
            actualUri.getPathSegments().get(1).getMatrixParameters().get("color"));
        Assertions.assertEquals(
            Arrays.asList("blue"),
            actualUri.getPathSegments(false).get(1).getMatrixParameters().get("color"));
        Assertions.assertEquals(
            expectedUri.getPathSegments().get(1).getMatrixParameters().get("color"),
            actualUri.getPathSegments().get(1).getMatrixParameters().get("color"));
    }

    @Test
    void testEncodedPathMatrixParameters() {
        jakarta.ws.rs.core.UriInfo expectedUri = new UriInfoImpl(HttpRequest.GET("http://example.com/foo;color=red/bar;color=green/?baz=bam"));

        System.out.println(expectedUri.getPathSegments().get(0).getMatrixParameters().get("color"));
        UriInfo actualUri =
            new UriInfoImpl(HttpRequest.GET("http://example.com/foo;color=red/bar;color=green/?baz=bam"));
        Assertions.assertEquals(
            actualUri.getPathSegments(false).get(0).getMatrixParameters().get("color"),
            expectedUri.getPathSegments(false).get(0).getMatrixParameters().get("color"));
        Assertions.assertEquals(
            actualUri.getPathSegments(false).get(1).getMatrixParameters().get("color"),
            expectedUri.getPathSegments(false).get(1).getMatrixParameters().get("color"));
    }

    @Test
    void testQueryParams() {
        jakarta.ws.rs.core.UriInfo expectedUri = new UriInfoImpl(HttpRequest.GET("http://example.com/foo/?bar=baz&bar=bam"));

        UriInfo actualUri = new UriInfoImpl(HttpRequest.GET("http://example.com/foo/?bar=baz&bar=bam"));
        Assertions.assertEquals(expectedUri.getQueryParameters().get("bar"), actualUri.getQueryParameters().get("bar"));
        Assertions.assertEquals(expectedUri.getQueryParameters(true).get("bar"), actualUri.getQueryParameters(true).get("bar"));
    }

    @Test
    void testEncodedQueryParams() {
        jakarta.ws.rs.core.UriInfo expectedUri = new UriInfoImpl(HttpRequest.GET("http://example.com/foo/?bar=baz&bar=bam"));

        UriInfo actualUri = new UriInfoImpl(HttpRequest.GET("http://example.com/foo/?bar=baz&bar=bam"));
        Assertions.assertEquals(expectedUri.getQueryParameters(false).get("bar"), actualUri.getQueryParameters(false).get("bar"));
    }

    @Test
    void testRequestUri() {
        jakarta.ws.rs.core.UriInfo expectedUri = new UriInfoImpl(HttpRequest.GET("http://example.com/foo/?bar=baz&bar=bam"));

        UriInfo actualUri = new UriInfoImpl(HttpRequest.GET("http://example.com/foo/?bar=baz&bar=bam"));
        Assertions.assertEquals(expectedUri.getRequestUri(), actualUri.getRequestUri());
    }

    @Test
    void testUriBuilders() {
        UriInfo actualUri = new UriInfoImpl(HttpRequest.GET("http://example.com/foo/?bar=baz&bar=bam"), "/foo");
        Assertions.assertEquals(actualUri.getRequestUri(), actualUri.getRequestUriBuilder().build());
        Assertions.assertEquals(actualUri.getAbsolutePath(), actualUri.getAbsolutePathBuilder().build());
        Assertions.assertEquals(actualUri.getBaseUri(), actualUri.getBaseUriBuilder().build());
    }

}
