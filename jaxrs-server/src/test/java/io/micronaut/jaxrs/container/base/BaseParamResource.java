package io.micronaut.jaxrs.container.base;

import io.micronaut.context.annotation.Requires;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;

/**
 * A resource with a request field, extended in another package.
 */
@Requires(property = "spec.name", value = "SubResourceTest")
@Path("/base-param")
public class BaseParamResource {

    @QueryParam("field")
    @DefaultValue("none")
    String field;

    @GET
    @Produces("text/plain")
    public String get(@QueryParam("q") String q) {
        return "base field=" + field + " q=" + q;
    }
}
