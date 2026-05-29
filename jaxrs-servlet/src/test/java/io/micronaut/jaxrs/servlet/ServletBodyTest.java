package io.micronaut.jaxrs.servlet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.core.HttpHeaders;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

@MicronautTest
@Property(name = "micronaut.server.testing.async", value = "false")
public class ServletBodyTest {

    @Test
    void testInheritAnnotations(@Client("/") HttpClient client) {
        BlockingHttpClient clientBlocking = client.toBlocking();
        HttpResponse<SimpleRobot> response = clientBlocking.exchange(
            HttpRequest.POST("/20180828/simpleRobots", new CreateSimpleRobotDetails("my type")), SimpleRobot.class);

        assertEquals(200, response.getStatus().getCode());
        assertEquals("my type", response.body().type);
        assertNotNull(response.header("request-id"));
    }


//    @Bean
    static class RobotResource extends AbstractSimpleRobotBaseResource {
        @Override
        public SimpleRobot createSimpleRobot(CreateSimpleRobotDetails createSimpleRobotDetails, @Nullable String opcRetryToken, @Nullable String opcRequestId, HttpHeaders httpHeadersContext, HttpServletResponse httpServletResponse) {
            httpServletResponse.addHeader("request-id", UUID.randomUUID().toString());
            return new SimpleRobot(UUID.randomUUID().toString(), createSimpleRobotDetails.type());
        }
    }

    @jakarta.ws.rs.Path("/20180828")
    @jakarta.ws.rs.Produces({ "application/json" })
    public static abstract class AbstractSimpleRobotBaseResource  {
        @jakarta.ws.rs.POST
        @jakarta.ws.rs.Path("/simpleRobots")

        @jakarta.ws.rs.Produces({ "application/json" })
        public abstract SimpleRobot createSimpleRobot(
            CreateSimpleRobotDetails createSimpleRobotDetails,

            @Nullable @jakarta.ws.rs.HeaderParam("opc-retry-token") String opcRetryToken,

            @Nullable @jakarta.ws.rs.HeaderParam("opc-request-id") String opcRequestId
            ,
            @jakarta.ws.rs.core.Context jakarta.ws.rs.core.HttpHeaders httpHeadersContext,

            @jakarta.ws.rs.core.Context jakarta.servlet.http.HttpServletResponse httpServletResponse
        );

    }


    @Serdeable
    record SimpleRobot(String id, String type) {}

    @Serdeable
    record CreateSimpleRobotDetails(String type) {}
}
