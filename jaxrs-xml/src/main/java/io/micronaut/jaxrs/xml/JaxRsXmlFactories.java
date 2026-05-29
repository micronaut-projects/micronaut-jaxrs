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

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.transform.TransformerException;
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
        disable(factory, XMLInputFactory.SUPPORT_DTD);
        disable(factory, "javax.xml.stream.isSupportingExternalEntities");
        return factory;
    }

    static TransformerFactory transformerFactory() {
        TransformerFactory factory = TransformerFactory.newInstance();
        setFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setAttribute(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        setAttribute(factory, XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        return factory;
    }

    private static void disable(XMLInputFactory factory, String propertyName) {
        try {
            factory.setProperty(propertyName, false);
        } catch (IllegalArgumentException ignored) {
            // Some XMLInputFactory implementations do not support every hardening property.
        }
    }

    private static void setFeature(TransformerFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (TransformerException ignored) {
            // Some TransformerFactory implementations do not support every hardening feature.
        }
    }

    private static void setAttribute(TransformerFactory factory, String attribute, String value) {
        try {
            factory.setAttribute(attribute, value);
        } catch (IllegalArgumentException ignored) {
            // Some TransformerFactory implementations do not support every hardening attribute.
        }
    }
}
