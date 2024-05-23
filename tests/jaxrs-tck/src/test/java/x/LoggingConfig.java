package x;

import org.slf4j.bridge.SLF4JBridgeHandler;

import java.util.logging.Level;
import java.util.logging.LogManager;

public class LoggingConfig {
    {
        LogManager.getLogManager().getLogger("").setLevel(Level.FINEST);
        SLF4JBridgeHandler.install();
    }
}
