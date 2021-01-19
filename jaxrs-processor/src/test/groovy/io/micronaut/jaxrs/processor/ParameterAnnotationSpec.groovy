package io.micronaut.jaxrs.processor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.core.bind.annotation.Bindable
import io.micronaut.http.annotation.CookieValue
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.QueryValue
import spock.lang.Unroll

import javax.ws.rs.BeanParam
import javax.ws.rs.CookieParam
import javax.ws.rs.FormParam
import javax.ws.rs.HeaderParam
import javax.ws.rs.MatrixParam
import javax.ws.rs.PathParam
import javax.ws.rs.QueryParam

class ParameterAnnotationSpec extends AbstractTypeElementSpec {

    @Unroll
    void "test map parameter annotation #source"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@javax.ws.rs.Path("/test")
class Test {

    @javax.ws.rs.GET
    void test(@$source.name("$value") String test) {}
}
""")

        def method = definition.getRequiredMethod("test", String)
        def metadata = method.arguments[0].getAnnotationMetadata()

        expect:
        metadata.hasAnnotation(target)
        metadata.stringValue(target)
                .get() == 'test'

        where:
        source      | target       | value
        PathParam   | PathVariable | "test"
        HeaderParam | Header       | "test"
        CookieParam | CookieValue  | "test"
        QueryParam  | QueryValue   | "test"
        FormParam   | QueryValue   | "test"
    }

    void "test default value"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@javax.ws.rs.Path("/test")
class Test {

    @javax.ws.rs.GET
    void test(@javax.ws.rs.DefaultValue("foo") @javax.ws.rs.PathParam("test") String test) {}
}
""")

        def method = definition.getRequiredMethod("test", String)
        def metadata = method.arguments[0].getAnnotationMetadata()

        expect:
        metadata.stringValue(Bindable, "defaultValue").get() == 'foo'
    }

    @Unroll
    void "test unsupported parameter annotation #source"() {
        when:
        buildBeanDefinition('test.Test', """
package test;

@javax.ws.rs.Path("/test")
class Test {

    @javax.ws.rs.GET
    void test(@$source.name(${value ? "\"$value\"" : ""}) String test) {}
}
""")


        then:
        def e = thrown(RuntimeException)
        e.message.contains("Unsupported JAX-RS annotation used on method: $source.name")

        where:
        source      | value
        MatrixParam | "test"
        BeanParam   | null
    }

    @Unroll
    void "test unsupported parameter annotation #source with @Controller"() {
        when:
        buildBeanDefinition('test.Test', """
package test;

@io.micronaut.http.annotation.Controller("/test")
class Test {

    @javax.ws.rs.GET
    void test(@$source.name(${value ? "\"$value\"" : ""}) String test) {}
}
""")

        then:
        def e = thrown(RuntimeException)
        e.message.contains("Unsupported JAX-RS annotation used on method: $source.name")

        where:
        source      | value
        MatrixParam | "test"
        BeanParam   | null
    }

    void "test javax.ws.rs.PathParam value"() {
        when:
        def definition =  buildBeanDefinition('test.Test', """
package test;

@javax.ws.rs.Path("/test/{user_id}/v1")
class Test {

    @javax.ws.rs.GET
    void test(@javax.ws.rs.PathParam("user_id") String userId) {}
}
""")

        def method = definition.getRequiredMethod("test", String)
        def metadata = method.arguments[0].getAnnotationMetadata()


        then:
        metadata.stringValue(Bindable, "value").get() == 'user_id'
    }

    void "test javax.ws.rs.PathParam value with javax.ws.rs.DefaultValue"() {
        when:
        def definition =  buildBeanDefinition('test.Test', """
package test;

@javax.ws.rs.Path("/test/{user_id}")
class Test {

    @javax.ws.rs.GET
    void test(@javax.ws.rs.DefaultValue("foo") @javax.ws.rs.PathParam("user_id") String userId) {}
}
""")

        def method = definition.getRequiredMethod("test", String)
        def metadata = method.arguments[0].getAnnotationMetadata()


        then:
        metadata.stringValue(PathVariable, "value").get() == 'user_id'
        metadata.stringValue(Bindable, "defaultValue").get() == 'foo'
    }

}
