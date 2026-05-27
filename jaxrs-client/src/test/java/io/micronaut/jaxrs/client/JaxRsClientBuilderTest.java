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

import io.micronaut.http.client.netty.DefaultHttpClient;
import jakarta.ws.rs.client.Client;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class JaxRsClientBuilderTest {

    @Test
    void jaxRsClientPreservesEncodedResponseHeaders() {
        Client client = new JaxRsClientBuilder().build();
        try {
            JaxRsClient jaxRsClient = (JaxRsClient) client;
            DefaultHttpClient httpClient = (DefaultHttpClient) jaxRsClient.getHttpClient();

            assertFalse(httpClient.getConfiguration().isDecompressionEnabled());
        } finally {
            client.close();
        }
    }
}
