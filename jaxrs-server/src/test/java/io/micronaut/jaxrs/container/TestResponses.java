package io.micronaut.jaxrs.container;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;

/**
 * Helpers of the tests that read responses.
 */
final class TestResponses {

    private TestResponses() {
    }

    /**
     * @param client  The client
     * @param request The request
     * @return The status code of the response, an error status included
     */
    static int status(HttpClient client, HttpRequest<?> request) {
        try {
            return client.toBlocking().exchange(request, String.class).code();
        } catch (HttpClientResponseException e) {
            return e.code();
        }
    }
}
