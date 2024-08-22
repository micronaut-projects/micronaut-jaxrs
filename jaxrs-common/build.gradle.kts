plugins {
    id("io.micronaut.build.internal.jaxrs-module")
}

micronautBuild {
    binaryCompatibility {
        enabled.set(false)
    }
}

dependencies {
    annotationProcessor(mn.micronaut.graal)

    api(mn.micronaut.http)
    api(libs.managed.jaxrs.api)

    // for Java
    testAnnotationProcessor(mn.micronaut.inject.java)
    testAnnotationProcessor(mnValidation.micronaut.validation.processor)
    testAnnotationProcessor(projects.micronautJaxrsProcessor)

    testImplementation(projects.micronautJaxrsProcessor)
    testImplementation(mnSerde.micronaut.serde.jackson)
    testImplementation(mn.micronaut.http.server.netty)
    testImplementation(mn.micronaut.http.client)
    testImplementation(mnValidation.micronaut.validation)
    testImplementation(mnTest.micronaut.test.junit5)
    testImplementation(libs.junit.jupiter.params)

    testRuntimeOnly(mnLogging.logback.classic)
    testRuntimeOnly(libs.junit.jupiter.engine)
}
