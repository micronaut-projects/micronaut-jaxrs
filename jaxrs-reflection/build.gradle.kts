plugins {
    id("io.micronaut.build.internal.jaxrs-module")
}

dependencies {
    api(projects.micronautJaxrsCommon)
    // the reflective JaxRsReflection: added by an application that needs reflection
    api(mn.micronaut.reflection)

    testImplementation(mnTest.micronaut.test.junit5)
    testRuntimeOnly(mnTest.junit.jupiter.engine)
}

noReflection {
    // the reflective implementation of the reflection SPI of jaxrs-common
    allowIn("io.micronaut.jaxrs.common.reflect.ReflectiveJaxRsReflection", "ANNOTATIONS", "CLASS_MEMBERS", "INTERFACES",
        "REFLECTION_UTILS", "REFLECTIVE_ACCESS", "TARGET_MEMBERS")
}
