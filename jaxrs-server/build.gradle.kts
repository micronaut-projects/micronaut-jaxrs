plugins {
    id("io.micronaut.build.internal.jaxrs-module")
}

dependencies {
	annotationProcessor(mn.micronaut.graal)

	api(mn.micronaut.http)
	api(libs.managed.jaxrs.api)

	implementation(mn.micronaut.router)
	implementation(mn.micronaut.reflection)
	implementation(mn.micronaut.http.server)
    implementation(projects.micronautJaxrsCommon)

	// for Java
	testAnnotationProcessor(mn.micronaut.inject.java)
	testAnnotationProcessor(mnValidation.micronaut.validation.processor)
	testAnnotationProcessor(projects.micronautJaxrsProcessor)

	testImplementation(projects.micronautJaxrsProcessor)
    testImplementation(mnSerde.micronaut.serde.jackson)
	testImplementation(mn.micronaut.http.server.netty)
	testImplementation(mn.micronaut.http.client)
	testImplementation(mnValidation.micronaut.validation)
	testImplementation(mnTest.micronaut.test.junit5)
    testImplementation(mnTest.junit.jupiter.params)

    testRuntimeOnly(mnLogging.logback.classic)
	testRuntimeOnly(mnTest.junit.jupiter.engine)
}

noReflection {
    // the generated routes: JAX-RS converts a parameter with the static fromString or valueOf method or the
    // String constructor of its type, and hands the annotations of the resource method to the providers; a
    // sub-resource or bean parameter that is not a bean is instantiated, and its members the generated router
    // cannot access are set reflectively; the resource method of ResourceInfo is a java.lang.reflect.Method
    allowIn("io.micronaut.jaxrs.container.JaxRsRouteSupport", "ANNOTATIONS", "ANNOTATION_SYNTHESIS", "INTERFACES", "REFLECTION_UTILS", "REFLECTIVE_ACCESS", "TARGET_MEMBERS")
    // Application.getClasses() and the Feature services are classes to instantiate; a DynamicFeature gets the
    // resource method as a java.lang.reflect.Method
    allowIn("io.micronaut.jaxrs.container.JaxRsFeatures", "INTERFACES", "REFLECTIVE_ACCESS", "SERVICE_LOADING", "TARGET_MEMBERS")
    // the Application class named in the configuration is loaded and instantiated
    allowIn("io.micronaut.jaxrs.container.JaxRsApplicationFactory", "CLASS_LOADING", "REFLECTIVE_ACCESS")
    // the annotations of an Application class that is not a bean
    allowIn("io.micronaut.jaxrs.container.ApplicationProvider", "ANNOTATIONS")
    // the runtime routes collect the resource methods and their annotations, and call them
    allowIn("io.micronaut.jaxrs.container.JaxRsRuntimeRoutes", "ANNOTATIONS", "CLASS_MEMBERS", "GENERIC_SIGNATURES", "INTERFACES", "REFLECTIVE_ACCESS", "TARGET_MEMBERS")
    // the context resolvers of the application are instances too: their @Produces and type argument
    allowIn("io.micronaut.jaxrs.container.JaxRsProviders", "ANNOTATIONS", "GENERIC_SIGNATURES")
    // ResourceInfo.getResourceMethod() returns a java.lang.reflect.Method
    allowIn("io.micronaut.jaxrs.container.JaxRsResourceInfo", "TARGET_MEMBERS")
    // ResourceContext.getResource(Class) instantiates a class
    allowIn("io.micronaut.jaxrs.container.JaxRsContextResourceContext", "REFLECTIVE_ACCESS")
    // the writers get the annotations of the resource method as Annotation[]
    allowIn("io.micronaut.jaxrs.container.JaxRsContainerFilters", "ANNOTATIONS", "TARGET_MEMBERS")
    // ContainerResponseContext.getEntityAnnotations() returns Annotation[]
    allowIn("io.micronaut.jaxrs.container.JaxRsContainerResponseContext", "ANNOTATION_SYNTHESIS")
    // a ParamConverterProvider gets the annotations of the parameter as Annotation[]
    allowIn("io.micronaut.jaxrs.container.QueryParamArgumentBinder", "ANNOTATION_SYNTHESIS")
}
