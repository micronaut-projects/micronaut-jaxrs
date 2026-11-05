package x;

import java.lang.reflect.Method;
import java.util.Set;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Disables the tests the system property {@code tck.excludedTests} names, as {@code Class#method}
 * separated by commas: Gradle's test filter does not reach the tests the suites select.
 */
public class ExcludedTests implements ExecutionCondition {

    private static final Set<String> EXCLUDED = Set.of(System.getProperty("tck.excludedTests", "").split(","));

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Method method = context.getTestMethod().orElse(null);
        if (method != null && EXCLUDED.contains(context.getRequiredTestClass().getName() + "#" + method.getName())) {
            return ConditionEvaluationResult.disabled("excluded by tck.excludedTests");
        }
        return ConditionEvaluationResult.enabled("not excluded");
    }
}
