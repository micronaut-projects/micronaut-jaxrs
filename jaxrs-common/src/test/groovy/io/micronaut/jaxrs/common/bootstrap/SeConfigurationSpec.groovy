package io.micronaut.jaxrs.common.bootstrap

import jakarta.ws.rs.SeBootstrap
import spock.lang.Specification

class SeConfigurationSpec extends Specification {

    void "a defaulted property is a property of the configuration"() {
        given:
        def configuration = new JaxRsSeConfiguration([:])

        expect:
        configuration.hasProperty(name)
        configuration.property(name) != null
        !configuration.properties().containsKey(name)

        where:
        name << [SeBootstrap.Configuration.PROTOCOL, SeBootstrap.Configuration.HOST, SeBootstrap.Configuration.PORT,
                 SeBootstrap.Configuration.ROOT_PATH, SeBootstrap.Configuration.SSL_CLIENT_AUTHENTICATION]
    }

    void "a property without a default is not a property until it is set"() {
        expect:
        !new JaxRsSeConfiguration([:]).hasProperty("x")
        new JaxRsSeConfiguration([x: 1]).hasProperty("x")
    }
}
