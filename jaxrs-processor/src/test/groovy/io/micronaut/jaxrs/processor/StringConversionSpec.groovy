package io.micronaut.jaxrs.processor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.jaxrs.common.reflect.StringConversion

import java.lang.reflect.InvocationTargetException

/**
 * The String conversions generated for the types of the JAX-RS parameters (JAX-RS 3.2).
 */
class StringConversionSpec extends AbstractTypeElementSpec {

    private static final String SERVICES = "/CLASS_OUTPUT/META-INF/micronaut/" + StringConversion.name + "/"

    void "a factory with a String array parameter is not a String factory"() {
        when:
        def conversions = conversions('''
package test;

@jakarta.ws.rs.Path("/test")
public class Test {

    @jakarta.ws.rs.GET
    public String get(@jakarta.ws.rs.QueryParam("v") Value v) {
        return null;
    }

    public static class Value {
        final String value;

        public Value(String value) {
            this.value = "constructor:" + value;
        }

        public static Value valueOf(String[] values) {
            return null;
        }
    }
}
''')

        then:
        conversions['test.Test$Value'].convert('a').value == 'constructor:a'
    }

    void "the static methods of an implemented interface are not factories"() {
        when:
        def conversions = conversions('''
package test;

@jakarta.ws.rs.Path("/test")
public class Test {

    @jakarta.ws.rs.GET
    public String get(@jakarta.ws.rs.QueryParam("v") Value v) {
        return null;
    }

    public interface Maker {
        static Value valueOf(String value) {
            return null;
        }
    }

    public static class Value implements Maker {
        final String value;

        public Value(String value) {
            this.value = "constructor:" + value;
        }
    }
}
''')

        then:
        conversions['test.Test$Value'].convert('a').value == 'constructor:a'
    }

    void "the static factory of an interface is its own"() {
        when:
        def conversions = conversions('''
package test;

@jakarta.ws.rs.Path("/test")
public class Test {

    @jakarta.ws.rs.GET
    public String get(@jakarta.ws.rs.QueryParam("v") Value v) {
        return null;
    }

    public interface Value {
        static Value valueOf(String value) {
            return () -> "valueOf:" + value;
        }

        String value();
    }
}
''')

        then:
        conversions['test.Test$Value'].convert('a').value() == 'valueOf:a'
    }

    void "a factory declaring Throwable is adapted"() {
        when:
        def conversions = conversions('''
package test;

@jakarta.ws.rs.Path("/test")
public class Test {

    @jakarta.ws.rs.GET
    public String get(@jakarta.ws.rs.QueryParam("v") Value v) {
        return null;
    }

    public static class Value {
        public static Value valueOf(String value) throws Throwable {
            if (value.equals("throwable")) {
                throw new Throwable(value);
            }
            if (value.equals("runtime")) {
                throw new IllegalStateException(value);
            }
            if (value.equals("error")) {
                throw new AssertionError(value);
            }
            return new Value();
        }
    }
}
''')
        def conversion = conversions['test.Test$Value']

        then:
        conversion.convert('a') != null

        when:
        conversion.convert('throwable')

        then:
        def e = thrown(InvocationTargetException)
        e.cause.message == 'throwable'

        when:
        conversion.convert('runtime')

        then:
        thrown(IllegalStateException)

        when:
        conversion.convert('error')

        then:
        thrown(AssertionError)
    }

    /**
     * The generated conversions, by the name of their type.
     */
    private Map<String, StringConversion> conversions(String source) {
        Map<String, StringConversion> conversions = [:]
        conversionsOf(source).each { conversions[it.type().name] = it }
        return conversions
    }

    private List<StringConversion> conversionsOf(String source) {
        def files = newJavaParser().generate('test.Test', source)
        def constructor = Class.forName('io.micronaut.annotation.processing.test.JavaFileObjectClassLoader').getDeclaredConstructor(Iterable)
        constructor.accessible = true
        ClassLoader classLoader = constructor.newInstance(files) as ClassLoader
        return files.findAll { it.name.startsWith(SERVICES) }
            .collect { classLoader.loadClass(it.name.substring(SERVICES.length())).getDeclaredConstructor().newInstance() as StringConversion }
    }
}
