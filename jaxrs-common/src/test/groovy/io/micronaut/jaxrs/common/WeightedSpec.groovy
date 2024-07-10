package io.micronaut.jaxrs.common

import spock.lang.Specification

class WeightedSpec extends Specification {

    void "test weighted"() {
        given:
        List<Weighted<String>> weightedList = []
        weightedList << new Weighted<>("one", 0.7)
        weightedList << new Weighted<>("two")
        weightedList << new Weighted<>("three", [q:0.5])
        weightedList << new Weighted<>("four", [q:0.1])
        weightedList << new Weighted<>("five", 0.9)

        def sorted = weightedList.sort().collect({ it.object })

        expect:
        sorted == ['two', 'five','one', 'three', 'four']
    }
}
