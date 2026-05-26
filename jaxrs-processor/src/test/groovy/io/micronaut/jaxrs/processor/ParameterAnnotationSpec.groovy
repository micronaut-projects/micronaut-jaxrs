package io.micronaut.jaxrs.processor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.context.annotation.Prototype
import io.micronaut.core.bind.annotation.Bindable
import io.micronaut.http.annotation.CookieValue
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.HttpMethodMapping
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.QueryValue
import jakarta.ws.rs.BeanParam
import jakarta.ws.rs.CookieParam
import jakarta.ws.rs.Encoded
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.MatrixParam
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import spock.lang.Unroll

class ParameterAnnotationSpec extends AbstractTypeElementSpec {

    static final String REQUEST_FIELD_INJECTION_ANNOTATION = "io.micronaut.jaxrs.container.JaxRsRequestFieldInjection"
    static final String SUB_RESOURCE_LOCATOR_ANNOTATION = "io.micronaut.jaxrs.container.JaxRsSubResourceLocator"

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
        source != HeaderParam || !metadata.hasAnnotation(Header)
        source != CookieParam || !metadata.hasAnnotation(CookieValue)

        where:
        source      | target       | value
        PathParam   | PathVariable | "test"
        HeaderParam | HeaderParam  | "test"
        CookieParam | CookieParam  | "test"
        QueryParam  | QueryParam   | "test"
        FormParam   | QueryValue   | "test"
        MatrixParam | MatrixParam  | "test"
    }

    void "test HeaderParam maps to Header for clients"() {
        given:
        def definition = buildBeanDefinition('test.Test$Intercepted', """
package test;

@io.micronaut.http.client.annotation.Client("/test")
interface Test {

    @jakarta.ws.rs.GET
    void test(@jakarta.ws.rs.HeaderParam("X-Test") String test);
}
""")

        def method = definition.getRequiredMethod("test", String)
        def metadata = method.arguments[0].getAnnotationMetadata()

        expect:
        metadata.hasAnnotation(Header)
        metadata.stringValue(Header).get() == 'X-Test'
    }

    void "test CookieParam maps to CookieValue for clients"() {
        given:
        def definition = buildBeanDefinition('test.Test$Intercepted', """
package test;

@io.micronaut.http.client.annotation.Client("/test")
interface Test {

    @jakarta.ws.rs.GET
    void test(@jakarta.ws.rs.CookieParam("test") String test);
}
""")

        def method = definition.getRequiredMethod("test", String)
        def metadata = method.arguments[0].getAnnotationMetadata()

        expect:
        metadata.hasAnnotation(CookieValue)
        metadata.stringValue(CookieValue).get() == 'test'
    }

    void "test MatrixParam route allows matrix path segments"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("/colors/ids")
    void test(@jakarta.ws.rs.MatrixParam("color") String color) {}
}
""")

        def method = definition.getRequiredMethod("test", String)

        expect:
        method.stringValue(HttpMethodMapping).get() == '/colors{color:;[^/]*|}/ids{color:;[^/]*|}'
    }

    void "test same class subresource locator exposes inherited resource methods"() {
        given:
        def definition = buildBeanDefinition('test.SubResource', """
package test;

@jakarta.ws.rs.Path("/base-resource")
class BaseResource {

    @jakarta.ws.rs.GET
    String get(@jakarta.ws.rs.HeaderParam("X-Test") String test) {
        return test;
    }
}

@jakarta.ws.rs.Path("resource")
class SubResource extends BaseResource {

    @jakarta.ws.rs.Path("subresource")
    SubResource subresource() {
        return this;
    }
}
""")

        def method = definition.getRequiredMethod("get", String)

        expect:
        method.stringValue(HttpMethodMapping).get() == '/subresource'
    }

    void "test subresource locator exposes returned resource method"() {
        given:
        def definition = buildBeanDefinition('test.LocatorResource', """
package test;

@jakarta.ws.rs.Path("resource")
class LocatorResource {

    @jakarta.ws.rs.Path("locator")
    MiddleResource locator(@jakarta.ws.rs.HeaderParam("X-Test") String test) {
        return new MiddleResource(test);
    }
}

class MiddleResource {
    private final String value;

    MiddleResource() {
        this.value = null;
    }

    MiddleResource(String value) {
        this.value = value;
    }

