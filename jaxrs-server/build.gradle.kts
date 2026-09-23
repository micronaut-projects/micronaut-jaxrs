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
    // a @Context servlet request is a stub when the server is not a servlet container
    compileOnly(mnServlet.servlet.api)

	// for Java
	testAnnotationProcessor(mn.micronaut.inject.java)
	testAnnotationProcessor(mnValidation.micronaut.validation.processor)
	testAnnotationProcessor(projects.micronautJaxrsProcessor)

	testImplementation(projects.micronautJaxrsProcessor)
    testImplementation(mnSerde.micronaut.serde.jackson)
    // the reflection of the classes the annotation processors never saw, see JaxRsReflection
    testRuntimeOnly(mn.micronaut.reflection)
	testImplementation(mn.micronaut.http.server.netty)
    testImplementation(mnServlet.servlet.api)
	testImplementation(mn.micronaut.http.client)
	testImplementation(mnValidation.micronaut.validation)
	testImplementation(mnTest.micronaut.test.junit5)
    testImplementation(mnTest.junit.jupiter.params)

    testRuntimeOnly(mnLogging.logback.classic)
	testRuntimeOnly(mnTest.junit.jupiter.engine)
}

noReflection {
    // reflection is used only through JaxRsReflection, when the micronaut-reflection module is on the classpath
    // the parameter converters are handed the annotations as Annotation[], synthesized from the metadata
    allowIn("io.micronaut.jaxrs.container.JaxRsRouteSupport", "ANNOTATION_SYNTHESIS")
    // the Feature services
    allowIn("io.micronaut.jaxrs.container.JaxRsFeatures", "SERVICE_LOADING")
    // the Application class named by the configuration
    allowIn("io.micronaut.jaxrs.container.JaxRsApplicationFactory", "CLASS_LOADING")
    // ContainerResponseContext.getEntityAnnotations() returns Annotation[]
    allowIn("io.micronaut.jaxrs.container.JaxRsContainerResponseContext", "ANNOTATION_SYNTHESIS")
    // a ParamConverterProvider gets the annotations of the parameter as Annotation[]
    allowIn("io.micronaut.jaxrs.container.QueryParamArgumentBinder", "ANNOTATION_SYNTHESIS")
}

// the server without the micronaut-reflection module: only the generated metadata is used
val testWithoutReflection by tasks.registering(Test::class) {
    description = "Runs the tests without the micronaut-reflection module on the classpath."
    group = "verification"
    val test = tasks.named<Test>("test").get()
    testClassesDirs = test.testClassesDirs
    classpath = test.classpath.filter { !it.name.startsWith("micronaut-reflection") }
    useJUnitPlatform()
    filter {
        // ResourceInfo.getResourceMethod and the classes a feature registers that are not beans
        // need the module: WithoutReflectionTest checks they fail with an error that says so
        excludeTestsMatching("io.micronaut.jaxrs.runtime.core.ResourceInfoSpec")
        excludeTestsMatching("io.micronaut.jaxrs.container.FeatureTest")
        excludeTestsMatching("io.micronaut.jaxrs.container.FormParamTest")
    }
    systemProperty("micronaut.jaxrs.test.reflection", "false")
}
tasks.named("check") {
    dependsOn(testWithoutReflection)
}

