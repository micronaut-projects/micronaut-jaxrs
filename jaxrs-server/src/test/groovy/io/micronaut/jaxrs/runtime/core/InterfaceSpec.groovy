package io.micronaut.jaxrs.runtime.core


import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.jaxrs.runtime.GreeterService
import io.micronaut.jaxrs.runtime.InterfaceResourceClient
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import spock.lang.Specification

import jakarta.inject.Inject

@MicronautTest
class InterfaceSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient rootClient

    @Inject
    InterfaceResourceClient interfaceClient

    void 'test JAX-RS controller works with interface'() {
        when:
        def response = rootClient.toBlocking().exchange("/api/interface/test/ping/hello", String.class)

        then:
        response.status() == HttpStatus.OK
        response.body() == "hello"

        when:
        response = rootClient.toBlocking().exchange("/api/interface/test", String.class)

        then:
        response.status() == HttpStatus.OK
        response.body() == "noPath"
    }

    void 'test JAX-RS client works with interface'() {
        when:
        def response = interfaceClient.ping("hello")

        then:
        response.status() == HttpStatus.OK
        response.body() == "hello"

        when:
        response = interfaceClient.noPathPing()

        then:
        response.status() == HttpStatus.OK
        response.body() == "noPath"
    }

    void 'test JAX-RS controller implemented interface'() {
        when:
            def response = rootClient.toBlocking().exchange("/api/greeter/string", String.class)

        then:
            response.status() == HttpStatus.OK
            response.body() == "hello!!!"
    }

}
