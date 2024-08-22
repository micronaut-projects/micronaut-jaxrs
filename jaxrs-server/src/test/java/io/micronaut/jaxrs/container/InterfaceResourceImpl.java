package io.micronaut.jaxrs.container;

import io.micronaut.http.HttpResponse;
import jakarta.ws.rs.Path;

@Path("/interface/test")
public class InterfaceResourceImpl implements InterfaceResource {

    @Override
    public HttpResponse<String> ping(String value) {
        return HttpResponse.ok(value);
    }

    @Override
    public HttpResponse<String> noPathPing() {
        return HttpResponse.ok("noPath");
    }

}
