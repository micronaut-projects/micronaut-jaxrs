package io.micronaut.jaxrs.providers;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.NoContentException;
import jakarta.ws.rs.ext.Providers;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JSON-P values are read and written by the JSON-P provider when the API and an implementation
 * are present (JAX-RS 11.2.6), before JSON-B and the JSON codecs of Micronaut.
 */
@MicronautTest
@Property(name = "spec.name", value = "JsonpProviderTest")
class JsonpProviderTest {

    @Inject
    EmbeddedServer server;

    @Inject
    Providers providers;

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.getURL() + path))
            .header("Content-Type", MediaType.APPLICATION_JSON)
            .header("Accept", MediaType.APPLICATION_JSON)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void jsonpComesBeforeJsonb() {
        // JSON-B also reads JSON-P values when it is present: the JSON-P provider, which has a higher
        // priority, is the one that answers an empty entity with NoContentException
        Annotation[] none = new Annotation[0];
        for (Class type : new Class[] {JsonValue.class, JsonStructure.class, JsonObject.class, JsonArray.class, JsonString.class, JsonNumber.class}) {
            var reader = providers.getMessageBodyReader(type, type, none, MediaType.APPLICATION_JSON_TYPE);
            assertThrows(NoContentException.class, () -> reader.readFrom(type, type, none, MediaType.APPLICATION_JSON_TYPE,
                new MultivaluedHashMap<>(), new ByteArrayInputStream(new byte[0])), type.getName());
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void readsAndWritesWithTheCharsetOfTheMediaType() throws Exception {
        JaxRsJsonpMessageBodyReaderWriter provider = new JaxRsJsonpMessageBodyReaderWriter();
        MediaType latin1 = MediaType.valueOf("application/json;charset=ISO-8859-1");
        Class<JsonValue> type = (Class) JsonObject.class;
        JsonValue read = provider.readFrom(type, JsonObject.class, new Annotation[0], latin1, new MultivaluedHashMap<>(),
            new ByteArrayInputStream("{\"v\":\"\u00e9\"}".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals("\u00e9", read.asJsonObject().getString("v"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        provider.writeTo(read, JsonObject.class, JsonObject.class, new Annotation[0], latin1, new MultivaluedHashMap<>(), out);
        assertEquals("{\"v\":\"\u00e9\"}", out.toString(StandardCharsets.ISO_8859_1));
        assertThrows(NoContentException.class, () -> provider.readFrom(type, JsonObject.class, new Annotation[0],
            MediaType.APPLICATION_JSON_TYPE, new MultivaluedHashMap<>(), new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void serverReadsAndWritesAJsonObject() throws Exception {
        HttpResponse<String> response = post("/jsonp/object", "{\"name\":\"Joe\",\"tags\":[1,2]}");
        assertEquals(200, response.statusCode(), response.body());
        // JSON-B or Jackson would write the JsonObject as a map of its values, e.g. {"name":{"chars":"Joe",...}}
        assertEquals("{\"name\":\"Joe\",\"tags\":[1,2],\"echoed\":true}", response.body());
    }

    @Test
    void serverReadsAndWritesTheOtherJsonTypes() throws Exception {
        assertEquals("[3,\"a\"]", post("/jsonp/array", "[\"a\",3]").body());
        assertEquals("{\"type\":\"OBJECT\"}", post("/jsonp/structure", "{}").body());
        assertEquals("{\"type\":\"ARRAY\"}", post("/jsonp/structure", "[]").body());
        assertEquals("\"STRING\"", post("/jsonp/value", "\"x\"").body());
        assertEquals("\"NUMBER\"", post("/jsonp/value", "42").body());
        assertEquals("43", post("/jsonp/number", "42").body());
        assertEquals("\"x!\"", post("/jsonp/string", "\"x\"").body());
    }

    @Test
    void serverRejectsInvalidJsonAndTheWrongJsonType() throws Exception {
        assertEquals(400, post("/jsonp/object", "{\"name\":").statusCode());
        assertEquals(400, post("/jsonp/object", "[1]").statusCode());
        assertEquals(400, post("/jsonp/object", "").statusCode());
    }

    @Test
    void serverWritesAJsonValue() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.getURL() + "/jsonp/get"))
            .header("Accept", MediaType.APPLICATION_JSON)
            .GET()
            .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("{\"a\":null}", response.body());
        }
    }

    @Test
    void clientReadsAndWritesJsonValues() {
        try (Client client = ClientBuilder.newClient()) {
            JsonObject sent = Json.createObjectBuilder().add("name", "Ann").build();
            JsonObject echoed = client.target(server.getURI()).path("jsonp/object")
                .request(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.json(sent), JsonObject.class);
            assertEquals("Ann", echoed.getString("name"));
            assertEquals(JsonValue.TRUE, echoed.get("echoed"));

            JsonStructure structure = client.target(server.getURI()).path("jsonp/array")
                .request(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.json(Json.createArrayBuilder().add(1).add(2).build()), JsonStructure.class);
            assertInstanceOf(JsonArray.class, structure);
            assertEquals("[2,1]", structure.toString());

            JsonValue value = client.target(server.getURI()).path("jsonp/get")
                .request(MediaType.APPLICATION_JSON_TYPE)
                .get(JsonValue.class);
            assertEquals(JsonValue.NULL, value.asJsonObject().get("a"));
        }
    }

    @Requires(property = "spec.name", value = "JsonpProviderTest")
    @Path("/jsonp")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public static class JsonpResource {

        @POST
        @Path("object")
        public JsonObject object(JsonObject object) {
            return Json.createObjectBuilder(object).add("echoed", true).build();
        }

        @POST
        @Path("array")
        public JsonArray array(JsonArray array) {
            var reversed = Json.createArrayBuilder();
            for (int i = array.size() - 1; i >= 0; i--) {
                reversed.add(array.get(i));
            }
            return reversed.build();
        }

        @POST
        @Path("structure")
        public JsonObject structure(JsonStructure structure) {
            return Json.createObjectBuilder().add("type", structure.getValueType().name()).build();
        }

        @POST
        @Path("value")
        public JsonValue value(JsonValue value) {
            return Json.createValue(value.getValueType().name());
        }

        @POST
        @Path("number")
        public JsonNumber number(JsonNumber number) {
            return Json.createValue(number.intValue() + 1);
        }

        @POST
        @Path("string")
        public JsonString string(JsonString string) {
            return Json.createValue(string.getString() + "!");
        }

        @GET
        @Path("get")
        public JsonValue get() {
            return Json.createObjectBuilder().addNull("a").build();
        }
    }
}
