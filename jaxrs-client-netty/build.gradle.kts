plugins {
    id("io.micronaut.build.internal.jaxrs-module")
}

micronautBuild {
    binaryCompatibility {
        enabled.set(false)
    }
}
dependencies {
    api(projects.micronautJaxrsClient)
    api(mn.micronaut.http.client)
}