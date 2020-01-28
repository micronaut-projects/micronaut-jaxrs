package io.micronaut.jaxrs.runtime.core;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.PropertySource;
import io.micronaut.core.annotation.Internal;

import javax.ws.rs.core.Application;
import java.util.Set;

/**
 * Registers runtime singletons and adds {@link Application} as a property source.
 *
 * @author graemerocher
 * @since 1.0
 */
@Context
@Requires(beans = Application.class)
@Internal
public class JaxRsRuntime {
    /**
     * Default constructor.
     * @param applicationContext The application context
     * @param application The application
     */
    protected JaxRsRuntime(ApplicationContext applicationContext, Application application) {
        final Set<Object> singletons = application.getSingletons();
        for (Object singleton : singletons) {
            if (singleton != null) {
                applicationContext.registerSingleton(singleton);
            }
        }
        applicationContext.getEnvironment().addPropertySource(
                PropertySource.of(
                        application.getClass().getName(),
                        application.getProperties()
                )
        );
    }
}
