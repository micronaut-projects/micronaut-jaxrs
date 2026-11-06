package io.micronaut.jaxrs.common

import io.micronaut.context.ApplicationContext
import io.micronaut.http.simple.SimpleHttpHeaders
import io.micronaut.jaxrs.common.body.standard.JaxRsStringMessageBodyReaderWriter
import io.micronaut.jaxrs.common.multipart.JaxRsMultipartMessageBodyReader
import jakarta.ws.rs.core.Application
import spock.lang.Specification

class ProviderRegistrationSpec extends Specification {

    void "only the providers of Micronaut JAX-RS are standard, not any one of io.micronaut"() {
        expect:
        JaxRsProviderSelection.isStandard(JaxRsStringMessageBodyReaderWriter)
        JaxRsProviderSelection.isStandard(JaxRsMultipartMessageBodyReader)
        JaxRsProviderSelection.isStandard(JaxRsMessageBodyReader)
        !JaxRsProviderSelection.isStandard(SimpleHttpHeaders)
        !JaxRsProviderSelection.isStandard(String)
    }

    void "an unlisted provider of another io.micronaut package is not registered by an application that lists its classes"() {
        given:
        def context = ApplicationContext.run()
        context.registerSingleton(Application, new Application() {
            @Override
            Set<Class<?>> getClasses() {
                return [Object] as Set
            }
        })
        def registry = new JaxRsContainerMessageBodyHandlerRegistry(context)

        expect:
        registry.isRegistered(Object)
        registry.isRegistered(JaxRsStringMessageBodyReaderWriter)
        !registry.isRegistered(SimpleHttpHeaders)

        cleanup:
        context.close()
    }
}
