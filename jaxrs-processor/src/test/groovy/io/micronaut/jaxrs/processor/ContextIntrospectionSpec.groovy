package io.micronaut.jaxrs.processor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import jakarta.ws.rs.core.Context

class ContextIntrospectionSpec extends AbstractTypeElementSpec {

    void "the @Context field and setter of a provider are properties of its introspection"() {
        when:
        def introspection = buildBeanIntrospection('test.TestProvider', '''
package test;

import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.Providers;

@Provider
public class TestProvider implements ContextResolver<String> {
    @Context
    private Providers providers;

    @Context
    public void setOther(Providers other) {
    }

    @Override
    public String getContext(Class<?> type) {
        return null;
    }
}
''')

        then:
        introspection.getProperty("providers").get().hasAnnotation(Context)
        !introspection.getProperty("providers").get().isReadOnly()
        introspection.getProperty("other").get().hasAnnotation(Context)
        introspection.getProperty("other").get().isWriteOnly()
    }

    void "a provider already annotated with @Introspected keeps its members and includes its injection points"() {
        when:
        def introspection = buildBeanIntrospection('test.TestProvider', '''
package test;

import io.micronaut.core.annotation.Introspected;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.Providers;

@Provider
@Introspected(accessKind = Introspected.AccessKind.FIELD)
public class TestProvider implements ContextResolver<String> {
    @Context
    Providers providers;

    public String name;

    public void setOther(Providers other) {
    }

    @Override
    public String getContext(Class<?> type) {
        return null;
    }
}
''')

        then:
        introspection.getProperty("providers").get().hasAnnotation(Context)
        introspection.getProperty("name").isPresent()
        !introspection.getProperty("other").isPresent()
    }

    void "the @Context field of an application is a property of its introspection"() {
        when:
        def introspection = buildBeanIntrospection('test.TestApplication', '''
package test;

import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Providers;

public class TestApplication extends Application {
    @Context
    private Providers providers;
}
''')

        then:
        introspection.getProperty("providers").get().hasAnnotation(Context)
    }

    void "the bean of a provider is created with its constructor of the most @Context parameters"() {
        when:
        def definition = buildBeanDefinition('test.TestProvider', '''
package test;

import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.Providers;

@Provider
public class TestProvider implements ContextResolver<String> {
    public TestProvider() {
    }

    public TestProvider(@Context Providers providers) {
    }

    @Override
    public String getContext(Class<?> type) {
        return null;
    }
}
''')

        then:
        definition.constructor.arguments.length == 1
        definition.constructor.arguments[0].type.name == 'jakarta.ws.rs.ext.Providers'
    }

    void "a provider without @Provider is introspected with its @Context field"() {
        when:
        def introspection = buildBeanIntrospection('test.TestFilter', '''
package test;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Providers;

public class TestFilter implements ClientRequestFilter {
    @Context
    private Providers providers;

    @Override
    public void filter(ClientRequestContext requestContext) {
    }
}
''')

        then:
        introspection != null
        introspection.instantiate() != null
        introspection.getProperty("providers").get().hasAnnotation(Context)
    }

    void "a class that implements no provider contract is not introspected"() {
        when:
        buildBeanIntrospection('test.Plain', '''
package test;

public class Plain {
}
''')

        then: "no introspection is generated"
        thrown(ClassNotFoundException)
    }
}
