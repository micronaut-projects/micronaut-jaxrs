plugins {
    id("io.micronaut.build.internal.jaxrs-module")
    id ("io.micronaut.build.internal.java-base")
}

dependencies {
    api(mn.micronaut.http)
    api(libs.managed.jaxrs.api)

    implementation(projects.micronautJaxrsCommon)
    implementation(mn.micronaut.http.client)
    implementation(mn.micronaut.buffer.netty)
}
