plugins {
    id("io.micronaut.build.internal.jaxrs-module")
}
dependencies {
    annotationProcessor(mn.micronaut.graal)

    api(mn.micronaut.http)
    implementation(mn.micronaut.reflection)
    api(libs.managed.jaxrs.api)

    // for Java
    testAnnotationProcessor(mn.micronaut.inject.java)
    testAnnotationProcessor(mnValidation.micronaut.validation.processor)
    testAnnotationProcessor(projects.micronautJaxrsProcessor)

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
    // UriBuilder.path(Class) and path(Class, String) read @Path of the resource class and of its methods
    allowIn("io.micronaut.jaxrs.common.JaxRsUriBuilder", "ANNOTATIONS", "CLASS_MEMBERS")
    // JAX-RS orders the providers of the application by the @Priority of their class
    allowIn("io.micronaut.jaxrs.common.JaxRsUtils", "ANNOTATIONS")
    // the JAX-RS providers are handed the annotations as Annotation[]
    allowIn("io.micronaut.jaxrs.common.JaxRsArgumentUtil", "ANNOTATION_SYNTHESIS")
    allowIn("io.micronaut.jaxrs.common.AbstractJaxRsInterceptorContext", "ANNOTATION_SYNTHESIS")
}
