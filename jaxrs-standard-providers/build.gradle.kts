plugins {
    id("io.micronaut.build.internal.jaxrs-module")
}

dependencies {
    annotationProcessor(mn.micronaut.graal)

    api(projects.micronautJaxrsCommon)
    api(libs.jakarta.activation.api)
    api(libs.jakarta.xml.bind.api)

    // JSON-B is used when the application has an implementation
    compileOnly(libs.jakarta.json.bind.api)

    runtimeOnly(libs.jaxb.runtime)
    runtimeOnly(libs.angus.activation)

    testAnnotationProcessor(mn.micronaut.inject.java)
    testAnnotationProcessor(projects.micronautJaxrsProcessor)

    testImplementation(projects.micronautJaxrsServer)
    testImplementation(projects.micronautJaxrsClient)
    testImplementation(mn.micronaut.http.server.netty)
    testImplementation(mn.micronaut.http.client)
    testImplementation(mnSerde.micronaut.serde.jackson)
    testImplementation(mnTest.micronaut.test.junit5)
    testImplementation(libs.jakarta.json.bind.api)
    testRuntimeOnly(libs.yasson)

    testRuntimeOnly(mnLogging.logback.classic)
    testRuntimeOnly(mnTest.junit.jupiter.engine)
}

noReflection {
    // JAXB binds a class by its @XmlRootElement or @XmlType, names an element without a root element after the
    // class, and reads the type argument of a JAXBElement from the generic type JAX-RS passes
    allowIn("io.micronaut.jaxrs.providers.JaxRsJaxbMessageBodyReaderWriter", "ANNOTATIONS", "CLASS_NAMES", "GENERIC_SIGNATURES")
    // JSON-B finds its implementation with the service loader
    allowIn("io.micronaut.jaxrs.providers.JsonbImplementationCondition", "SERVICE_LOADING")
    allowIn("io.micronaut.jaxrs.providers.StandardProviders", "CLASS_LOADING")
}
