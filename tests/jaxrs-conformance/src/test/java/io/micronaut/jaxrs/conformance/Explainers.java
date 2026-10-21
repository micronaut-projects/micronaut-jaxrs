package io.micronaut.jaxrs.conformance;

import java.util.List;

/**
 * The triage of the differences of the client API tests. "BUG" labels are confirmed Micronaut bugs, each
 * reproduced literally in {@link ClientFindingsTest}; "IMPL" labels are implementation-defined differences;
 * "JERSEY" labels are Jersey deviations from the specification.
 */
final class Explainers {
    private Explainers() {
    }

    private static boolean throwsIn(Object o) {
        return String.valueOf(o).startsWith("throws ");
    }

    /** HeaderConformanceTest. */
    static String headers(String category, String input, Object ours, Object jersey) {
        if (category.equals("quoted-string") && input.equals(ours)) {
            return "JERSEY MediaType.toString does not escape a backslash in a quoted parameter";
        }
        if (category.startsWith("media:")) {
            if (throwsIn(jersey)) {
                return "IMPL MediaType.valueOf accepts characters that are not token characters";
            }
        }
        return null;
    }

    /** UriConformanceTest and the URI builder modes of ClientApiConformanceTest. */
    static String uris(String category, String input, Object ours, Object jersey) {
        String o = String.valueOf(ours);
        String j = String.valueOf(jersey);
        if (o.replace(";", "%3B").equals(j) || o.replace(";", "%3B").replace("%253B", "%3B").equals(j)) {
            return "IMPL ';' in a path value is not encoded (RFC 3986 allows it in a segment, Jersey encodes it)";
        }
        if (o.replace("=", "").equals(j.replace("=", "")) && o.contains(";")) {
            return "IMPL matrixParam with an empty value is written 'm=' (Jersey 'm')";
        }
        if (input.startsWith("value=") && category.equals("mode=8") || !input.startsWith("value=") && category.equals("mode=16")) {
            return "IMPL replaceQueryParam appends the replaced parameter (Jersey keeps its position)";
        }
        if (o.contains(";") && j.contains("%3B")) {
            return "IMPL ';' in a path value is not encoded (RFC 3986 allows it in a segment, Jersey encodes it)";
        }
        if (input.startsWith("value=") && category.equals("mode=6")) {
            return "IMPL '/' is encoded in a fragment and ';' kept in a path (RFC 3986 allows both)";
        }
        if (category.equals("mode=11") || category.equals("mode=4")) {
            return "JERSEY/IMPL replaceQuery and fragment: Jersey encodes '/', '?' and '@' that RFC 3986 allows; Micronaut keeps an empty fragment";
        }
        return null;
    }

    /** ClientApiConformanceTest.seededHeaderDelegateRoundTrips. */
    static String delegates(String category, String input, Object ours, Object jersey) {
        switch (category) {
            case "link" -> {
                if (input.contains("< ")) {
                    return "JERSEY Link.valueOf accepts whitespace within <> (RFC 8288 3: a syntax error)";
                }
                if (input.indexOf("rel=") != input.lastIndexOf("rel=")) {
                    return "JERSEY Link.valueOf keeps the last of a repeated rel (RFC 8288 3.3: the first)";
                }
                return null;
            }
            case "link-build" -> {
                return throwsIn(jersey) ? "JERSEY Link.valueOf cannot read back the title/rel Link.toString wrote" : null;
            }
            case "locale" -> {
                return throwsIn(ours) ? "IMPL no HeaderDelegate<Locale> (Jersey has one; JAX-RS does not require it)" : null;
            }
            case "media-type", "media-type-toString" -> {
                return throwsIn(jersey) ? "IMPL MediaType.valueOf is lenient (accepts '*', spaces, empty parameter names/values)" : null;
            }
            default -> {
                return null;
            }
        }
    }

    /** ClientApiConformanceTest.seededResponseBuilder: the index of the first differing property. */
    static String responses(String category, String input, Object ours, Object jersey) {
        if (throwsIn(jersey) && !throwsIn(ours)) {
            return "IMPL Jersey parses typed headers eagerly in the getters, Micronaut tolerates invalid values";
        }
        if (throwsIn(ours) && !throwsIn(jersey)) {
            if (input.startsWith("status=-1 ")) {
                return "JERSEY Response.status(-1) is accepted";
            }
            if (input.startsWith("status=99 ") || input.startsWith("status=600 ")) {
                return "JERSEY Response.status accepts a status out of 100..599";
            }
            return input.endsWith("Set-Cookie: ") ? "IMPL an empty Set-Cookie header value is rejected (IllegalArgumentException)" : null;
        }
        if (throwsIn(ours)) {
            return "IMPL both reject the invalid header, with another exception";
        }
        List<?> a = (List<?>) ours;
        List<?> b = (List<?>) jersey;
        for (int i = 0; i < a.size(); i++) {
            if (!String.valueOf(a.get(i)).equals(String.valueOf(b.get(i)))) {
                switch (i) {
                    case 1:
                        return "IMPL reason phrase of a status without a Response.Status constant";
                    case 9:
                    case 10:
                        return "IMPL parsing of an invalid Allow/Set-Cookie value set as a raw header";
                    case 13:
                    case 17:
                        if (i == 13 && input.contains("Link: ")) {
                            return "IMPL a raw Link header is kept as written (Jersey re-serializes it)";
                        }
                        return null;
                    default:
                        return null;
                }
            }
        }
        return null;
    }

    /** ClientApiConformanceTest.seededVariantListBuilder. */
    static String variants(String category, String input, Object ours, Object jersey) {
        if (input.startsWith("0/0/0")) {
            return "JERSEY VariantListBuilder.add() with nothing throws IllegalArgumentException, the javadoc says IllegalStateException";
        }
        if (ours instanceof List<?> a && jersey instanceof List<?> b
            && a.stream().map(v -> String.valueOf(v).toLowerCase()).sorted().toList()
            .equals(b.stream().map(v -> String.valueOf(v).toLowerCase()).sorted().toList())) {
            return "IMPL VariantListBuilder combination order (language before encoding varies)";
        }
        return null;
    }
}
