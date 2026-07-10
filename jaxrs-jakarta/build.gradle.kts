import groovy.util.Node
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("io.micronaut.build.internal.jaxrs-module")
}

val optionalJsonDependencies = listOf(
    libs.jakarta.json.api.get(),
    libs.jakarta.json.bind.api.get()
)

dependencies {
    api(projects.micronautJaxrsServer)
    api(projects.micronautJaxrsClient)
    api(projects.micronautJaxrsProcessor)
    api(projects.micronautJaxrsReflection)
    api(projects.micronautJaxrsXml)
    compileOnly(libs.jakarta.json.api)
    compileOnly(libs.jakarta.json.bind.api)

    compileOnly(mn.micronaut.http.client)

    testImplementation(mnTest.junit.jupiter.api)
    testRuntimeOnly(mnTest.junit.jupiter.engine)
}

extensions.configure<PublishingExtension>("publishing") {
    publications.withType<MavenPublication>().configureEach {
        pom.withXml {
            val dependenciesNode = asNode().children()
                .filterIsInstance<Node>()
                .first { it.name().toString().endsWith("dependencies") }
            optionalJsonDependencies.forEach { dependency ->
                val dependencyNode = dependenciesNode.children()
                    .filterIsInstance<Node>()
                    .firstOrNull {
                        it.name().toString().endsWith("dependency")
                            && it.childText("groupId") == dependency.module.group
                            && it.childText("artifactId") == dependency.module.name
                    }
                    ?: dependenciesNode.appendNode("dependency").also {
                        it.appendNode("groupId", dependency.module.group)
                        it.appendNode("artifactId", dependency.module.name)
                        it.appendNode("version", dependency.versionConstraint.requiredVersion)
                        it.appendNode("scope", "compile")
                    }
                if (dependencyNode.childText("optional") == null) {
                    dependencyNode.appendNode("optional", "true")
                }
            }
        }
    }
}

micronautBuild {
    binaryCompatibility {
        enabledAfter("5.0.1")
    }
}

fun Node.childText(name: String): String? {
    return children()
        .filterIsInstance<Node>()
        .firstOrNull { it.name().toString().endsWith(name) }
        ?.text()
}
