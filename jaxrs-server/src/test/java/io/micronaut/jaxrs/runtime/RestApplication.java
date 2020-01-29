package io.micronaut.jaxrs.runtime;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;
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
