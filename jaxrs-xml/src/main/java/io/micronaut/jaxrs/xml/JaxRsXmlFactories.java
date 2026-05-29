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

import io.micronaut.core.annotation.Internal;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;

/**
 * Hardened XML factory creation for optional XML providers.
 */
@Internal
final class JaxRsXmlFactories {

    private JaxRsXmlFactories() {
    }

    static XMLInputFactory xmlInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    static XMLStreamReader xmlStreamReader(InputStream inputStream) throws XMLStreamException {
        return xmlInputFactory().createXMLStreamReader(inputStream);
    }

    static XMLStreamReader xmlStreamReader(byte[] bytes) throws XMLStreamException {
        return xmlStreamReader(new ByteArrayInputStream(bytes));
    }

    static Unmarshaller unmarshaller(JAXBContext context) throws JAXBException {
        Unmarshaller unmarshaller = context.createUnmarshaller();
        setProperty(unmarshaller, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        setProperty(unmarshaller, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return unmarshaller;
    }

    static Marshaller marshaller(JAXBContext context) throws JAXBException {
        Marshaller marshaller = context.createMarshaller();
        setProperty(marshaller, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        setProperty(marshaller, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return marshaller;
    }

    static byte[] validatedXmlBytes(InputStream inputStream) throws IOException {
        byte[] bytes = inputStream.readAllBytes();
        String prefix = new String(bytes, 0, Math.min(bytes.length, 512), StandardCharsets.ISO_8859_1).toUpperCase(Locale.ROOT);
        if (prefix.contains("<!DOCTYPE") || prefix.contains("<!ENTITY")) {
            throw new IOException("Unsafe XML entity");
        }
        return bytes;
    }

    static TransformerFactory transformerFactory() throws TransformerConfigurationException {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        return factory;
    }

    private static void setProperty(Unmarshaller unmarshaller, String property, String value) {
        try {
            unmarshaller.setProperty(property, value);
        } catch (JAXBException ignored) {
            // Some JAXB providers do not support every hardening property.
        }
    }

    private static void setProperty(Marshaller marshaller, String property, String value) {
        try {
            marshaller.setProperty(property, value);
        } catch (JAXBException ignored) {
            // Some JAXB providers do not support every hardening property.
        }
    }
}
