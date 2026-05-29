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
package io.micronaut.jaxrs.common;

import io.micronaut.core.annotation.Internal;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Build-time selection metadata for Jakarta REST message body providers.
 */
@Internal
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface JaxRsMessageBodyProvider {

    String MEMBER_SERVER = "server";
    String MEMBER_CONSUMES = "consumes";
    String MEMBER_PRODUCES = "produces";
    String MEMBER_READER_TYPE = "readerType";
    String MEMBER_READER_TYPE_VARIABLE = "readerTypeVariable";
    String MEMBER_WRITER_TYPE = "writerType";
    String MEMBER_WRITER_TYPE_VARIABLE = "writerTypeVariable";

    /**
     * @return Whether this provider can run on the server.
     */
    boolean server() default true;

    /**
     * @return Precomputed reader media types.
     */
    String[] consumes() default {};

    /**
     * @return Precomputed writer media types.
     */
    String[] produces() default {};

    /**
     * @return Precomputed reader entity type.
     */
    Class<?> readerType() default void.class;

    /**
     * @return Whether the reader entity type is a type variable.
     */
    boolean readerTypeVariable() default false;

    /**
     * @return Precomputed writer entity type.
     */
    Class<?> writerType() default void.class;

    /**
     * @return Whether the writer entity type is a type variable.
     */
    boolean writerTypeVariable() default false;
}
