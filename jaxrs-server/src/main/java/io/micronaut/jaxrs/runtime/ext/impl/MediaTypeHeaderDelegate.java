/*
 * Copyright 2017-2020 original authors
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
package io.micronaut.jaxrs.runtime.ext.impl;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.ArgumentUtils;
import io.micronaut.core.util.clhm.ConcurrentLinkedHashMap;
import io.micronaut.jaxrs.runtime.core.ParameterParser;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.RuntimeDelegate;

import java.util.Arrays;
import java.util.Map;

/**
 * Forked from RESTEasy.
 *
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
@Internal
final class MediaTypeHeaderDelegate implements RuntimeDelegate.HeaderDelegate<Object> {

    private static final Map<String, MediaType> MAP = new ConcurrentLinkedHashMap.Builder<String, MediaType>()
            .maximumWeightedCapacity(200)
            .build();
    private static final Map<MediaType, String> REVERSE_MAP = new ConcurrentLinkedHashMap.Builder<MediaType, String>()
            .maximumWeightedCapacity(200)
            .build();
    private static final char[] QUOTED_CHARS = "()<>@,;:\\\"/[]?= \t\r\n".toCharArray();

    public static final String INVALID_MEDIA_TYPE = "Invalid media type: ";

    @Override
    public Object fromString(String type) throws IllegalArgumentException {
        if (type == null) {
            ArgumentUtils.requireNonNull("type", type);
        }
        return parse(type);
    }

    @Override
    public String toString(Object o) {
        ArgumentUtils.requireNonNull("o", o);

        MediaType type = (MediaType) o;
        String result = REVERSE_MAP.get(type);
        if (result == null) {
            result = internalToString(type);
            REVERSE_MAP.put(type, result);
            MAP.put(result, type);
        }
        return result;
    }

    private static MediaType parse(String type) {
        MediaType result = MAP.get(type);
        if (result == null) {
            result = internalParse(type);
            MAP.put(type, result);
            REVERSE_MAP.put(result, type);
        }
        return result;
    }

    /**
     * Checks whether a string is quoted.
     * @param str The str
     * @return True if it is
     */
    static boolean quoted(String str) {
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            for (char q : QUOTED_CHARS) {
                if (c == q) {
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressWarnings("java:S3776")
    private static MediaType internalParse(String type) {
        int typeIndex = type.indexOf('/');
        int paramIndex = type.indexOf(';');
        String major;
        String subtype;
        if (typeIndex < 0) { // possible "*"

            major = type;
            if (paramIndex > -1) {
                major = major.substring(0, paramIndex);
            }
            if (!MediaType.MEDIA_TYPE_WILDCARD.equals(major)) {
                throw new IllegalArgumentException(INVALID_MEDIA_TYPE + type);
            }
            subtype = MediaType.MEDIA_TYPE_WILDCARD;
        } else {
            major = type.substring(0, typeIndex);
            if (paramIndex > -1) {
                subtype = type.substring(typeIndex + 1, paramIndex);
            } else {
                subtype = type.substring(typeIndex + 1);
            }
        }
        if (major.length() < 1 || subtype.length() < 1) {
            throw new IllegalArgumentException(INVALID_MEDIA_TYPE + type);
        }
        if (!isValid(major) || !isValid(subtype)) {
            throw new IllegalArgumentException(INVALID_MEDIA_TYPE + type);
        }
        String params = null;
        if (paramIndex > -1) {
            params = type.substring(paramIndex + 1);
        }
        if (params != null && !params.equals("")) {
            Map<String, String> typeParams = new ParameterParser().parse(params, ';');
            return new MediaType(major, subtype, typeParams);
        } else {
            return new MediaType(major, subtype);
        }
    }

    private static boolean isValid(String str) {
        if (str == null || str.length() == 0) {
            return false;
        }
        char[] notValid = { '/', '\\', '?', ':', '<', '>', ';', '(', ')', '@', ',', '[', ']', '=' };
        return str.chars().noneMatch(c -> Arrays.binarySearch(notValid, (char) c) >= 0);
    }

    private String internalToString(MediaType type) {
        StringBuilder buf = new StringBuilder();

        buf.append(type.getType().toLowerCase()).append("/").append(type.getSubtype().toLowerCase());
        if (type.getParameters() == null || type.getParameters().size() == 0) {
            return buf.toString();
        }
        for (String name : type.getParameters().keySet()) {
            buf.append(';').append(name).append('=');
            String val = type.getParameters().get(name);
            if (quoted(val)) {
                buf.append('"').append(val).append('"');
            } else {
                buf.append(val);
            }
        }
        return buf.toString();
    }
}
