plugins {
    id("io.micronaut.build.internal.jaxrs-module")
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
    testImplementation(libs.junit.jupiter.params)

    testRuntimeOnly(mnLogging.logback.classic)
	testRuntimeOnly(libs.junit.jupiter.engine)
}
