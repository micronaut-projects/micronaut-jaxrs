/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.jaxrs.xml;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JaxRsXmlFactoriesTest {

    @Test
    void xmlInputFactoryDisablesDtdAndExternalEntities() {
        XMLInputFactory factory = JaxRsXmlFactories.xmlInputFactory();

        assertFalse((Boolean) factory.getProperty(XMLInputFactory.SUPPORT_DTD));
        assertFalse((Boolean) factory.getProperty("javax.xml.stream.isSupportingExternalEntities"));
        assertEquals("", factory.getProperty(XMLConstants.ACCESS_EXTERNAL_DTD));
        assertEquals("", factory.getProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA));
    }

    @Test
    void validatedXmlBytesRejectsEntities() {
        byte[] xml = "<!DOCTYPE test [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>".getBytes(StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> JaxRsXmlFactories.validatedXmlBytes(new ByteArrayInputStream(xml)));
    }
}
