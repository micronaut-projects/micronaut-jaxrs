package io.micronaut.jaxrs.conformance;

import io.micronaut.jaxrs.common.MicronautRuntimeDelegate;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.glassfish.jersey.internal.RuntimeDelegateImpl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the same operation against the Micronaut and the Jersey runtime delegates and collects the differences.
 */
final class Oracles {
    static final RuntimeDelegate MICRONAUT = new MicronautRuntimeDelegate();
    static final RuntimeDelegate JERSEY = new RuntimeDelegateImpl();

    private Oracles() {
    }

    static Object outcome(RuntimeDelegate delegate, Supplier<?> operation) {
        RuntimeDelegate previous = RuntimeDelegate.getInstance();
        RuntimeDelegate.setInstance(delegate);
        try {
            return operation.get();
        } catch (RuntimeException e) {
            return "throws " + e.getClass().getSimpleName();
        } finally {
            RuntimeDelegate.setInstance(previous);
        }
    }

    /**
     * Names a difference already triaged: a confirmed bug (label "BUG ...", reproduced in a *FindingsTest), an
     * implementation-defined difference ("IMPL ...") or a Jersey deviation ("JERSEY ..."); null when unexplained.
     */
    @FunctionalInterface
    interface Explainer {
        String explain(String category, String input, Object ours, Object jersey);
    }

    static final class Differences {
        private final Map<String, String> examples = new LinkedHashMap<>();
        private final Map<String, Integer> perCategory = new LinkedHashMap<>();
        private final Map<String, Integer> explained = new java.util.TreeMap<>();
        private final Map<String, String> explainedExample = new LinkedHashMap<>();
        private final Explainer explainer;
        private int cases;
        private int mismatches;

        Differences() {
            this((c, i, o, j) -> null);
        }

        Differences(Explainer explainer) {
            this.explainer = explainer;
        }

        void compare(String category, String input, Supplier<?> operation) {
            Object ours = outcome(MICRONAUT, operation);
            Object jersey = outcome(JERSEY, operation);
            record(category, input, ours, jersey);
        }

        void record(String category, String input, Object ours, Object jersey) {
            cases++;
            if (!Objects.equals(ours, jersey)) {
                mismatches++;
                String label = explainer.explain(category, input, ours, jersey);
                String key = category + ": " + input;
                if (label != null) {
                    explained.merge(label, 1, Integer::sum);
                    explainedExample.putIfAbsent(label, key + "\n    Micronaut=" + ours + "\n    Jersey=" + jersey);
                    return;
                }
                if (!examples.containsKey(key) && perCategory.merge(category, 1, Integer::sum) <= 12) {
                    examples.put(key, key + "\n  Micronaut=" + ours + "\n  Jersey=" + jersey);
                }
            }
        }

        void verify(long seed) {
            StringBuilder report = new StringBuilder("seed=" + seed + ", cases=" + cases + ", mismatches=" + mismatches + "\n");
            explained.forEach((label, count) -> report.append("  [").append(count).append("x] ").append(label).append("\n    e.g. ")
                .append(explainedExample.get(label)).append('\n'));
            report.append(String.join("\n", examples.values()));
            System.out.println(report);
            assertTrue(examples.isEmpty(), "unexplained differences:\n" + report);
        }
    }
}
