package io.micronaut.jaxrs.common.reflect

import io.micronaut.core.type.Argument
import jakarta.ws.rs.client.InvocationCallback
import spock.lang.Specification

class GenericSignaturesSpec extends Specification {

    void "a type variable within a type argument is bound to the type argument of the subclass"() {
        when:
        Argument<?> argument = GenericSignatures.resolve(Concrete, InvocationCallback)

        then:
        argument.type == InvocationCallback
        argument.typeParameters.length == 1
        argument.typeParameters[0].type == List
        argument.typeParameters[0].typeParameters*.type == [String]
    }

    static abstract class Base<T> implements InvocationCallback<List<T>> {
        @Override
        void completed(List<T> response) {
        }

        @Override
        void failed(Throwable throwable) {
        }
    }

    static class Concrete extends Base<String> {
    }
}
