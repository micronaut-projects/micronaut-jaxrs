plugins {
    id("io.micronaut.build.internal.jaxrs-module")
}

dependencies {
    api(projects.micronautJaxrsServer)
    api(projects.micronautJaxrsClient)
    api(projects.micronautJaxrsProcessor)
    api(projects.micronautJaxrsReflection)
}

micronautBuild {
    binaryCompatibility {
        enabledAfter("5.0.1")
    }
}
