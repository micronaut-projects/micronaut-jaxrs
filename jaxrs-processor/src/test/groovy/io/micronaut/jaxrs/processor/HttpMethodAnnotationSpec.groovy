package io.micronaut.jaxrs.processor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.CustomHttpMethod
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Head
import io.micronaut.http.annotation.HttpMethodMapping
import io.micronaut.http.annotation.Options
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Produces
import io.micronaut.http.annotation.Put
import io.micronaut.http.annotation.UriMapping
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HEAD
import jakarta.ws.rs.OPTIONS
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import spock.lang.Unroll

class HttpMethodAnnotationSpec extends AbstractTypeElementSpec {

    @Unroll
    void "test mapped annotation for client #source"() {
        given:
        def definition = buildBeanDefinition('test.Test$Intercepted', """
package test;

@io.micronaut.http.client.annotation.Client("/test")
interface Test {

    @${source.name}
    @jakarta.ws.rs.Path("$path")
    @jakarta.ws.rs.Produces("text/plain")
    String test();
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

    @Unroll
    void "test micronaut mapped annotation for #source"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@io.micronaut.http.annotation.Controller("/test")
class Test {

    @${source.name}("$path")
    @io.micronaut.http.annotation.Produces("text/plain")
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
        Get     | Get     | "/foo"
        Post    | Post    | "/foo"
        Put     | Put     | "/foo"
        Delete  | Delete  | "/foo"
        Head    | Head    | "/foo"
        Options | Options | "/foo"
    }

    @Unroll
    void "test mapped annotation for #source"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @${source.name}
    @jakarta.ws.rs.Path("$path")
    @jakarta.ws.rs.Produces("text/plain")
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

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Produces("text/plain")
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

    void "test mapping no path specified with @Controller"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@io.micronaut.http.annotation.Controller("/test")
class Test {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Produces("text/plain")
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

@jakarta.ws.rs.Path("/test")
@io.micronaut.validation.Validated
class Test {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Produces("text/plain")
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

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.GET
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

@jakarta.ws.rs.Path("/test")
class Test {

    @${method.toUpperCase()}
    @jakarta.ws.rs.Path("$path")
    @jakarta.ws.rs.Produces("text/plain")
    String test() {
        return "ok";
    }
}

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@jakarta.ws.rs.HttpMethod("$method")
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
    @jakarta.ws.rs.Produces("text/plain")
    @jakarta.ws.rs.Path("/foo")
    public String test();
}

class Test implements TestService {
    @Override
    public String test() {
        return "ok";
    }

    @jakarta.ws.rs.GET
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

    void "test bean definition is built when only @jakarta.ws.rs.Path is present on implementation class"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

interface TestService {
    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Produces("text/plain")
    @jakarta.ws.rs.Path("/foo")
    public String test();

    @jakarta.ws.rs.GET
    public String test2();
}

@jakarta.ws.rs.Path("/rest")
class Test implements TestService {
    @Override
    public String test() {
        return "ok";
    }

    @Override
    public String test2() {
        return "ok";
    }
}
""")
        def firstMethod = definition.getRequiredMethod("test")
        def secondMethod = definition.getRequiredMethod("test2")

        expect:
        definition.stringValue(Controller.class)
                .get() == "/rest"
        firstMethod.hasAnnotation(Get)
        firstMethod.stringValue(HttpMethodMapping)
                .get() == '/foo'
        firstMethod.stringValue(Produces)
                .get() == 'text/plain'
        secondMethod.hasAnnotation(Get)
        secondMethod.stringValue(HttpMethodMapping)
                .get() == '/'
    }

    void "test mapping path specified with @Path"() {
        given:
            def definition = buildBeanDefinition('test.Test', '''
package test;

@jakarta.ws.rs.Path("/base-path")
public class Test {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("/method-path")
    public String get() {
        return "ok";
    }
}
''')
        expect:
            definition.stringValue(Controller).get() == '/base-path'
            definition.stringValue(UriMapping).get() == '/base-path'
        and:
            def method = definition.getRequiredMethod('get')
            method.stringValue(HttpMethodMapping).get() == '/method-path'
    }

    void "test mapped annotation from interface for client"() {
        given:
            def definition = buildBeanDefinition('test.Test$Intercepted', """
package test;

interface TestService {

    @${source.name}
    @jakarta.ws.rs.Path("/foo")
    @jakarta.ws.rs.Produces("text/plain")
    String test();

    @${source.name}
    String test2();
}

@io.micronaut.http.client.annotation.Client("/test")
interface Test extends TestService {
}
""")

        def firstMethod = definition.getRequiredMethod("test")
        def secondMethod = definition.getRequiredMethod("test2")

        expect:
        firstMethod.hasAnnotation(target)
        firstMethod.stringValue(HttpMethodMapping)
                .get() == '/foo'
        firstMethod.stringValue(Produces)
                .get() == 'text/plain'
        secondMethod.hasAnnotation(target)
        secondMethod.stringValue(HttpMethodMapping)
                .get() == "/"

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
