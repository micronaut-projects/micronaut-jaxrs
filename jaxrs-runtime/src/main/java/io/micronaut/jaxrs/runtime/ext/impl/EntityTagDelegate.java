package io.micronaut.jaxrs.runtime.ext.impl;


import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.ArgumentUtils;

import javax.ws.rs.core.EntityTag;
import javax.ws.rs.ext.RuntimeDelegate;


/**
 * Forked from RESTEasy.
 *
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
@Internal
final class EntityTagDelegate implements RuntimeDelegate.HeaderDelegate<EntityTag> {

    @Override
    public EntityTag fromString(String value) throws IllegalArgumentException {
        ArgumentUtils.requireNonNull("value", value);
        boolean weakTag = false;
        if (value.startsWith("W/")) {
            weakTag = true;
            value = value.substring(2);
        }
        if (value.startsWith("\"")) {
            value = value.substring(1);
        }
        if (value.endsWith("\"")) {
            value = value.substring(0, value.length() - 1);
        }
        return new EntityTag(value, weakTag);
    }

    @Override
    public String toString(EntityTag value) {
        String weak = value.isWeak() ? "W/" : "";
        return weak + '"' + value.getValue() + '"';
    }

}
