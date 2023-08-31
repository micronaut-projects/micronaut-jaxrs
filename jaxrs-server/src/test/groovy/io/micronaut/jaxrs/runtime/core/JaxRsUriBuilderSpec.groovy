package io.micronaut.jaxrs.runtime.core

import spock.lang.Specification

class JaxRsUriBuilderSpec extends Specification {

    def 'resolve template encodes the value'() {
        given:
        def builder = JaxRsUriBuilder.fromPath("/person")

        when:
        def result = builder.path("/{name}").resolveTemplate("name", "Tim Yates").build()

        then:
        result.toString() == '/person/Tim%20Yates'
    }

    def 'resolve template accepts multiple values'() {
        given:
        def builder = JaxRsUriBuilder.fromPath("/person/{age}")
        Map<String, Object> values = Map.of("name", "Tim Yates", "age", 43)

        when:
        def result = builder.path("/{name}").resolveTemplates(values).build()

        then:
        result.toString() == '/person/43/Tim%20Yates'
    }
}
