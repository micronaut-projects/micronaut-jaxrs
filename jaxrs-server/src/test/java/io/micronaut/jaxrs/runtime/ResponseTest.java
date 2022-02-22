package io.micronaut.jaxrs.runtime;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.hateoas.JsonError;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
public class ResponseTest {

    private final NotificationClient client;

    public ResponseTest(NotificationClient client) {
        this.client = client;
    }

    @Test
    void testPingResponse() {
        final HttpResponse<String> response = client.ping();
        assertEquals(
                HttpStatus.OK,
                response.getStatus()

        );
        assertEquals(
                "Service online: notifications",
                response.body()
        );
    }

    @Test
    void testGetResponse() {
        final Notification notification = client.getNotification(10);

        assertNotNull(notification);

        assertEquals("john", notification.getName());
    }


    @Test
    void testGetResponseV2() {
        final Notification notification = client.getNotificationV2(10);

        assertNotNull(notification);

        assertEquals("john", notification.getName());
    }

    @Test
    void testPostNotification() {
        final HttpResponse<Notification> response = client.postNotification(new Notification(5, "test", "test message"));

        assertEquals(HttpStatus.CREATED, response.status());
        assertEquals(
                "test",
                response.body().getName()
        );
    }

    @Test
    void testDefaultValue() {
        final Notification notification = client.getDefault();

        assertNotNull(notification);

        assertEquals(10, notification.getId());
        assertEquals(notification.getId(), client.getDefault(10).getId());
    }

    @Test
    void testValidation() {
        assertThrows(HttpClientResponseException.class,
                () -> client.getNotification(-10),
                "id: must be greater than or equal to 1");
    }

    @Test
    void testBadRequest() {
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class,
            () -> client.badRequest());
        assertEquals(
            HttpStatus.BAD_REQUEST,
            exception.getStatus()
        );
        assertEquals(
            "Testing bad-request",
            exception.getResponse().getBody(JsonError.class).map(JsonError::getMessage).orElse(null)
        );
    }

    @Test
    void testForbidden(){
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class,
            () -> client.forbidden());
        assertEquals(
            HttpStatus.FORBIDDEN,
            exception.getStatus()
        );
        assertEquals(
            "Testing forbidden",
            exception.getResponse().getBody(JsonError.class).map(JsonError::getMessage).orElse(null)
        );
    }

    @Test
    void testNotAcceptable(){
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class,
            () -> client.notAcceptable());
        assertEquals(
            HttpStatus.NOT_ACCEPTABLE,
            exception.getStatus()
        );
        assertEquals(
            "Testing not-acceptable",
            exception.getResponse().getBody(JsonError.class).map(JsonError::getMessage).orElse(null)
        );
    }

    @Test
    void testNotAllowed() {
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class,
            () -> client.notAllowed());
        assertEquals(
            HttpStatus.METHOD_NOT_ALLOWED,
            exception.getStatus()
        );
        assertEquals(
            "Testing not-allowed",
            exception.getResponse().getBody(JsonError.class).map(JsonError::getMessage).orElse(null)
        );
    }

    @Test
    void testNotAuthorized() {
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class,
            () -> client.notAuthorized());
        assertEquals(
            HttpStatus.UNAUTHORIZED,
            exception.getStatus()
        );
        assertEquals(
            "Testing not-authorized",
            exception.getResponse().getBody(JsonError.class).map(JsonError::getMessage).orElse(null)
        );
    }

    @Test
    void testNotFound() {
        final HttpResponse<?> response = client.notFound();
        assertEquals(
            HttpStatus.NOT_FOUND,
            response.getStatus()
        );
        assertEquals(
            "Testing not-found",
            response.getBody(JsonError.class).map(JsonError::getMessage).orElse(null)
        );
    }

    @Test
    void testNotSupported() {
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class,
            () -> client.notSupported());
        assertEquals(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            exception.getStatus()
        );
        assertEquals(
            "Testing not-supported",
            exception.getResponse().getBody(JsonError.class).map(JsonError::getMessage).orElse(null)
        );
    @Test
    void testBadRequestWithoutResponse() {
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, client::badRequestWithoutResponse);
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    @Test
    void testForbiddenWithoutResponse(){
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, client::forbiddenWithoutResponse);
        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
    }

    @Test
    void testNotAcceptableWithoutResponse(){
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, client::notAcceptableWithoutResponse);
        assertEquals(HttpStatus.NOT_ACCEPTABLE, exception.getStatus());
    }

    @Test
    void testNotFoundWithoutResponse() {
        final HttpResponse<?> response = client.notFoundWithoutResponse();
        assertEquals(HttpStatus.NOT_FOUND, response.getStatus());
    }

    @Test
    void testNotSupportedWithoutResponse() {
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, client::notSupportedWithoutResponse);
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, exception.getStatus());
    }

    @Nullable
    private static String errorsMessage(HttpClientResponseException exception) {
        return errorsMessage(exception.getResponse());
    }

    @Nullable
    private static String errorsMessage(HttpResponse<?> response) {
        return ((Map) ((List) ((Map) response.getBody(Map.class).get().get("_embedded")).get("errors")).get(0)).get("message").toString();
    }
}
