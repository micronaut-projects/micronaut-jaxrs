package io.micronaut.jaxrs.runtime;

import io.micronaut.http.client.annotation.Client;

@Client("/api/interface/test")
public interface InterfaceResourceClient extends InterfaceResource {

}
