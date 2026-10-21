package io.micronaut.jaxrs.conformance;

import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.Variant;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Differential tests of the client side API: the header delegates, the URI builder, the response builder and
 * the variant list builder, against Jersey's runtime delegate.
 */
@Timeout(120)
class ClientApiConformanceTest {

    private static String pick(Random r, String... values) {
        return values[r.nextInt(values.length)];
    }

    @Test
    void seededHeaderDelegateRoundTrips() {
        long seed = 0x44454c45L;
        Random r = new Random(seed);
        var d = new Oracles.Differences(Explainers::delegates);
        for (int i = 0; i < 1500; i++) {
            // cookies as received in a Cookie request header
            String cookie = pick(r, "a", "$Version=1; ", "") + pick(r, "n=v", "n=\"v w\"", "n=", "n=v;$Path=/p", "n=v; $Domain=.x.test",
                "n=v,m=w", "n=v; m=w", "N=v", "n=\"a;b\"", "n=v=w", " n = v ", "n=\"\"");
            d.compare("cookie", cookie, () -> {
                Cookie c = RuntimeDelegate.getInstance().createHeaderDelegate(Cookie.class).fromString(cookie);
                return List.of(c.getName(), c.getValue(), String.valueOf(c.getPath()), String.valueOf(c.getDomain()), c.getVersion());
            });
            // a Set-Cookie header
            String setCookie = pick(r, "n=v", "n=\"v w\"", "n=") + pick(r, "", "; Path=/p", "; Domain=x.test", "; Max-Age=10", "; Max-Age=-1",
                "; Secure", "; HttpOnly", "; Version=1", "; Comment=hi", "; Expires=Wed, 21 Oct 2015 07:28:00 GMT", "; SameSite=Lax",
                "; SameSite=none", "; max-age=5", "; Path=\"/q\"", "; Expires=Sun, 06 Nov 1994 08:49:37 GMT; Max-Age=3")
                + pick(r, "", "; secure", "; Path=/z; HttpOnly");
            d.compare("set-cookie", setCookie, () -> {
                NewCookie c = RuntimeDelegate.getInstance().createHeaderDelegate(NewCookie.class).fromString(setCookie);
                return List.of(c.getName(), c.getValue(), String.valueOf(c.getPath()), String.valueOf(c.getDomain()), c.getMaxAge(),
                    c.isSecure(), c.isHttpOnly(), String.valueOf(c.getComment()), String.valueOf(c.getExpiry() == null ? null : c.getExpiry().getTime()),
                    String.valueOf(c.getSameSite()), c.getVersion());
            });
            // NewCookie built then serialized then parsed
            String name = pick(r, "n", "N1", "a_b");
            String value = pick(r, "v", "v w", "", "a;b", "a\"b", "a,b", "é");
            int maxAge = pick(r, "-1", "0", "100").equals("-1") ? -1 : r.nextInt(2) * 100;
            boolean secure = r.nextBoolean();
            String path = r.nextBoolean() ? null : pick(r, "/", "/a b", "/p");
            NewCookie.SameSite sameSite = r.nextBoolean() ? null : NewCookie.SameSite.values()[r.nextInt(3)];
            d.compare("newcookie-build", name + "=" + value + " path=" + path + " maxAge=" + maxAge + " secure=" + secure + " sameSite=" + sameSite, () -> {
                NewCookie c = new NewCookie.Builder(name).value(value).path(path).maxAge(maxAge).secure(secure).sameSite(sameSite).build();
                String text = c.toString();
                NewCookie back = NewCookie.valueOf(text);
                return List.of(back.getName(), back.getValue(), String.valueOf(back.getPath()), back.getMaxAge(), back.isSecure(),
                    String.valueOf(back.getSameSite()));
            });
            // links
            String link = pick(r, "<http://x.test/a>", "<http://x.test/a?b=c&d=e>", "<rel/a>", "< http://x.test >")
                + pick(r, "", "; rel=next", "; rel=\"next prev\"", "; title=\"a, b\"", "; type=\"text/plain\"", "; rel=next; rel=last",
                "; anchor=\"#x\"", "; REL=up", ";rel=next;title=t", "; title*=UTF-8'de'n%c3%a4chstes", "; x=\"a\\\"b\"", "; hreflang=en");
            d.compare("link", link, () -> {
                Link l = Link.valueOf(link);
                return List.of(l.getUri().toString(), new TreeMap<>(l.getParams()).toString(), l.getRels().toString(), String.valueOf(l.getTitle()),
                    String.valueOf(l.getType()));
            });
            // link built then serialized
            String rel = pick(r, "next", "next prev", "a,b", "");
            String title = pick(r, "t", "a b", "a\"b", "a,b", "a;b");
            d.compare("link-build", "rel=" + rel + " title=" + title, () -> {
                Link l = Link.fromUri("http://x.test/a").rel(rel).title(title).param("p", title).build();
                Link back = Link.valueOf(l.toString());
                return List.of(new TreeMap<>(back.getParams()).toString(), back.getRels().toString());
            });
            // entity tags
            String tag = pick(r, "\"a\"", "W/\"a\"", "\"a\\\"b\"", "\"\"", "W/\"\"", "\"a b\"", "w/\"a\"", " \"a\"", "\"a\" ", "\"a,b\"", "*");
            d.compare("etag", tag, () -> {
                EntityTag t = EntityTag.valueOf(tag);
                return List.of(t.getValue(), t.isWeak(), t.toString());
            });
            String tagValue = pick(r, "a", "a\"b", "a\\b", "", "a b", "é");
            boolean weak = r.nextBoolean();
            d.compare("etag-build", tagValue + " weak=" + weak, () -> {
                EntityTag t = new EntityTag(tagValue, weak);
                EntityTag back = EntityTag.valueOf(t.toString());
                return List.of(t.toString(), back.getValue(), back.isWeak(), back.equals(t));
            });
            // cache control
            String cc = String.join(", ", randomSubset(r, "no-cache", "no-store", "max-age=60", "s-maxage=5", "private", "public", "no-transform",
                "must-revalidate", "proxy-revalidate", "x=y", "x=\"a b\"", "no-cache=\"Set-Cookie\"", "private=\"a, b\"", "max-age=abc", "MAX-AGE=3",
                "max-age=\"7\"", "stale-while-revalidate=10", "immutable"));
            d.compare("cache-control", cc, () -> {
                CacheControl c = CacheControl.valueOf(cc);
                return List.of(c.isPrivate(), c.getPrivateFields(), c.isNoCache(), c.getNoCacheFields(), c.getMaxAge(), c.getSMaxAge(),
                    c.isNoStore(), c.isNoTransform(), c.isMustRevalidate(), c.isProxyRevalidate(), new TreeMap<>(c.getCacheExtension()));
            });
            d.compare("cache-control-roundtrip", cc, () -> {
                CacheControl c = CacheControl.valueOf(cc);
                return CacheControl.valueOf(c.toString()).equals(c);
            });
            // media types
            String mt = pick(r, "text/plain", "application/vnd.a+json", "*/*", "text/*", "*", "application/json;charset=utf-8",
                "text/plain;q=0.5;level=1", "TEXT/Plain;Charset=UTF-8", "a/b;x=\"y;z\"", "a/b;x=", "a/b;=y", "a/b; x = y", "a/b;x=y;x=z", "/b", "a/",
                "a/b/c", "a b/c", "*/b", "a/b;x=\"\"");
            d.compare("media-type", mt, () -> {
                MediaType m = MediaType.valueOf(mt);
                return List.of(m.getType(), m.getSubtype(), new TreeMap<>(m.getParameters()).toString(), m.isWildcardType(), m.isWildcardSubtype());
            });
            d.compare("media-type-toString", mt, () -> MediaType.valueOf(MediaType.valueOf(mt).toString()).equals(MediaType.valueOf(mt)));
            // dates
            String date = pick(r, "Sun, 06 Nov 1994 08:49:37 GMT", "Sunday, 06-Nov-94 08:49:37 GMT", "Sun Nov  6 08:49:37 1994",
                "Sun, 06 Nov 1994 08:49:37 +0000", "Sun, 6 Nov 1994 08:49:37 GMT", "garbage");
            d.compare("date", date, () -> RuntimeDelegate.getInstance().createHeaderDelegate(Date.class).fromString(date).getTime());
            long epoch = 784111777000L + r.nextInt(1_000_000) * 1000L;
            d.compare("date-toString", String.valueOf(epoch),
                () -> RuntimeDelegate.getInstance().createHeaderDelegate(Date.class).toString(new Date(epoch)));
            // locale
            String lang = pick(r, "en", "en-US", "de-CH-1996", "*", "zh-Hant-TW", "i-klingon", "EN-us", "");
            d.compare("locale", lang, () -> RuntimeDelegate.getInstance().createHeaderDelegate(Locale.class).fromString(lang).toString());
        }
        d.verify(seed);
    }

