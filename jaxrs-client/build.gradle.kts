plugins {
    id("io.micronaut.build.internal.jaxrs-module")
}
dependencies {
    api(mn.micronaut.http)
    api(libs.managed.jaxrs.api)
    implementation(mnReactor.micronaut.reactor)
    implementation(projects.micronautJaxrsCommon)
    implementation(mn.micronaut.http.client)
    implementation(mn.micronaut.buffer.netty)
}

noReflection {
    // the standard providers of the other modules are services
    allowIn("io.micronaut.jaxrs.client.JaxRsClientBuilder", "SERVICE_LOADING")
    // ClientRequestContext.getEntityAnnotations() returns Annotation[]
    allowIn("io.micronaut.jaxrs.client.JaxRsClientRequestContext", "ANNOTATION_SYNTHESIS")
    // the HTTP method of a request is looked up by its name
    allowIn("io.micronaut.jaxrs.client.JaxRsInvocation", "ENUM_CONSTANTS")
}
