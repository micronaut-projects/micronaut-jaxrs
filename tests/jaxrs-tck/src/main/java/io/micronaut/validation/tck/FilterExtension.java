package io.micronaut.validation.tck;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.Method;

public class FilterExtension implements ExecutionCondition {
    private static final ConditionEvaluationResult SUBRESOURCES = ConditionEvaluationResult.disabled("Subresources not supported");
    private static final ConditionEvaluationResult CLIENT = ConditionEvaluationResult.disabled("JAX-RS client not supported");

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Class<?> testClass = context.getTestClass().orElse(null);
        Method testMethod = context.getTestMethod().orElse(null);
        if (testClass == null || testMethod == null) {
            return ConditionEvaluationResult.enabled("No test class or method");
        }
        if (testClass == ee.jakarta.tck.ws.rs.ee.rs.get.JAXRSClientIT.class) {
            switch (testMethod.getName()) {
                case "headSubTest", "headTest1", "headTest2" -> {
                    return ConditionEvaluationResult.disabled("core drops HEAD bodies"); // TODO
                }
                case "dynamicGetTest", "recursiveResourceLocatorTest" -> {
                    return SUBRESOURCES;
                }
            }
        } else if (testClass == ee.jakarta.tck.ws.rs.ee.rs.headerparam.sub.JAXRSSubClientIT.class) {
            return SUBRESOURCES;
        } else if (ee.jakarta.tck.ws.rs.common.client.JaxrsCommonClient.class.isAssignableFrom(testClass) ||
                testClass == ee.jakarta.tck.ws.rs.api.client.client.JAXRSClientIT.class ||
                testClass == ee.jakarta.tck.ws.rs.api.rs.core.configurable.JAXRSClientIT.class ||
                testClass == ee.jakarta.tck.ws.rs.api.client.clientresponsecontext.JAXRSClientIT.class ||
                testClass == ee.jakarta.tck.ws.rs.api.client.clientbuilder.JAXRSClientIT.class ||
                testClass == ee.jakarta.tck.ws.rs.spec.client.instance.JAXRSClientIT.class ||
                testClass == ee.jakarta.tck.ws.rs.sebootstrap.SeBootstrapIT.class ||
                testClass == ee.jakarta.tck.ws.rs.api.rs.bindingpriority.JAXRSClientIT.class ||
                testClass == ee.jakarta.tck.ws.rs.api.rs.core.configuration.JAXRSClientIT.class ||
                testClass == ee.jakarta.tck.ws.rs.jaxrs21.ee.client.rxinvoker.JAXRSClientIT.class ||
                testClass == ee.jakarta.tck.ws.rs.jaxrs21.api.client.invocationbuilder.JAXRSClientIT.class ||
                testClass == ee.jakarta.tck.ws.rs.spec.client.webtarget.JAXRSClientIT.class ||
                testClass == ee.jakarta.tck.ws.rs.api.client.invocationcallback.JAXRSClientIT.class ||
                testClass == ee.jakarta.tck.ws.rs.jaxrs21.ee.sse.ssebroadcaster.JAXRSClientIT.class ||
                testClass == ee.jakarta.tck.ws.rs.api.client.clientrequestcontext.JAXRSClientIT.class ||
                testClass == ee.jakarta.tck.ws.rs.api.client.webtarget.JAXRSClientIT.class ||
                testClass == ee.jakarta.tck.ws.rs.api.client.invocation.JAXRSClientIT.class) {
            return CLIENT;
        }
        return ConditionEvaluationResult.enabled(null);
    }

    // this prevents automatic imports
    private static class JAXRSClientIT {}
}
