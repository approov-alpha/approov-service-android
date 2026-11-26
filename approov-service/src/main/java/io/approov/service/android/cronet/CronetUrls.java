//
// MIT License
//
// Copyright (c) 2016-present, Approov Ltd.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files
// (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge,
// publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
// subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
// ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH
// THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

package io.approov.service.android.cronet;

import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CronetUrls reads request URLs the way Cronet's URL parser (GURL) reads them, never with java.net.URI,
 * which rejects characters Cronet sends unchanged (such as |, {, } and ^ in a query) and quotes the
 * whole URL in its exception. Nothing here throws and no Cronet type is referenced.
 */
final class CronetUrls {
    /**
     * Construction is disallowed as this is a static only class.
     */
    private CronetUrls() {
    }

    // a URL in free text, as the scheme, the authority and then anything up to whitespace
    private static final Pattern URL_IN_TEXT = Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]*)://([^\\s/?#\\\\]*)\\S*");

    // cuts every URL to scheme and host, as user info, port, path, query and fragment may carry a secret
    static String withoutUrls(String text) {
        if (text == null)
            return null;
        Matcher matcher = URL_IN_TEXT.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String authority = matcher.group(2);
            String host = authority.substring(authority.lastIndexOf('@') + 1);
            if (host.startsWith("[")) {
                int close = host.indexOf(']');
                host = (close < 0) ? host : host.substring(0, close + 1);
            } else if (host.indexOf(':') >= 0) {
                host = host.substring(0, host.indexOf(':'));
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    matcher.group(1) + "://" + host.toLowerCase(Locale.ROOT)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    // Origin is the scheme, host and port of a URL
    static final class Origin {
        // the lowercase scheme
        final String scheme;

        // the lowercase ASCII host, without any trailing dot
        final String host;

        // the port, or the scheme's default port if none is given
        final int port;

        Origin(String scheme, String host, int port) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Origin))
                return false;
            Origin other = (Origin) o;
            return scheme.equals(other.scheme) && host.equals(other.host) && (port == other.port);
        }

        @Override
        public int hashCode() {
            return (scheme.hashCode() * 31 + host.hashCode()) * 31 + port;
        }
    }

    // null for an authority this layer will not interpret, so the request fails closed
    static Origin origin(String url) {
        if (url == null)
            return null;

        // read the scheme
        int schemeLength = 0;
        while (schemeLength < url.length()) {
            char c = url.charAt(schemeLength);
            boolean alpha = ((c >= 'a') && (c <= 'z')) || ((c >= 'A') && (c <= 'Z'));
            boolean other = ((c >= '0') && (c <= '9')) || (c == '+') || (c == '-') || (c == '.');
            if (alpha || ((schemeLength > 0) && other))
                schemeLength++;
            else
                break;
        }
        if ((schemeLength == 0) || (schemeLength >= url.length()) || (url.charAt(schemeLength) != ':'))
            return null;
        String scheme = url.substring(0, schemeLength).toLowerCase(Locale.ROOT);

        // find the authority, skipping any run of slashes after the scheme
        int start = schemeLength + 1;
        while ((start < url.length()) && (url.charAt(start) == '/'))
            start++;
        int end = start;
        while ((end < url.length()) && (url.charAt(end) != '/') && (url.charAt(end) != '?') && (url.charAt(end) != '#'))
            end++;
        String authority = url.substring(start, end);

        // we refuse userinfo, a backslash, whitespace and control characters anywhere in the authority
        for (int i = 0; i < authority.length(); i++) {
            char c = authority.charAt(i);
            if ((c == '\\') || (c == '@') || (c <= 0x20) || (c == 0x7f))
                return null;
        }

        // split the host from the port, checking an IPv6 literal holds only its own characters
        String hostPart;
        String portPart;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0)
                return null;
            hostPart = authority.substring(0, close + 1);
            String rest = authority.substring(close + 1);
            if (rest.isEmpty())
                portPart = null;
            else if (rest.startsWith(":"))
                portPart = rest.substring(1);
            else
                return null;
            String literal = hostPart.substring(1, hostPart.length() - 1);
            for (int i = 0; i < literal.length(); i++) {
                char c = Character.toLowerCase(literal.charAt(i));
                if (!(((c >= '0') && (c <= '9')) || ((c >= 'a') && (c <= 'f')) || (c == ':') || (c == '.')))
                    return null;
            }
        } else {
            int colon = authority.lastIndexOf(':');
            hostPart = (colon >= 0) ? authority.substring(0, colon) : authority;
            portPart = (colon >= 0) ? authority.substring(colon + 1) : null;
            if ((hostPart.indexOf('%') >= 0) || (hostPart.indexOf(':') >= 0))
                return null;
        }

        // read the port, using the scheme's default if there is none
        int port;
        if ((portPart == null) || portPart.isEmpty()) {
            port = defaultPort(scheme);
        } else {
            if (portPart.length() > 5)
                return null;
            for (int i = 0; i < portPart.length(); i++) {
                if ((portPart.charAt(i) < '0') || (portPart.charAt(i) > '9'))
                    return null;
            }
            port = Integer.parseInt(portPart);
            if (port > 65535)
                return null;
        }

        // normalise the host as GURL does
        String host = hostPart.toLowerCase(Locale.ROOT);
        boolean ascii = true;
        for (int i = 0; i < host.length(); i++) {
            if (host.charAt(i) > 0x7f)
                ascii = false;
        }
        if (!ascii) {
            try {
                host = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        if (host.endsWith("."))
            host = host.substring(0, host.length() - 1);
        if (host.isEmpty() || host.equals("[]"))
            return null;
        return new Origin(scheme, host, port);
    }

    // -1 if the scheme has none
    static int defaultPort(String scheme) {
        if ("https".equals(scheme))
            return 443;
        if ("http".equals(scheme))
            return 80;
        return -1;
    }

    // the URL in Cronet's GURL canonical form, as your backend API receives it and @target-uri covers it
    static String wireUrl(String url) {
        // trim the URL and remove tab, CR and LF
        int begin = 0;
        int finish = url.length();
        while ((begin < finish) && (url.charAt(begin) <= 0x20))
            begin++;
        while ((finish > begin) && (url.charAt(finish - 1) <= 0x20))
            finish--;
        StringBuilder stripped = new StringBuilder(finish - begin);
        for (int i = begin; i < finish; i++) {
            char c = url.charAt(i);
            if ((c != '\t') && (c != '\r') && (c != '\n'))
                stripped.append(c);
        }
        // drop the fragment
        String noFragment = stripped.toString();
        int hash = noFragment.indexOf('#');
        if (hash >= 0)
            noFragment = noFragment.substring(0, hash);
        Origin origin = origin(noFragment);
        if (origin == null)
            return noFragment;

        // rebuild the URL from the canonical origin and the encoded path and query
        int start = noFragment.indexOf(':') + 1;
        while ((start < noFragment.length()) && (noFragment.charAt(start) == '/'))
            start++;
        int end = start;
        while ((end < noFragment.length()) && (noFragment.charAt(end) != '/') && (noFragment.charAt(end) != '?'))
            end++;
        String rest = noFragment.substring(end);
        int q = rest.indexOf('?');
        String path = encode((q >= 0) ? rest.substring(0, q) : rest);
        if (path.isEmpty())
            path = "/";
        String query = (q >= 0) ? encode(rest.substring(q + 1)) : null;
        String port = ((origin.port < 0) || (origin.port == defaultPort(origin.scheme))) ? "" : (":" + origin.port);
        return origin.scheme + "://" + origin.host + port + path + ((query != null) ? ("?" + query) : "");
    }

    private static boolean sentAsIs(int c) {
        return (c >= 0x21) && (c <= 0x7e) && (c != '"') && (c != '<') && (c != '>');
    }

    // percent-encodes, as UTF-8, every character not sent as is
    private static String encode(String s) {
        boolean plain = true;
        for (int i = 0; i < s.length(); i++) {
            if (!sentAsIs(s.charAt(i)))
                plain = false;
        }
        if (plain)
            return s;
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (sentAsIs(c))
                sb.append((char) c);
            else
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 15]);
        }
        return sb.toString();
    }

    // hexadecimal digits for percent-encoding
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
}
