plugins {
    id("io.micronaut.build.internal.jaxrs-module")
}

dependencies {
    annotationProcessor(mn.micronaut.graal)

    implementation(projects.micronautJaxrsServer)
    implementation(mnSecurity.micronaut.security)

    testAnnotationProcessor(mnValidation.micronaut.validation.processor)
    testAnnotationProcessor(mn.micronaut.inject.java)
    testAnnotationProcessor(projects.micronautJaxrsProcessor)
    testAnnotationProcessor(mnSecurity.micronaut.security.annotations)
    testAnnotationProcessor(mnSerde.micronaut.serde.processor)

    testImplementation(mnValidation.micronaut.validation)
    testImplementation(libs.managed.jaxrs.api)
    testImplementation(mn.micronaut.http.client)
    testImplementation(mn.micronaut.http.server.netty)
    testImplementation(mnTest.micronaut.test.junit5)
    testImplementation(mnSerde.micronaut.serde.jackson)

    testRuntimeOnly(mnTest.junit.jupiter.engine)
}
