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

package io.approov.service.android;

import java.util.Locale;

/**
 * WireUrl reads the parts of a request URL exactly as the networking stack sends it. The URL is only split
 * at its delimiters, so nothing is decoded, re-encoded or validated, every value is the wire form and no
 * input makes any method throw. Note that java.net.URI must not be used on the request path, as it rejects
 * characters the stacks send unchanged, such as '|', '{', '}' and '^' in a query, and toString() re-encodes others.
 */
final class WireUrl {
    // returned by port() for a port that is not a decimal number
    static final int MALFORMED_PORT = -2;

    /**
     * Construction is disallowed as this is a static only class.
     */
    private WireUrl() {
    }

    // a fragment is never sent, so it is never part of the target URI
    static String withoutFragment(String url) {
        int hash = url.indexOf('#');
        return (hash < 0) ? url : url.substring(0, hash);
    }

    // 0 if the URL does not start with a scheme
    private static int schemeEnd(String url) {
        int colon = url.indexOf(':');
        if (colon <= 0)
            return 0;
        for (int i = 0; i < colon; i++) {
            char c = url.charAt(i);
            boolean alpha = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            if (!alpha && ((i == 0) || !(((c >= '0') && (c <= '9')) || (c == '+') || (c == '-') || (c == '.'))))
                return 0;
        }
        return colon + 1;
    }

    // the length of the URL if there is no delimiter
    private static int indexOfAny(String url, int from, String delimiters) {
        for (int i = from; i < url.length(); i++) {
            if (delimiters.indexOf(url.charAt(i)) >= 0)
                return i;
        }
        return url.length();
    }

    // lowercase, or null if there is none
    static String scheme(String url) {
        int end = schemeEnd(url);
        return (end == 0) ? null : url.substring(0, end - 1).toLowerCase(Locale.ROOT);
    }

    // -1 if the URL has no authority
    private static int authorityStart(String url) {
        int start = schemeEnd(url);
        return url.startsWith("//", start) ? start + 2 : -1;
    }

    // as sent, including any user information and port
    static String authority(String url) {
        int start = authorityStart(url);
        if (start < 0)
            return null;
        return url.substring(start, indexOfAny(url, start, "/?#"));
    }

    // the authority without any user information
    private static String hostAndPort(String url) {
        String authority = authority(url);
        if (authority == null)
            return null;
        int at = authority.lastIndexOf('@');
        return (at < 0) ? authority : authority.substring(at + 1);
    }

    // -1 if there is no port
    private static int portColon(String hostAndPort) {
        if (hostAndPort.startsWith("[")) {
            int close = hostAndPort.indexOf(']');
            return ((close >= 0) && hostAndPort.startsWith(":", close + 1)) ? close + 1 : -1;
        }
        return hostAndPort.lastIndexOf(':');
    }

    // lowercase, without user information or port; an IPv6 literal keeps its brackets
    static String host(String url) {
        String hostAndPort = hostAndPort(url);
        if (hostAndPort == null)
            return null;
        int colon = portColon(hostAndPort);
        String host = (colon < 0) ? hostAndPort : hostAndPort.substring(0, colon);
        return host.toLowerCase(Locale.ROOT);
    }

    // -1 if there is none or it is empty, or MALFORMED_PORT if it is not a decimal number
    static int port(String url) {
        String hostAndPort = hostAndPort(url);
        if (hostAndPort == null)
            return -1;
        int colon = portColon(hostAndPort);
        if ((colon < 0) || (colon == hostAndPort.length() - 1))
            return -1;
        String port = hostAndPort.substring(colon + 1);
        if (port.length() > 5)
            return MALFORMED_PORT;
        for (int i = 0; i < port.length(); i++) {
            if ((port.charAt(i) < '0') || (port.charAt(i) > '9'))
                return MALFORMED_PORT;
        }
        return Integer.parseInt(port);
    }

    // as sent, which may not be a number
    static String portText(String url) {
        String hostAndPort = hostAndPort(url);
        if (hostAndPort == null)
            return null;
        int colon = portColon(hostAndPort);
        return (colon < 0) ? null : hostAndPort.substring(colon + 1);
    }

    private static int pathStart(String url) {
        int start = authorityStart(url);
        return (start < 0) ? schemeEnd(url) : indexOfAny(url, start, "/?#");
    }

    // as sent, still percent-encoded
    static String path(String url) {
        int start = pathStart(url);
        return url.substring(start, indexOfAny(url, start, "?#"));
    }

    // as sent, still percent-encoded and without the '?'; empty for a bare '?', null if there is none
    static String query(String url) {
        int mark = indexOfAny(url, pathStart(url), "?#");
        if ((mark >= url.length()) || (url.charAt(mark) != '?'))
            return null;
        return url.substring(mark + 1, indexOfAny(url, mark + 1, "#"));
    }
}
