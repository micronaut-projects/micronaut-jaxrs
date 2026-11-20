plugins {
    id("io.micronaut.build.internal.jaxrs-module")
}

dependencies {
	annotationProcessor(mn.micronaut.graal)

	api(mn.micronaut.http)
	api(libs.managed.jaxrs.api)

	implementation(mn.micronaut.router)
	implementation(mn.micronaut.http.server)
    implementation(projects.micronautJaxrsCommon)
    // the SSLContext of a Java SE bootstrap configuration is applied to the Netty server, when it is the server
    compileOnly(mn.micronaut.http.server.netty)
    // the Bean Validation of the resources, when Micronaut Validation is on the classpath (JAX-RS chapter 7)
    compileOnly(mnValidation.micronaut.validation)

	// for Java
	testAnnotationProcessor(mn.micronaut.inject.java)
	testAnnotationProcessor(mnValidation.micronaut.validation.processor)
	testAnnotationProcessor(projects.micronautJaxrsProcessor)

	testImplementation(projects.micronautJaxrsProcessor)
    // the compilation of resources with the route validation of the controllers, see RouteValidationSpec
    testImplementation(mn.micronaut.inject.java.test)
    testImplementation(mn.micronaut.http.validation)
    testImplementation(mnSerde.micronaut.serde.jackson)
    // the reflection of the classes the annotation processors never saw, see JaxRsReflection
    testRuntimeOnly(projects.micronautJaxrsReflection)
	testImplementation(mn.micronaut.http.server.netty)
    // the stub servlet request of a @Context servlet request, see StubHttpServletRequestTest
    testImplementation(projects.micronautJaxrsServlet)
	testImplementation(mn.micronaut.http.client)
	testImplementation(mnValidation.micronaut.validation)
	testImplementation(mnTest.micronaut.test.junit5)
    testImplementation(mnTest.junit.jupiter.params)

    testRuntimeOnly(mnLogging.logback.classic)
	testRuntimeOnly(mnTest.junit.jupiter.engine)
}

noReflection {
    // reflection is used only through JaxRsReflection, when the micronaut-jaxrs-reflection module is on the classpath
    // the parameter converters are handed the annotations as Annotation[], synthesized from the metadata
    allowIn("io.micronaut.jaxrs.container.JaxRsParameterBinding", "ANNOTATION_SYNTHESIS")
    // the Feature services
    allowIn("io.micronaut.jaxrs.container.JaxRsFeatures", "SERVICE_LOADING")
    // the Application class named by the configuration
    allowIn("io.micronaut.jaxrs.container.JaxRsApplicationFactory", "CLASS_LOADING")
    // whether the Netty server, to which the SSLContext of a Java SE bootstrap configuration is applied, is on the classpath
    allowIn("io.micronaut.jaxrs.container.JaxRsSeBootstrapServer", "CLASS_LOADING")
    // ContainerResponseContext.getEntityAnnotations() returns Annotation[]
    allowIn("io.micronaut.jaxrs.container.JaxRsContainerResponseContext", "ANNOTATION_SYNTHESIS")
    // a ParamConverterProvider gets the annotations of the parameter as Annotation[]
    allowIn("io.micronaut.jaxrs.container.QueryParamArgumentBinder", "ANNOTATION_SYNTHESIS")
}

// the server without the micronaut-jaxrs-reflection module: only the generated metadata is used
val testWithoutReflection by tasks.registering(Test::class) {
    description = "Runs the tests without the micronaut-jaxrs-reflection module on the classpath."
    group = "verification"
    val test = tasks.named<Test>("test").get()
    testClassesDirs = test.testClassesDirs
    classpath = test.classpath.filter { !it.name.startsWith("micronaut-reflection") && !it.name.startsWith("micronaut-jaxrs-reflection") && !it.path.contains("/jaxrs-reflection/build/") }
    useJUnitPlatform()
    systemProperty("micronaut.jaxrs.test.reflection", "false")
}
tasks.named("check") {
    dependsOn(testWithoutReflection)
}

