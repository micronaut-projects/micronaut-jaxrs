package io.micronaut.jaxrs.processor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.core.bind.annotation.Bindable
import io.micronaut.http.annotation.CookieValue
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.QueryValue
import jakarta.ws.rs.BeanParam
import jakarta.ws.rs.CookieParam
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.MatrixParam
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import spock.lang.Unroll

class ParameterAnnotationSpec extends AbstractTypeElementSpec {

    @Unroll
    void "test map parameter annotation #source"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.GET
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

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.GET
    void test(@jakarta.ws.rs.DefaultValue("foo") @jakarta.ws.rs.PathParam("test") String test) {}
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

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.GET
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

    @jakarta.ws.rs.GET
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

    void "test jakarta.ws.rs.PathParam value"() {
        when:
        def definition =  buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test/{user_id}/v1")
class Test {

    @jakarta.ws.rs.GET
    void test(@jakarta.ws.rs.PathParam("user_id") String userId) {}
}
""")

        def method = definition.getRequiredMethod("test", String)
        def metadata = method.arguments[0].getAnnotationMetadata()


        then:
        metadata.stringValue(Bindable, "value").get() == 'user_id'
    }

    void "test jakarta.ws.rs.PathParam value with jakarta.ws.rs.DefaultValue"() {
        when:
        def definition =  buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test/{user_id}")
class Test {

    @jakarta.ws.rs.GET
    void test(@jakarta.ws.rs.DefaultValue("foo") @jakarta.ws.rs.PathParam("user_id") String userId) {}
}
""")

        def method = definition.getRequiredMethod("test", String)
        def metadata = method.arguments[0].getAnnotationMetadata()


        then:
        metadata.stringValue(PathVariable, "value").get() == 'user_id'
        metadata.stringValue(Bindable, "defaultValue").get() == 'foo'
    }

}
