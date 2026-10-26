package example.extension;

import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;

/**
 * A feature of the Java service loader: configured only by the application that sets the probe
 * property, see {@link FeatureExtensionsTest}, which disables the service loading.
 */
public class ServiceFeature implements Feature {
    static final String PROBE = "service.feature.probe";
    static volatile boolean configured;

    @Override
    public boolean configure(FeatureContext context) {
        if (context.getConfiguration().getProperty(PROBE) != null) {
            configured = true;
        }
        return false;
    }
}
