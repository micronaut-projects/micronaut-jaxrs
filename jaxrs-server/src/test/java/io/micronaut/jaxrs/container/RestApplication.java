package io.micronaut.jaxrs.container;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

import java.util.Collections;
import java.util.Map;

@ApplicationPath("/api")
public class RestApplication extends Application {

    @Override
    public Map<String, Object> getProperties() {
        return Collections.singletonMap(
                "service.name", "notifications"
        );
    }
}
