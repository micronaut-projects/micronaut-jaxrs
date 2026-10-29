package io.micronaut.jaxrs.common

import spock.lang.Specification

class TypeDistanceSpec extends Specification {

    void "the distance of a type follows the shortest path of its hierarchy"() {
        expect:
        JaxRsUtils.typeDistance(provided, ArrayList) == distance

        where:
        provided         | distance
        ArrayList        | 0
        List             | 1
        AbstractList     | 1
        // through SequencedCollection, or AbstractList and AbstractCollection
        Collection       | 3
        Iterable         | 4
        Object           | Integer.MAX_VALUE - 1
        String           | Integer.MAX_VALUE
    }
}