    @jakarta.ws.rs.POST
    String post() {
        return value;
    }
}
""")

        def method = definition.getRequiredMethod("locator", String)
        def metadata = method.arguments[0].getAnnotationMetadata()

        expect:
        method.hasAnnotation(Post)
        method.stringValue(HttpMethodMapping).get() == '/locator'
        method.stringValue(SUB_RESOURCE_LOCATOR_ANNOTATION).get() == 'post'
        method.classValue(SUB_RESOURCE_LOCATOR_ANNOTATION, 'type').get().name == 'test.MiddleResource'
        metadata.hasAnnotation(HeaderParam)
        metadata.stringValue(HeaderParam).get() == 'X-Test'
        !metadata.hasAnnotation(Header)
    }

    void "test Encoded MatrixParam is supported"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.GET
    void test(@jakarta.ws.rs.Encoded @jakarta.ws.rs.MatrixParam("color") String color) {}
}
""")

        def method = definition.getRequiredMethod("test", String)
        def metadata = method.arguments[0].getAnnotationMetadata()

        expect:
        metadata.hasAnnotation(Encoded)
        metadata.hasAnnotation(MatrixParam)
    }

    void "test method Encoded applies to MatrixParam arguments"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Encoded
    void test(@jakarta.ws.rs.MatrixParam("color") String color) {}
}
""")

        def method = definition.getRequiredMethod("test", String)

        expect:
        method.arguments[0].getAnnotationMetadata().hasAnnotation(Encoded)
    }

    void "test MatrixParam field marks resource for request field injection"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.MatrixParam("color")
    String color;

    @jakarta.ws.rs.GET
    String test() {
        return color;
    }
}
""")

        expect:
        definition.annotationMetadata.hasAnnotation(REQUEST_FIELD_INJECTION_ANNOTATION)
        definition.annotationMetadata.hasAnnotation(Prototype)
    }

    void "test QueryParam field marks resource for request field injection"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.QueryParam("color")
    String color;

    @jakarta.ws.rs.GET
    String test() {
        return color;
    }
}
""")

        expect:
        definition.annotationMetadata.hasAnnotation(REQUEST_FIELD_INJECTION_ANNOTATION)
        definition.annotationMetadata.hasAnnotation(Prototype)
        definition.stringValue(io.micronaut.http.annotation.Controller).get() == '/test'
    }

    void "test HeaderParam field marks resource for request field injection"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.HeaderParam("X-Color")
    String color;

    @jakarta.ws.rs.GET
    String test() {
        return color;
    }
}
""")

        expect:
        definition.annotationMetadata.hasAnnotation(REQUEST_FIELD_INJECTION_ANNOTATION)
        definition.annotationMetadata.hasAnnotation(Prototype)
    }

    void "test CookieParam field marks resource for request field injection"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.CookieParam("color")
    String color;

    @jakarta.ws.rs.GET
    String test() {
        return color;
    }
}
""")

        expect:
        definition.annotationMetadata.hasAnnotation(REQUEST_FIELD_INJECTION_ANNOTATION)
        definition.annotationMetadata.hasAnnotation(Prototype)
    }

    void "test inherited HeaderParam field marks resource for request field injection"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

class BaseResource {

    @jakarta.ws.rs.HeaderParam("X-Color")
    String color;
}

@jakarta.ws.rs.Path("/test")
class Test extends BaseResource {

    @jakarta.ws.rs.GET
    String test() {
        return color;
    }
}
""")

        expect:
        definition.annotationMetadata.hasAnnotation(REQUEST_FIELD_INJECTION_ANNOTATION)
        definition.annotationMetadata.hasAnnotation(Prototype)
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

    void "test primitive parameter has java default value"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.GET
    void test(@jakarta.ws.rs.MatrixParam("count") int count, @jakarta.ws.rs.MatrixParam("enabled") boolean enabled) {}
}
""")

        def method = definition.getRequiredMethod("test", int, boolean)

        expect:
        method.arguments[0].getAnnotationMetadata().stringValue(Bindable, "defaultValue").get() == '0'
        method.arguments[1].getAnnotationMetadata().stringValue(Bindable, "defaultValue").get() == 'false'
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
        BeanParam   | null
    }

    void "test jakarta.ws.rs.PathParam value"() {
        when:
        def definition = buildBeanDefinition('test.Test', """
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
        def definition = buildBeanDefinition('test.Test', """
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
