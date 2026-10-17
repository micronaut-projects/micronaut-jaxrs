/*
 * Copyright 2017-2024 original authors
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
import jakarta.ws.rs.ext.RuntimeDelegate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The implementation of {@link RuntimeDelegate.HeaderDelegate} for dates: an HTTP date
 * (RFC 9110, 5.6.7), in GMT.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
final class DateHeaderDelegate implements RuntimeDelegate.HeaderDelegate<Date> {

    static final DateHeaderDelegate INSTANCE = new DateHeaderDelegate();

    // the preferred format, e.g. Sun, 06 Nov 1994 08:49:37 GMT
    private static final DateTimeFormatter IMF_FIXDATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .withZone(ZoneOffset.UTC);
    // the obsolete formats a recipient also reads: RFC 850 and asctime
    private static final List<DateTimeFormatter> PARSERS = List.of(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        // a two-digit year of RFC 850 is one since 1970
        new DateTimeFormatterBuilder().appendPattern("EEEE, dd-MMM-")
            .appendValueReduced(ChronoField.YEAR, 2, 2, 1970)
            .appendPattern(" HH:mm:ss zzz")
            .toFormatter(Locale.US),
        DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.US).withZone(ZoneOffset.UTC)
    );

    private DateHeaderDelegate() {
    }

    @Override
    public Date fromString(String value) {
        JaxRsUtils.requireNonNull("value", value);
        String trimmed = value.trim();
        for (DateTimeFormatter parser : PARSERS) {
            try {
                return Date.from(ZonedDateTime.parse(trimmed, parser).toInstant());
            } catch (DateTimeParseException e) {
                // the next format
            }
        }
        throw new IllegalArgumentException("Not an HTTP date: " + value);
    }

    @Override
    public String toString(Date value) {
        JaxRsUtils.requireNonNull("value", value);
        return IMF_FIXDATE.format(Instant.ofEpochMilli(value.getTime()));
    }
}
