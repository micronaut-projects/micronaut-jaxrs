plugins {
    id("io.micronaut.build.internal.jaxrs-module")
}

dependencies {
    annotationProcessor(mn.micronaut.graal)

    api(projects.micronautJaxrsCommon)
    api(libs.jakarta.activation.api)
    api(libs.jakarta.xml.bind.api)

    implementation(projects.micronautJaxrsClient)

    testAnnotationProcessor(mn.micronaut.inject.java)
    testAnnotationProcessor(projects.micronautJaxrsProcessor)

    testImplementation(projects.micronautJaxrsProcessor)
    testImplementation(projects.micronautJaxrsServer)
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
