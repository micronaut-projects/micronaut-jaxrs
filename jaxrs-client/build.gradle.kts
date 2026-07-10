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

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnSerde.micronaut.serde.jackson)

    testRuntimeOnly(mnLogging.logback.classic)
    testRuntimeOnly(mnTest.junit.jupiter.engine)
}
