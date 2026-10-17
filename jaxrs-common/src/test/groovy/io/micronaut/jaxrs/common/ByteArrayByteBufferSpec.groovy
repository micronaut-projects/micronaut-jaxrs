package io.micronaut.jaxrs.common

import spock.lang.Specification

class ByteArrayByteBufferSpec extends Specification {

    void "toByteArray returns the readable bytes"() {
        given:
        def buffer = new ByteArrayByteBuffer<Object>([1, 2, 3, 4] as byte[])

        when:
        buffer.readerIndex(2)

        then:
        buffer.toByteArray() == [3, 4] as byte[]
    }

    void "the reader index can be at the end"() {
        given:
        def buffer = new ByteArrayByteBuffer<Object>([1, 2] as byte[])

        when:
        buffer.readerIndex(2)

        then:
        buffer.readableBytes() == 0
        buffer.toByteArray().length == 0
    }
}
