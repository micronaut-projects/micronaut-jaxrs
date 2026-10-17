plugins {
    id("io.micronaut.build.internal.jaxrs-module")
}
dependencies {
    api(mn.micronaut.http)
    api(libs.managed.jaxrs.api)
    implementation(mnReactor.micronaut.reactor)
    implementation(projects.micronautJaxrsCommon)
    implementation(mn.micronaut.http.client)
    implementation(mn.micronaut.buffer.netty)

    testAnnotationProcessor(mn.micronaut.inject.java)
    testImplementation(mn.micronaut.http.server.netty)
    testImplementation(mnTest.micronaut.test.junit5)
    testImplementation(mnSerde.micronaut.serde.jackson)
    // the reflection of the providers the tests register, see JaxRsReflection
    testRuntimeOnly(mn.micronaut.reflection)
    testRuntimeOnly(mnLogging.logback.classic)
    testRuntimeOnly(mnTest.junit.jupiter.engine)
}

noReflection {
    // the standard providers of the other modules are services
    allowIn("io.micronaut.jaxrs.client.JaxRsClientBuilder", "SERVICE_LOADING")
    // ClientRequestContext.getEntityAnnotations() returns Annotation[]
    allowIn("io.micronaut.jaxrs.client.JaxRsClientRequestContext", "ANNOTATION_SYNTHESIS")
}
