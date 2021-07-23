package io.micronaut.jaxrs.processor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec

class ParameterValidationSpec extends AbstractTypeElementSpec {

    void "test javax.ws.rs.PathParam path compile-time validation fails"() {
        when:
        buildBeanDefinition('test.Test', """
package test;

@javax.ws.rs.Path("/test")
class Test {

    @javax.ws.rs.Path("/{user_id}")
    @javax.ws.rs.GET
    void test(@javax.ws.rs.PathParam("u") String userId) {}
}
""")

        then:
        def ex = thrown(RuntimeException)
        ex.message.contains("The route declares a uri variable named [user_id], but no corresponding method argument is present")

        when:
        buildBeanDefinition('test.Test', """
package test;

@io.micronaut.http.annotation.Controller("/test")
class Test {

    @javax.ws.rs.Path("/{user_id}")
    @javax.ws.rs.GET
    void test(@javax.ws.rs.PathParam("u") String userId) {}
}
""")

        then:
        ex = thrown(RuntimeException)
        ex.message.contains("The route declares a uri variable named [user_id], but no corresponding method argument is present")
    }

    void "test javax.ws.rs.PathParam path compile-time validation passes"() {
        when:
        buildBeanDefinition('test.Test', """
package test;

@javax.ws.rs.Path("/test")
class Test {

    @javax.ws.rs.Path("/{user_id}")
    @javax.ws.rs.GET
    void test(@javax.ws.rs.PathParam("user_id") String userId) {}
}
""")

        then:
        noExceptionThrown()

        when:
        buildBeanDefinition('test.Test', """
package test;

@io.micronaut.http.annotation.Controller("/test")
class Test {

    @javax.ws.rs.Path("/{user_id}")
    @javax.ws.rs.GET
    void test(@javax.ws.rs.PathParam("user_id") String userId) {}
}
""")

        then:
        noExceptionThrown()
    }


}
