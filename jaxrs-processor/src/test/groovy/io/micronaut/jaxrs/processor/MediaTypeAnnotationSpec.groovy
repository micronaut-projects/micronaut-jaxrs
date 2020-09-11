package io.micronaut.jaxrs.processor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.http.annotation.Consumes
import io.micronaut.http.annotation.Produces

class MediaTypeAnnotationSpec extends AbstractTypeElementSpec {

    void "test that values are set for produces and consumes #source"() {
        given:
            def definition = buildBeanDefinition('test.Test', """
package test;

@javax.ws.rs.Path("/test")
class Test {

    @javax.ws.rs.GET
    @javax.ws.rs.Consumes($source)
    @javax.ws.rs.Produces($source)
    void test(@javax.ws.rs.PathParam("test") String test) {}
}
""")

            def method = definition.getRequiredMethod("test", String)
            def metadata = method.annotationMetadata

        expect:
            metadata.findAnnotation(Produces).get().values['value'] == value
            metadata.findAnnotation(Consumes).get().values['value'] == value

        where:
            source                                 | value
            '{ "application/json", "text/plain" }' | ["application/json", "text/plain"]
            '{ "application/json" }'               | ["application/json"]
            '"application/json"'                   | ["application/json"]
            ''                                     | ["*/*"]
    }

}
