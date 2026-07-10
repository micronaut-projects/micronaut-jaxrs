plugins {
    id("io.micronaut.build.internal.jaxrs-module")
}

dependencies {
    implementation(mn.micronaut.aop)
    implementation(mn.micronaut.http)
    api(projects.micronautJaxrsCommon)
    implementation(projects.micronautJaxrsClient)
    implementation(projects.micronautJaxrsServer)
    api(libs.managed.jaxrs.api)

    implementation(mn.micronaut.inject)

    testAnnotationProcessor(mn.micronaut.inject.java)
    testAnnotationProcessor(projects.micronautJaxrsProcessor)

    testImplementation(mn.micronaut.http.client)
    testImplementation(mn.micronaut.http.server.netty)
    testImplementation(mnSerde.micronaut.serde.jackson)
    testImplementation(mnTest.micronaut.test.junit5)

    testRuntimeOnly(mnLogging.logback.classic)
    testRuntimeOnly(mnTest.junit.jupiter.engine)
}

micronautBuild {
    binaryCompatibility {
        enabledAfter("5.0.1")
    }
}
