package io.micronaut.jaxrs.processor

import io.micronaut.inject.visitor.TypeElementVisitor
import jakarta.ws.rs.ApplicationPath
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.ext.Provider
import spock.lang.Specification

class JaxRsApplicationProcessorSpec extends Specification {

    void "application processor is isolated to JAX-RS application annotations"() {
        given:
        def processor = new JaxRsApplicationProcessor()

        expect:
        processor.visitorKind == TypeElementVisitor.VisitorKind.ISOLATING
        processor.supportedAnnotationNames == [
                ApplicationPath.getName(),
                Path.getName(),
                Provider.getName(),
                Context.getName()
        ] as Set
    }
}
