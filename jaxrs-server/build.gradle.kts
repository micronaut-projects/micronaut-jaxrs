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

	// for Groovy
	testImplementation(projects.jaxrsProcessor)

	testImplementation(mn.micronaut.http.server.netty)
	testImplementation(mn.micronaut.http.client)
	testImplementation(mn.micronaut.validation)
	testImplementation(mn.micronaut.test.junit5)
	testRuntimeOnly(libs.junit.jupiter.engine)
}
