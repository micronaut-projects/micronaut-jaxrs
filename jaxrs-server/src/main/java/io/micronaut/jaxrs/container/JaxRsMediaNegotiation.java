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
package io.micronaut.jaxrs.container;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.MediaType;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The content negotiation of JAX-RS: the consumed type of a request (section 3.7.2), and the
 * combined type of the accepted and produced types of a response (section 3.8).
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
final class JaxRsMediaNegotiation {

    private static final String QS = "qs";

    private static final String WILDCARD = "*";

    private JaxRsMediaNegotiation() {
    }

    /**
     * The response type of JAX-RS (section 3.8) for accepted and produced types: the most
     * acceptable combined type, {@code application/octet-stream} for a wildcard one.
     *
     * @param accepted The accepted types, empty for any
     * @param produces The produced types
     * @return The type, or {@code null} if none is acceptable, or the combined type has a wildcard
     * subtype other than {@code application/*}
     */
    static @Nullable MediaType responseType(List<MediaType> accepted, List<MediaType> produces) {
        Negotiated negotiated = negotiate(accepted.isEmpty() ? List.of(MediaType.ALL_TYPE) : accepted, produces);
        if (negotiated == null) {
            return null;
        }
        MediaType type = negotiated.type();
        if (specificity(type) < 2 && octetStreamFallback(accepted.isEmpty() ? List.of(MediaType.ALL_TYPE) : accepted, produces)) {
            return MediaType.APPLICATION_OCTET_STREAM_TYPE;
        }
        return specificity(type) == 2 ? type : null;
    }

