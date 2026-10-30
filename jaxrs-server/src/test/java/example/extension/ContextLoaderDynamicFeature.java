package example.extension;

import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.FeatureContext;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A dynamic feature of the service loader that only the context class loader of
 * {@link ServiceDiscoveryTest} lists.
 */
public class ContextLoaderDynamicFeature implements DynamicFeature {
    static final AtomicInteger CONFIGURED = new AtomicInteger();

    @Override
    public void configure(ResourceInfo resourceInfo, FeatureContext context) {
        CONFIGURED.incrementAndGet();
    }
}
