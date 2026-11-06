package io.micronaut.jaxrs.providers;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.transform.Source;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The XML and JSON standard providers process untrusted entities safely and honour the media type.
 */
class StandardProvidersHardeningTest {

    private static final Annotation[] NO_ANNOTATIONS = new Annotation[0];

    @Test
    void saxSourceDoesNotExpandExternalEntities(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "SECRET");
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY e SYSTEM \"" + secret.toUri() + "\">]><r>&e;</r>";
        JaxRsSourceMessageBodyReaderWriter provider = new JaxRsSourceMessageBodyReaderWriter();
        @SuppressWarnings({"unchecked", "rawtypes"})
        Source source = provider.readFrom((Class) SAXSource.class, SAXSource.class, NO_ANNOTATIONS,
            MediaType.APPLICATION_XML_TYPE, new MultivaluedHashMap<>(), new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        StringWriter out = new StringWriter();
        try {
            TransformerFactory.newInstance().newTransformer().transform(source, new StreamResult(out));
        } catch (Exception e) {
            // rejecting the DTD is fine
        }
        assertFalse(out.toString().contains("SECRET"), out.toString());
    }

    @Test
    void sourceIsWrittenInTheDeclaredCharset() throws Exception {
        JaxRsSourceMessageBodyReaderWriter provider = new JaxRsSourceMessageBodyReaderWriter();
        MediaType latin1 = new MediaType("application", "xml", Map.of(MediaType.CHARSET_PARAMETER, "ISO-8859-1"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        provider.writeTo(new StreamSource(new StringReader("<r>é</r>")), StreamSource.class, StreamSource.class,
            NO_ANNOTATIONS, latin1, new MultivaluedHashMap<>(), out);
        String written = out.toString(StandardCharsets.ISO_8859_1);
        assertTrue(written.contains("<r>é</r>"), written);
        assertTrue(written.contains("ISO-8859-1"), written);
    }

    @Test
    void mediaTypesAreCaseInsensitive() {
        JaxRsSourceMessageBodyReaderWriter source = new JaxRsSourceMessageBodyReaderWriter();
        assertTrue(source.isReadable(DOMSource.class, DOMSource.class, NO_ANNOTATIONS, new MediaType("APPLICATION", "XML")));
        assertTrue(source.isReadable(DOMSource.class, DOMSource.class, NO_ANNOTATIONS, new MediaType("Application", "Atom+XML")));
        assertTrue(source.isReadable(DOMSource.class, DOMSource.class, NO_ANNOTATIONS, new MediaType("TEXT", "Xml")));
        JaxRsJsonbMessageBodyReaderWriter jsonb = new JaxRsJsonbMessageBodyReaderWriter();
        assertTrue(jsonb.isReadable(Object.class, Object.class, NO_ANNOTATIONS, new MediaType("APPLICATION", "JSON")));
        assertTrue(jsonb.isReadable(Object.class, Object.class, NO_ANNOTATIONS, new MediaType("application", "Problem+JSON")));
    }
}
