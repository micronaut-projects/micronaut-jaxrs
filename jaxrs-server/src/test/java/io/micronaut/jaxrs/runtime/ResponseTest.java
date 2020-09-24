package io.micronaut.jaxrs.runtime;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import javax.ws.rs.core.Response;

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
}
