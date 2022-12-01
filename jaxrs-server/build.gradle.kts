plugins {
    id("io.micronaut.build.internal.jaxrs-module")
}

dependencies {
	annotationProcessor(mn.micronaut.graal)

	api(mn.micronaut.http)
	api(libs.managed.jaxrs.api)

	implementation(mn.micronaut.router)
	implementation(mn.micronaut.http.server)

	// for Java
	testAnnotationProcessor(mn.micronaut.inject.java)
	testAnnotationProcessor(mn.micronaut.validation)
	testAnnotationProcessor(projects.jaxrsProcessor)

	testImplementation(projects.jaxrsProcessor)
    testImplementation(mnSerde.micronaut.serde.jackson)
	testImplementation(mn.micronaut.http.server.netty)
	testImplementation(mn.micronaut.http.client)
	testImplementation(mn.micronaut.validation)
	testImplementation(mnTest.micronaut.test.junit5)
	testRuntimeOnly(libs.junit.jupiter.engine)
}
