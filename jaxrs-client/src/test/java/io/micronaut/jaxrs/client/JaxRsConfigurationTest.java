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
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.jaxrs.common.body.standard.JaxRsStreamingOutputMessageBodyWriter;
import jakarta.ws.rs.core.StreamingOutput;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JaxRsConfigurationTest {

    @Test
    void formUrlEncodedWriterOutputIsSentAsRawText() {
        JaxRsConfiguration configuration = new JaxRsConfiguration();
        configuration.register(new JaxRsStreamingOutputMessageBodyWriter<>());
        MutableHttpRequest<StreamingOutput> request = HttpRequest.POST("http://localhost/form", output("entity"))
            .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE);

        configuration.writeBody(request, Argument.of(StreamingOutput.class), request.getBody().orElseThrow());

        assertEquals("entity", request.getBody(String.class).orElseThrow());
    }

    private static StreamingOutput output(String value) {
        return outputStream -> outputStream.write(value.getBytes(StandardCharsets.UTF_8));
    }
}
