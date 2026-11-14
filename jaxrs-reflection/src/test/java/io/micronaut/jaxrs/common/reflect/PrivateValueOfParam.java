package io.micronaut.jaxrs.common.reflect;

/**
 * A parameter type with a public String constructor and a private valueOf helper, see ReflectiveJaxRsReflectionSpec.
 */
public final class PrivateValueOfParam {

    private final String value;

    public PrivateValueOfParam(String value) {
        this.value = value;
    }

    private static PrivateValueOfParam valueOf(String value) {
        return new PrivateValueOfParam("helper:" + value);
    }

    public String value() {
        return value;
    }
}
