package io.micronaut.validation.tck;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class FilterExtension implements ExecutionCondition {
    private static final String FILTER_MODE_PROPERTY = "micronaut.jaxrs.tck.filter.mode";
    private static final String FAILING_TESTS_PROPERTY = "micronaut.jaxrs.tck.failing-tests";
    private static final String SINGLE_CLASS_PROPERTY = "tckSingleClass";
    private static final String SINGLE_METHOD_PROPERTY = "tckSingleMethod";
    private static final String TCK_PACKAGE = "ee.jakarta.tck.ws.rs.";
    private static final ConditionEvaluationResult ENABLED = ConditionEvaluationResult.enabled(null);
    private static final ConditionEvaluationResult DISABLED = ConditionEvaluationResult.disabled("Disabled by JAX-RS TCK filter");

    private final Mode mode;
    private final KnownFailures knownFailures;
    private final Optional<String> singleClass;
    private final Optional<String> singleMethod;

    public FilterExtension() throws Exception {
        this.mode = Mode.from(System.getProperty(FILTER_MODE_PROPERTY, Mode.EXCLUDE_KNOWN_FAILURES.propertyValue));
        this.knownFailures = KnownFailures.load();
        this.singleClass = Optional.ofNullable(System.getProperty(SINGLE_CLASS_PROPERTY)).filter(value -> !value.isBlank());
        this.singleMethod = Optional.ofNullable(System.getProperty(SINGLE_METHOD_PROPERTY)).filter(value -> !value.isBlank());
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Class<?> testClass = context.getTestClass().orElse(null);
        if (testClass == null) {
            return ENABLED;
        }

        String className = testClass.getName();
        if (!className.startsWith(TCK_PACKAGE)) {
            return ENABLED;
        }

        Optional<Method> testMethod = context.getTestMethod();
        return switch (mode) {
            case ALL -> ENABLED;
            case EXCLUDE_KNOWN_FAILURES -> isKnownFailure(className, testMethod) ? DISABLED : ENABLED;
            case INCLUDE_KNOWN_FAILURES -> isIncludedKnownFailure(className, testMethod) ? ENABLED : DISABLED;
            case SINGLE -> isSingleTest(className, testMethod) ? ENABLED : DISABLED;
        };
    }

    private boolean isKnownFailure(String className, Optional<Method> testMethod) {
        return knownFailures.includesClass(className)
            || testMethod.map(method -> knownFailures.includesMethod(className, method.getName())).orElse(false);
    }

    private boolean isIncludedKnownFailure(String className, Optional<Method> testMethod) {
        if (knownFailures.includesClass(className)) {
            return true;
        }
        if (testMethod.isEmpty()) {
            return knownFailures.hasMethodFailures(className);
        }
        return knownFailures.includesMethod(className, testMethod.get().getName());
    }

    private boolean isSingleTest(String className, Optional<Method> testMethod) {
        if (singleClass.isEmpty()) {
            throw new IllegalStateException("singleJakartaTck requires -PtckSingleClass=...");
        }
        if (!className.equals(singleClass.get())) {
            return false;
        }
        return singleMethod.isEmpty()
            || testMethod.isEmpty()
            || testMethod.map(method -> method.getName().equals(singleMethod.get())).orElse(false);
    }

    private enum Mode {
        EXCLUDE_KNOWN_FAILURES("exclude-known-failures"),
        INCLUDE_KNOWN_FAILURES("include-known-failures"),
        SINGLE("single"),
        ALL("all");

        private final String propertyValue;

        Mode(String propertyValue) {
            this.propertyValue = propertyValue;
        }

        static Mode from(String value) {
            for (Mode mode : values()) {
                if (mode.propertyValue.equals(value)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("Unsupported JAX-RS TCK filter mode: " + value);
        }
    }

    private record KnownFailures(Set<String> classes, Map<String, Set<String>> methods) {
        static KnownFailures load() throws Exception {
            Document document = parseFailingTests();
            Set<String> classes = new HashSet<>();
            Map<String, Set<String>> methods = new HashMap<>();

            var classNodes = document.getElementsByTagName("class");
            for (int i = 0; i < classNodes.getLength(); i++) {
                Element element = (Element) classNodes.item(i);
                String className = element.getAttribute("name");
                if (!className.isBlank()) {
                    classes.add(className);
                }
            }

            var testNodes = document.getElementsByTagName("test");
            for (int i = 0; i < testNodes.getLength(); i++) {
                Element element = (Element) testNodes.item(i);
                String className = element.getAttribute("class");
                String methodName = element.getAttribute("method");
                if (!className.isBlank() && !methodName.isBlank()) {
                    methods.computeIfAbsent(className, ignored -> new HashSet<>()).add(methodName);
                }
            }

            return new KnownFailures(Collections.unmodifiableSet(classes), deepUnmodifiable(methods));
        }

        boolean includesClass(String className) {
            return classes.contains(className);
        }

        boolean includesMethod(String className, String methodName) {
            return methods.getOrDefault(className, Set.of()).contains(methodName);
        }

        boolean hasMethodFailures(String className) {
            return methods.containsKey(className);
        }

        private static Document parseFailingTests() throws Exception {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            String configuredPath = System.getProperty(FAILING_TESTS_PROPERTY);
            if (configuredPath != null && !configuredPath.isBlank()) {
                return factory.newDocumentBuilder().parse(Path.of(configuredPath).toFile());
            }

            try (InputStream inputStream = FilterExtension.class.getResourceAsStream("/failingTests.xml")) {
                if (inputStream == null) {
                    throw new IllegalStateException("No JAX-RS TCK failingTests.xml configured or found on the classpath");
                }
                return factory.newDocumentBuilder().parse(inputStream);
            }
        }

        private static Map<String, Set<String>> deepUnmodifiable(Map<String, Set<String>> source) {
            Map<String, Set<String>> result = new HashMap<>();
            source.forEach((key, value) -> result.put(key, Collections.unmodifiableSet(value)));
            return Collections.unmodifiableMap(result);
        }
    }
}
