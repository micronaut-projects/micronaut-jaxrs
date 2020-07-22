package io.micronaut.jaxrs.processor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.http.annotation.CustomHttpMethod
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Head
import io.micronaut.http.annotation.HttpMethodMapping
import io.micronaut.http.annotation.Options
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Produces
import io.micronaut.http.annotation.Put
import io.micronaut.inject.BeanDefinition
import spock.lang.Unroll

import javax.ws.rs.DELETE
import javax.ws.rs.GET
import javax.ws.rs.HEAD
import javax.ws.rs.OPTIONS
import javax.ws.rs.POST
import javax.ws.rs.PUT

class HttpMethodAnnotationSpec extends AbstractTypeElementSpec {

    @Unroll
    void "test mapped annotation for #source"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@javax.ws.rs.Path("/test")
class Test {

    @${source.name}
    @javax.ws.rs.Path("$path")
    @javax.ws.rs.Produces("text/plain")
    String test() {
        return "ok";
    }
}
""")

        def method = definition.getRequiredMethod("test")

        expect:
        method.hasAnnotation(target)
        method.stringValue(HttpMethodMapping)
                .get() == '/foo'
        method.stringValue(Produces)
                .get() == 'text/plain'

        where:
        source  | target  | path
        GET     | Get     | "/foo"
        POST    | Post    | "/foo"
        PUT     | Put     | "/foo"
        DELETE  | Delete  | "/foo"
        HEAD    | Head    | "/foo"
        OPTIONS | Options | "/foo"
    }

    void "test mapping no path specified"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@javax.ws.rs.Path("/test")
class Test {

    @javax.ws.rs.GET
    @javax.ws.rs.Produces("text/plain")
    String test() {
        return "ok";
    }
}
""")

        def method = definition.getRequiredMethod("test")

        expect:
        method.hasAnnotation(Get)
        method.stringValue(HttpMethodMapping)
                .get() == '/'
        method.stringValue(Produces)
                .get() == 'text/plain'

    }


    void "test mapping with validation"() {
        given:
        def context = buildContext('test.Test', """
package test;

@javax.ws.rs.Path("/test")
@io.micronaut.validation.Validated
class Test {

    @javax.ws.rs.GET
    @javax.ws.rs.Produces("text/plain")
    String test(@javax.validation.constraints.NotBlank String val) {
        return "ok";
    }
}
""")
        def definition = context.getBeanDefinition(context.getClassLoader().loadClass('test.Test'))

        def method = definition.getRequiredMethod("test", String)

        expect:
        definition.isProxy()
        method.hasAnnotation(Get)
        method.stringValue(HttpMethodMapping)
                .get() == '/'
        method.stringValue(Produces)
                .get() == 'text/plain'

    }

    void "test mapping with implicit validation"() {
        given:
        def context = buildContext('test.Test', """
package test;

@javax.ws.rs.Path("/test")
class Test {

    @javax.ws.rs.GET
    String test(@javax.validation.constraints.NotBlank String val) {
        return "ok";
    }
}
""")
        def definition = context.getBeanDefinition(context.getClassLoader().loadClass('test.Test'))

        def method = definition.getRequiredMethod("test", String)

        expect:
        definition.isProxy()
        method.hasAnnotation(Get)
        method.stringValue(HttpMethodMapping)
                .get() == '/'

    }

    @Unroll
    void "test custom http method mapping for #method"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

import java.lang.annotation.*;

@javax.ws.rs.Path("/test")
class Test {

    @${method.toUpperCase()}
    @javax.ws.rs.Path("$path")
    @javax.ws.rs.Produces("text/plain")
    String test() {
        return "ok";
    }
}

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@javax.ws.rs.HttpMethod("$method")
@Documented
@interface ${method.toUpperCase()} {
}


""")

        def executableMethod = definition.getRequiredMethod("test")

        expect:
        executableMethod.hasAnnotation(target)
        executableMethod.stringValue(HttpMethodMapping)
                .get() == '/test'
        executableMethod.stringValue(Produces)
                .get() == 'text/plain'

        where:
        method    | target           | path
        "GET"     | Get              | "/test"
        "get"     | Get              | "/test"
        "POST"    | Post             | "/test"
        "PUT"     | Put              | "/test"
        "DELETE"  | Delete           | "/test"
        "HEAD"    | Head             | "/test"
        "OPTIONS" | Options          | "/test"
        "WATCH"   | CustomHttpMethod | "/test"
    }

    void "test mapped annotation from interface"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

interface TestService {
    @${source.name}
    @javax.ws.rs.Produces("text/plain")
    @javax.ws.rs.Path("/foo")
    public String test();
}

class Test implements TestService {
    @Override
    public String test() {
        return "ok";
    }

    @javax.ws.rs.GET
    void dummy() {}
}
""")
        def method = definition.getRequiredMethod("test")

        expect:
        method.hasAnnotation(target)
        method.stringValue(HttpMethodMapping)
                .get() == '/foo'
        method.stringValue(Produces)
                .get() == 'text/plain'

        where:
        source  | target  | path
        GET     | Get     | "/foo"
        POST    | Post    | "/foo"
        PUT     | Put     | "/foo"
        DELETE  | Delete  | "/foo"
        HEAD    | Head    | "/foo"
        OPTIONS | Options | "/foo"
    }
}
