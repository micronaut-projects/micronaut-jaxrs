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
package io.micronaut.jaxrs.providers;

import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.reflect.ClassUtils;
import jakarta.json.spi.JsonProvider;

/**
 * Whether a JSON-P implementation is present, e.g. Eclipse Parsson, not only its API.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
public final class JsonpImplementationCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context) {
        return isPresent();
    }

    /**
     * @return Whether the JSON-P API and an implementation are present
     */
    static boolean isPresent() {
        if (!ClassUtils.isPresent("jakarta.json.spi.JsonProvider", JsonpImplementationCondition.class.getClassLoader())) {
            return false;
        }
        try {
            JsonProvider.provider();
            return true;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
