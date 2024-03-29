plugins {
    id("io.micronaut.build.internal.jaxrs-module")
}

dependencies {
	annotationProcessor(mn.micronaut.graal)

	implementation(projects.micronautJaxrsServer)
    implementation(mnSecurity.micronaut.security)

	testAnnotationProcessor(mnValidation.micronaut.validation.processor)
	testImplementation(mnValidation.micronaut.validation)
	testAnnotationProcessor(mn.micronaut.inject.java)
	testAnnotationProcessor(projects.micronautJaxrsProcessor)
	testAnnotationProcessor(mnSecurity.micronaut.security.annotations)

	testImplementation(libs.managed.jaxrs.api)
	testImplementation(mn.micronaut.http.client)
	testImplementation(mn.micronaut.http.server.netty)
	testImplementation(mnTest.micronaut.test.junit5)

	testRuntimeOnly(libs.junit.jupiter.engine)

    testAnnotationProcessor(mnSerde.micronaut.serde.processor)
    testImplementation(mnSerde.micronaut.serde.jackson)
}