    private static List<String> randomSubset(Random r, String... values) {
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (r.nextInt(5) == 0) {
                out.add(v);
            }
        }
        return out;
    }

    @Test
    void seededUriBuilderOperations() {
        long seed = 0x55524942L;
        Random r = new Random(seed);
        var d = new Oracles.Differences(Explainers::uris);
        String[] values = {"", "a", "a/b", "/", "a+b", "a b", "%", "%2F", "%20", "%GG", "a;b", "a?b", "a#b", "é", "{x}", "a&b=c", "a=b", ":", "@",
            "a%2Fb", "..", "~", "%7e"};
        for (int i = 0; i < 3000; i++) {
            String v = pick(r, values);
            String w = pick(r, values);
            int mode = r.nextInt(24);
            d.compare("mode=" + mode, "v=" + v + " w=" + w, () -> switch (mode) {
                case 0 -> UriBuilder.fromPath("").path(v).build().toString();
                case 1 -> UriBuilder.fromUri("http://x.test/a").path(v).path(w).build().toString();
                case 2 -> UriBuilder.fromUri("http://x.test/a").segment(v, w).build().toString();
                case 3 -> UriBuilder.fromUri("http://x.test/").queryParam(v, w).build().toString();
                case 4 -> UriBuilder.fromUri("http://x.test/").fragment(v).build().toString();
                case 5 -> UriBuilder.fromUri("http://x.test/{a}/{b}").build(new Object[]{v, w}, false).toString();
                case 6 -> UriBuilder.fromUri("http://x.test/{a}").buildFromEncodedMap(Map.of("a", v)).toString();
                case 7 -> UriBuilder.fromUri("http://x.test/{a}").resolveTemplate("a", v, true).toTemplate();
                case 8 -> UriBuilder.fromUri("http://x.test/{a}").resolveTemplate("a", v, false).toTemplate();
                case 9 -> UriBuilder.fromUri("http://x.test/p;m=1/q;m=2").replaceMatrixParam("m", v).build().toString();
                case 10 -> UriBuilder.fromUri("http://x.test/p;m=1").replaceMatrix(v).build().toString();
                case 11 -> UriBuilder.fromUri("http://x.test/p?q=1").replaceQuery(v).build().toString();
                case 12 -> UriBuilder.fromUri("http://x.test/p").replacePath(v).build().toString();
                case 13 -> UriBuilder.fromUri("http://x.test/").userInfo(v).build().toString();
                case 14 -> UriBuilder.fromUri("http://x.test/").host(v.isEmpty() ? "h" : v).build().toString();
                case 15 -> UriBuilder.fromUri("http://x.test/").path(v).build().toString();
                case 16 -> UriBuilder.fromUri("http://x.test/?a=1&b=2&a=3").replaceQueryParam("a").build().toString();
                case 17 -> UriBuilder.fromUri("http://x.test/{a}?q={b}").buildFromEncoded(v, w).toString();
                case 18 -> UriBuilder.fromUri("http://x.test/{a}{b}").build(v, w).toString();
                case 19 -> UriBuilder.fromUri("mailto:" + (v.isEmpty() ? "a" : "a@x.test")).build().toString();
                case 20 -> UriBuilder.fromUri(URI.create("http://x.test/a/b/../c?x#f")).build().toString();
                case 21 -> UriBuilder.fromUri("http://x.test/p").matrixParam(v.isEmpty() ? "m" : v, w).build().toString();
                case 22 -> UriBuilder.fromLink(Link.fromUri("http://x.test/").build()).path(v).build().toString();
                default -> UriBuilder.fromUri("http://x.test/a/").path("{x: [a-z]+}").path("/{y}/").build(v, w).toString();
            });
        }
        d.verify(seed);
    }

    @Test
    void seededResponseBuilder() {
        long seed = 0x52455350L;
        Random r = new Random(seed);
        var d = new Oracles.Differences(Explainers::responses);
        for (int i = 0; i < 1500; i++) {
            int status = new int[]{100, 200, 201, 204, 299, 301, 304, 404, 418, 499, 500, 599, 600, 99, -1}[r.nextInt(15)];
            String reason = r.nextBoolean() ? null : pick(r, "Custom", "", "OK");
            String ct = r.nextBoolean() ? null : pick(r, "text/plain", "application/json;charset=UTF-8", "a/b");
            String lang = r.nextBoolean() ? null : pick(r, "en", "de-CH", "");
            String header = pick(r, "X-A", "Content-Length", "Date", "Last-Modified", "ETag", "Allow", "Link", "Location", "Expires", "Vary",
                "Set-Cookie", "Content-Language", "Content-Type");
            String headerValue = pick(r, "1", "\"t\"", "GET, POST", "<http://x.test/>; rel=next", "Sun, 06 Nov 1994 08:49:37 GMT", "n=v",
                "text/html", "fr", "garbage", "");
            int extra = r.nextInt(4);
            d.compare("status=" + status + " extra=" + extra, "status=" + status + " reason=" + reason + " ct=" + ct + " lang=" + lang
                + " " + header + ": " + headerValue, () -> {
                Response.ResponseBuilder b = reason == null ? Response.status(status) : Response.status(status, reason);
                if (ct != null) {
                    b.type(ct);
                }
                if (lang != null) {
                    b.language(lang);
                }
                b.header(header, headerValue);
                switch (extra) {
                    case 1 -> b.allow("PUT", "get").cookie(new NewCookie.Builder("c").value("v").build()).tag("t").lastModified(new Date(0));
                    case 2 -> b.links(Link.fromUri("http://x.test/").rel("self").build()).variant(new Variant(MediaType.TEXT_HTML_TYPE, "fr", "gzip"));
                    case 3 -> b.entity("abc").header(header, null).expires(new Date(0)).location(URI.create("http://x.test/l"));
                    default -> {
                    }
                }
                Response resp = b.build();
                Response copy = Response.fromResponse(resp).build();
                List<Object> out = new ArrayList<>();
                out.add(resp.getStatus());
                out.add(resp.getStatusInfo().getReasonPhrase());
                out.add(String.valueOf(resp.getStatusInfo().getFamily()));
                out.add(String.valueOf(resp.getMediaType()));
                out.add(String.valueOf(resp.getLanguage()));
                out.add(resp.getLength());
                out.add(String.valueOf(resp.getEntityTag()));
                out.add(String.valueOf(resp.getLastModified() == null ? null : resp.getLastModified().getTime()));
                out.add(String.valueOf(resp.getDate() == null ? null : resp.getDate().getTime()));
                out.add(new java.util.TreeSet<>(resp.getAllowedMethods()).toString());
                out.add(new TreeMap<>(resp.getCookies()).keySet().toString());
                out.add(String.valueOf(resp.getLocation()));
                out.add(resp.getLinks().size());
                out.add(String.valueOf(resp.getHeaderString(header)));
                out.add(String.valueOf(resp.getHeaderString("Vary")));
                out.add(resp.hasEntity());
                out.add(copy.getStatus());
                out.add(String.valueOf(copy.getHeaderString(header)));
                return out;
            });
        }
        d.verify(seed);
    }

    @Test
    void seededVariantListBuilder() {
        long seed = 0x56415249L;
        Random r = new Random(seed);
        var d = new Oracles.Differences(Explainers::variants);
        for (int i = 0; i < 500; i++) {
            int m = r.nextInt(3);
            int l = r.nextInt(3);
            int e = r.nextInt(3);
            boolean twice = r.nextBoolean();
            d.compare("variants", m + "/" + l + "/" + e + "/" + twice, () -> {
                Variant.VariantListBuilder b = Variant.VariantListBuilder.newInstance();
                for (int k = 0; k < (twice ? 2 : 1); k++) {
                    b.mediaTypes(List.of(MediaType.TEXT_PLAIN_TYPE, MediaType.APPLICATION_JSON_TYPE).subList(0, m).toArray(new MediaType[0]));
                    b.languages(List.of(Locale.ENGLISH, Locale.GERMAN).subList(0, l).toArray(new Locale[0]));
                    b.encodings(List.of("gzip", "br").subList(0, e).toArray(new String[0]));
                    b.add();
                }
                List<String> out = new ArrayList<>();
                for (Variant v : b.build()) {
                    out.add(v.getMediaType() + "|" + v.getLanguageString() + "|" + v.getEncoding());
                }
                return out;
            });
        }
        d.verify(seed);
    }
}
