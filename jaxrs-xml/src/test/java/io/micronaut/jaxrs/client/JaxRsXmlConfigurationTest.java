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
package io.micronaut.jaxrs.client;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.jaxrs.xml.JaxRsJaxbElementMessageBodyReaderWriter;
import io.micronaut.jaxrs.xml.JaxRsSourceMessageBodyReaderWriter;
import jakarta.ws.rs.client.Client;
import jakarta.xml.bind.JAXBElement;
import org.junit.jupiter.api.Test;

import javax.xml.namespace.QName;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JaxRsXmlConfigurationTest {

    @Test
    void jaxbElementWriterOutputIsSentAsXmlBytes() {
        JaxRsConfiguration configuration = new JaxRsConfiguration();
        configuration.register(new JaxRsJaxbElementMessageBodyReaderWriter(null));
        JAXBElement<String> element = new JAXBElement<>(new QName("jaxb"), String.class, "jaxb");
        MutableHttpRequest<JAXBElement<String>> request = HttpRequest.POST("http://localhost/jaxb", element)
            .contentType(MediaType.APPLICATION_XML_TYPE);

        configuration.writeBody(request, Argument.of(JAXBElement.class), request.getBody().orElseThrow());

        assertEquals("<jaxb>jaxb</jaxb>", new String(request.getBody(byte[].class).orElseThrow(), StandardCharsets.UTF_8));
    }

    @Test
    void sourceWriterOutputIsSentAsXmlBytes() {
        JaxRsConfiguration configuration = new JaxRsConfiguration();
        configuration.register(new JaxRsSourceMessageBodyReaderWriter());
        StreamSource source = new StreamSource(new StringReader("<source>value</source>"));
        MutableHttpRequest<StreamSource> request = HttpRequest.POST("http://localhost/source", source)
            .contentType(MediaType.APPLICATION_XML_TYPE);

        configuration.writeBody(request, Argument.of(StreamSource.class), request.getBody().orElseThrow());

        assertEquals("<source>value</source>", new String(request.getBody(byte[].class).orElseThrow(), StandardCharsets.UTF_8));
    }

    @Test
    void sourceReaderReadsXmlBytes() throws TransformerException {
        JaxRsConfiguration configuration = new JaxRsConfiguration();
        configuration.register(new JaxRsSourceMessageBodyReaderWriter());
        HttpResponse<byte[]> response = HttpResponse.ok("<source>value</source>".getBytes(StandardCharsets.UTF_8))
            .contentType(MediaType.APPLICATION_XML_TYPE);

        Source source = configuration.createHttpMessageEntityReader().readEntity(response, Argument.of(Source.class));

        assertEquals("<source>value</source>", sourceToString(source));
    }

    @Test
    void clientBuilderLoadsXmlProvidersFromServiceLoader() {
        try (Client client = new JaxRsClientBuilder().build()) {
            JaxRsConfiguration configuration = ((JaxRsClient) client).getConfiguration();
            JAXBElement<String> element = new JAXBElement<>(new QName("jaxb"), String.class, "jaxb");
            MutableHttpRequest<JAXBElement<String>> request = HttpRequest.POST("http://localhost/jaxb", element)
                .contentType(MediaType.APPLICATION_XML_TYPE);

            configuration.writeBody(request, Argument.of(JAXBElement.class), request.getBody().orElseThrow());

            assertEquals("<jaxb>jaxb</jaxb>", new String(request.getBody(byte[].class).orElseThrow(), StandardCharsets.UTF_8));
        }
    }

    private static String sourceToString(Source source) throws TransformerException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.transform(source, new StreamResult(outputStream));
        return outputStream.toString(StandardCharsets.UTF_8).trim();
    }
}
