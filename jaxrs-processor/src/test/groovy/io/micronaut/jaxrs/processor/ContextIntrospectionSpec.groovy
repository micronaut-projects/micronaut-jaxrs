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
}
