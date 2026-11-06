package io.micronaut.jaxrs.container;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An application path is decoded as a path: a literal plus sign is not a space.
 */
class ApplicationPathDecodingTest {

    @Test
    void literalPlusSignIsKept() {
        assertEquals("/a+b", ApplicationProvider.decodePath("/a+b"));
    }

    @Test
    void percentEncodedCharactersAreDecoded() {
        assertEquals("/a b+c", ApplicationProvider.decodePath("/a%20b%2Bc"));
    }
}