    /**
     * Whether the acceptable combined types of accepted and produced types, not only the most
     * acceptable one, include {@code *}{@code /*} or {@code application/*}: without a concrete
     * type, the response type is then {@code application/octet-stream} (JAX-RS 3.8, step 9).
     */
    static boolean octetStreamFallback(List<MediaType> accepted, List<MediaType> produces) {
        for (MediaType accept : accepted) {
            if (accept.getQualityAsNumber().doubleValue() <= 0) {
                continue;
            }
            for (MediaType produce : produces) {
                if (!accept.matches(produce) && !produce.matches(accept)) {
                    continue;
                }
                MediaType type = specificity(produce) >= specificity(accept) ? produce : accept;
                if (specificity(type) == 0 || specificity(type) == 1 && "application".equals(type.getType())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * How closely a content type matches the consumed types: 2 for a type, 1 for a type with a
     * wildcard subtype, 0 for any type, -1 when no consumed type matches.
     *
     * @param contentType The content type
     * @param consumes    The consumed types
     * @return How closely it matches
     */
    static int consumes(MediaType contentType, List<MediaType> consumes) {
        int best = -1;
        for (MediaType consumed : consumes) {
            if (consumed.matches(contentType) || contentType.matches(consumed)) {
                best = Math.max(best, specificity(consumed));
            }
        }
        return best;
    }

    /**
     * The most acceptable combined type of accepted and produced types (JAX-RS 3.8): the
     * candidates are compared with their own parameters, only the winner loses its {@code q} and
     * {@code qs}.
     *
     * @param accepted The accepted types, not empty
     * @param produces The produced types
     * @return The combined type, or {@code null} if none is acceptable
     */
    static @Nullable Negotiated negotiate(List<MediaType> accepted, List<MediaType> produces) {
        Negotiated best = null;
        for (MediaType accept : accepted) {
            double quality = accept.getQualityAsNumber().doubleValue();
            if (quality <= 0) {
                // q=0: not acceptable (RFC 9110, 12.4.2)
                continue;
            }
            for (MediaType produce : produces) {
                if (!accept.matches(produce) && !produce.matches(accept)) {
                    continue;
                }
                // the more specific of the two, with its parameters but q and qs (JAX-RS 3.8)
                MediaType type = specificity(produce) >= specificity(accept) ? produce : accept;
                if (overridden(accepted, accept, type)) {
                    continue;
                }
                // the wildcard components of one matched against concrete ones of the other (JAX-RS 3.7.2)
                int distance = Math.abs(specificity(produce) - specificity(accept));
                Negotiated negotiated = new Negotiated(type, quality, qs(produce), distance);
                if (best == null || negotiated.compareTo(best) > 0) {
                    best = negotiated;
                }
            }
        }
        return best == null ? null : new Negotiated(withoutQuality(best.type), best.q, best.qs, best.distance);
    }

    /**
     * Whether an accepted range more specific than the matching one also covers a type: the most
     * specific range determines the quality (RFC 9110, 12.5.1), also a nonzero one, so the
     * matching range does not apply. A range with parameters is more specific than the same
     * range without them, and covers only a type with the same parameters.
     */
    private static boolean overridden(List<MediaType> accepted, MediaType matching, MediaType type) {
        for (MediaType accept : accepted) {
            if (moreSpecific(accept, matching)
                && accept.matches(type)
                && parametersMatch(accept, type)) {
                return true;
            }
        }
        return false;
    }

    private static boolean moreSpecific(MediaType range, MediaType other) {
        int specificity = Integer.compare(specificity(range), specificity(other));
        if (specificity != 0) {
            return specificity > 0;
        }
        return parameterCount(range) > parameterCount(other);
    }

    /**
     * Whether a type has every parameter of a range, but {@code q} and {@code qs}.
     */
    private static boolean parametersMatch(MediaType range, MediaType type) {
        for (CharSequence parameter : range.getParameters()) {
            String name = parameter.toString();
            if (MediaType.Q_PARAMETER.equals(name) || QS.equals(name)) {
                continue;
            }
            String value = type.getParameters().get(name).orElse(null);
            if (value == null || !value.equalsIgnoreCase(range.getParameters().get(name).orElse(null))) {
                return false;
            }
        }
        return true;
    }

    private static int parameterCount(MediaType type) {
        int count = 0;
        for (CharSequence parameter : type.getParameters()) {
            String name = parameter.toString();
            if (!MediaType.Q_PARAMETER.equals(name) && !QS.equals(name)) {
                count++;
            }
        }
        return count;
    }

    private static MediaType withoutQuality(MediaType type) {
        Map<String, String> parameters = new LinkedHashMap<>();
        type.getParameters().forEach((name, value) -> {
            String key = name.toString();
            if (!MediaType.Q_PARAMETER.equals(key) && !QS.equals(key)) {
                parameters.put(key, value);
            }
        });
        return new MediaType(type.getType() + "/" + type.getSubtype(), parameters);
    }

    /**
     * @param type A media type
     * @return 2 for a concrete type, 1 for a wildcard subtype, 0 for any type
     */
    static int specificity(MediaType type) {
        if (WILDCARD.equals(type.getType())) {
            return 0;
        }
        return WILDCARD.equals(type.getSubtype()) ? 1 : 2;
    }

    private static double qs(MediaType type) {
        String qs = type.getParameters().get(QS).orElse(null);
        return qs == null ? 1 : Double.parseDouble(qs);
    }

    /**
     * A combined media type of JAX-RS: compared by specificity, then the quality of the accepted
     * type, then the {@code qs} of the produced type, then the distance, the smaller the better.
     *
     * @param type     The media type
     * @param q        The quality of the accepted type
     * @param qs       The quality of the produced type
     * @param distance The distance of the types
     */
    record Negotiated(MediaType type, double q, double qs, int distance) implements Comparable<Negotiated> {
        @Override
        public int compareTo(Negotiated other) {
            int result = Integer.compare(specificity(type), specificity(other.type));
            if (result == 0) {
                result = Double.compare(q, other.q);
            }
            if (result == 0) {
                result = Double.compare(qs, other.qs);
            }
            if (result == 0) {
                result = Integer.compare(other.distance, distance);
            }
            return result;
        }
    }
}
