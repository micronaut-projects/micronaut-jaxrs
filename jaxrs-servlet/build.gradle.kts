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

    api(projects.micronautJaxrsServer)
    api(mnServlet.micronaut.servlet.engine)

    testImplementation(mnServlet.micronaut.http.server.jetty)

    // for Java
    testAnnotationProcessor(mn.micronaut.inject.java)
    testAnnotationProcessor(mnValidation.micronaut.validation.processor)
    testAnnotationProcessor(projects.micronautJaxrsProcessor)

    testImplementation(mnSerde.micronaut.serde.jackson)
    testImplementation(mn.micronaut.http.client)
    testImplementation(mnValidation.micronaut.validation)
    testImplementation(mnTest.micronaut.test.junit5)
    testImplementation(mnTest.junit.jupiter.params)

    testRuntimeOnly(mnLogging.logback.classic)
    testRuntimeOnly(mnTest.junit.jupiter.engine)
}
