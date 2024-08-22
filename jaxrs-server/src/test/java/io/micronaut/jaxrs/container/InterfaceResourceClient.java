package io.micronaut.jaxrs.container;

import io.micronaut.http.client.annotation.Client;

@Client("/api/interface/test")
public interface InterfaceResourceClient extends InterfaceResource {

}
