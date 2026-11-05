plugins {
    id("io.micronaut.build.internal.jaxrs-module")
}
dependencies {
    annotationProcessor(mn.micronaut.graal)

    api(mn.micronaut.http)
    // reflection only when the application adds it, see JaxRsReflection
    compileOnly(mn.micronaut.reflection)
    api(libs.managed.jaxrs.api)

    // for Java
    testAnnotationProcessor(mn.micronaut.inject.java)
    testAnnotationProcessor(mnValidation.micronaut.validation.processor)
    testAnnotationProcessor(projects.micronautJaxrsProcessor)

    testImplementation(mnSerde.micronaut.serde.jackson)
    testRuntimeOnly(mn.micronaut.reflection)
    testImplementation(mn.micronaut.http.server.netty)
    testImplementation(mn.micronaut.http.client)
    testImplementation(mnValidation.micronaut.validation)
    testImplementation(mnTest.micronaut.test.junit5)
    testImplementation(mnTest.junit.jupiter.params)

    testRuntimeOnly(mnLogging.logback.classic)
    testRuntimeOnly(mnTest.junit.jupiter.engine)
}

noReflection {
    // the only class that uses reflection, when the micronaut-reflection module is on the classpath
    allowIn("io.micronaut.jaxrs.common.reflect.ReflectiveJaxRsReflection", "ANNOTATIONS", "CLASS_MEMBERS", "INTERFACES",
        "REFLECTION_UTILS", "REFLECTIVE_ACCESS", "TARGET_MEMBERS")
    // the JAX-RS providers are handed the annotations as Annotation[], synthesized from the metadata
    allowIn("io.micronaut.jaxrs.common.JaxRsArgumentUtil", "ANNOTATION_SYNTHESIS")
    allowIn("io.micronaut.jaxrs.common.AbstractJaxRsInterceptorContext", "ANNOTATION_SYNTHESIS")
    // the generic signature of a class (class metadata of the JDK, kept in a native image), e.g. the type
    // argument of an anonymous InvocationCallback, which the processors record no type hierarchy of
    allowIn("io.micronaut.jaxrs.common.reflect.GenericSignatures", "GENERIC_SIGNATURES")
    // SeBootstrap starts the server of the server module, which it provides as a service
    allowIn("io.micronaut.jaxrs.common.MicronautRuntimeDelegate", "SERVICE_LOADING")
    // whether the micronaut-reflection module is on the classpath, and its implementation
    allowIn("io.micronaut.jaxrs.common.reflect.JaxRsReflection", "CLASS_LOADING", "SERVICE_LOADING")
    // the synthesized annotations of the metadata, and the String conversions of the parameter types,
    // which the processor generates as services
    allowIn("io.micronaut.jaxrs.common.reflect.MetadataJaxRsReflection", "ANNOTATION_SYNTHESIS", "SERVICE_LOADING")
    // ResourceInfo.getResourceMethod returns a java.lang.reflect.Method by API contract: the one method the
    // metadata names is looked up, no members are scanned; needs a reflection config entry in a native image
    allowIn("io.micronaut.jaxrs.common.reflect.ResourceMethods", "CLASS_MEMBERS")
}
