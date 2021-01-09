package io.micronaut.jaxrs.runtime;

import javax.ws.rs.Path;

import io.micronaut.http.HttpResponse;

@Path("/interface/test")
public class InterfaceResourceImpl implements InterfaceResource {

    @Override
    public HttpResponse<String> ping(String value) {
        return HttpResponse.ok(value);
    }

}
