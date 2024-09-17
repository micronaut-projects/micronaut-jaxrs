package io.micronaut.jaxrs.runtime.core

import io.micronaut.context.ApplicationContext
import io.micronaut.core.type.Argument
import io.micronaut.http.HttpMethod
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.runtime.server.EmbeddedServer
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

class MethodsSpec extends Specification {

    @Shared
    @AutoCleanup
    EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer)

    @Shared
    @AutoCleanup
    HttpClient client = embeddedServer.getApplicationContext().createBean(HttpClient, embeddedServer.getURL())

    @Unroll
    void 'test #method method mappings'() {
        given:
        def response = client.toBlocking().exchange(HttpRequest.create(
                method, "/api/test-method/${method.name().toLowerCase()}"
        ).body(''), Argument.STRING)

        expect:
        response.body() == method.name().toLowerCase()


        where:
        method << [HttpMethod.PUT, HttpMethod.GET, HttpMethod.DELETE, HttpMethod.POST, HttpMethod.OPTIONS]
    }
}
