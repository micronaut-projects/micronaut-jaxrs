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

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.activation.DataSource;
import jakarta.inject.Inject;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ProcessingException;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.JAXBElement;
import javax.xml.transform.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@MicronautTest
@Property(name = "spec.name", value = "JaxRsXmlServerProviderTest")
class JaxRsXmlServerProviderTest {
    private static final MediaType APPLICATION_ATOM_XML_TYPE = MediaType.of("application/atom+xml");

    @Inject
    @Client("/api/standard-provider")
    HttpClient client;

    @Test
    void zeroLengthDataSourceEntityIsNotNull() {
        assertEquals("EXPECTED", client.toBlocking().retrieve(emptyPost("/datasource")));
    }

    @Test
    void sourceProviderSupportsXmlSuffixMediaTypes() {
        HttpRequest<String> request = HttpRequest.POST("/source", "<tag>EXPECTED</tag>")
            .contentType(APPLICATION_ATOM_XML_TYPE)
            .accept(APPLICATION_ATOM_XML_TYPE);

        String body = client.toBlocking().retrieve(request);

        assertEquals("<tag>EXPECTED</tag>", body);
    }

    @Test
    void jaxbElementProviderSupportsXmlMediaTypes() {
        assertJaxbElementProvider(MediaType.TEXT_XML_TYPE);
        assertJaxbElementProvider(APPLICATION_ATOM_XML_TYPE);
    }

    @Test
    void sourceProviderRejectsDoctypeBeforeReturningReusableSource() {
        HttpRequest<String> request = HttpRequest.POST("/source", unsafeXml())
            .contentType(MediaType.APPLICATION_XML_TYPE);

        assertThrows(Exception.class, () -> client.toBlocking().exchange(request, String.class));
    }

    @Test
    void jaxbElementProviderRejectsDoctype() {
        HttpRequest<String> request = HttpRequest.POST("/jaxb", unsafeXml())
            .contentType(MediaType.APPLICATION_XML_TYPE);

        assertThrows(Exception.class, () -> client.toBlocking().exchange(request, String.class));
    }

    @Test
    void jaxbObjectProviderRejectsDoctype() {
        HttpRequest<String> request = HttpRequest.POST("/jaxb-object", unsafeXml())
            .contentType(MediaType.APPLICATION_XML_TYPE);

        assertThrows(Exception.class, () -> client.toBlocking().exchange(request, String.class));
    }

    @Test
    void sseXmlDataReaderRejectsDoctypeForSourceData() {
        JaxRsXmlSseEventDataReader reader = new JaxRsXmlSseEventDataReader();

        assertThrows(ProcessingException.class, () -> reader.readData(Source.class, Source.class, jakarta.ws.rs.core.MediaType.APPLICATION_XML_TYPE, unsafeXml()));
    }

    private static HttpRequest<byte[]> emptyPost(String path) {
        return HttpRequest.POST(path, new byte[0])
            .contentType(MediaType.APPLICATION_OCTET_STREAM_TYPE);
    }

    private void assertJaxbElementProvider(MediaType mediaType) {
        HttpRequest<String> request = HttpRequest.POST("/jaxb", "<tag>EXPECTED</tag>")
            .contentType(mediaType)
            .accept(mediaType);

        String body = client.toBlocking().retrieve(request);

        assertEquals("<tag>EXPECTED</tag>", body);
    }

    private static String unsafeXml() {
        return "<!DOCTYPE tag [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><tag>&xxe;</tag>";
    }

    @Requires(property = "spec.name", value = "JaxRsXmlServerProviderTest")
    @ApplicationPath("/api")
    static class RestApplication extends Application {
    }

    @Requires(property = "spec.name", value = "JaxRsXmlServerProviderTest")
    @Path("/standard-provider")
    static class StandardProviderResource {
        @Context
        HttpHeaders headers;

        @POST
        @Path("/datasource")
        @Produces(MediaType.TEXT_PLAIN)
        String datasource(DataSource dataSource) {
            return isNull(dataSource);
        }

        @POST
        @Path("/source")
        @Produces("application/atom+xml")
        Source source(Source source) {
            return source;
        }

        @POST
        @Path("/jaxb")
        Response jaxb(JAXBElement<String> jaxb) {
            return Response.ok(jaxb).type(headers.getMediaType()).build();
        }

        @POST
        @Path("/jaxb-object")
        @Produces(MediaType.TEXT_PLAIN)
        String jaxbObject(XmlMessage message) {
            return message.value;
        }

        private static String isNull(Object value) {
            return value == null ? "NULL" : "EXPECTED";
        }
    }

    @XmlRootElement(name = "tag")
    public static class XmlMessage {
        public String value;
    }
}
