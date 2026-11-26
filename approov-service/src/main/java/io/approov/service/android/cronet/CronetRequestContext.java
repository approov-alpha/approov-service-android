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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import io.approov.service.android.ApproovRequestContext;

/**
 * CronetRequestContext is the ApproovRequestContext of one Cronet request attempt (hop). The URL is held
 * as your app gave it, which is what the Approov SDK token fetch receives, while getTargetURI() is the
 * URL as Cronet sends it, which message signing covers and secure string query substitutions are read
 * back from. Headers keep your app's order and names are matched case-insensitively.
 */
final class CronetRequestContext implements ApproovRequestContext {
    // the URL as your app gave it, or as later changed by the pipeline
    private String url;

    // the HTTP method of the attempt
    private final String method;

    // the headers as name and value pairs, in order
    private final List<String[]> headers;

    // the body of the attempt, or null if there is none
    private final byte[] body;

    // holds a copy of the headers
    CronetRequestContext(String url, String method, List<String[]> headers, byte[] body) {
        this.url = url;
        this.method = method;
        this.headers = new ArrayList<>();
        for (String[] header : headers)
            this.headers.add(new String[] {header[0], header[1]});
        this.body = body;
    }

    // a copy of the headers as they will be sent, in order
    List<String[]> headers() {
        List<String[]> copy = new ArrayList<>(headers.size());
        for (String[] header : headers)
            copy.add(new String[] {header[0], header[1]});
        return copy;
    }

    byte[] body() {
        return body;
    }

    @Override
    public String getMethod() {
        return method;
    }

    @Override
    public String getURL() {
        return url;
    }

    @Override
    public String getTargetURI() {
        return CronetUrls.wireUrl(url);
    }

    @Override
    public boolean supportsURLMutation() {
        return true;
    }

    @Override
    public void setURL(String url) {
        this.url = url;
    }

    @Override
    public String getHeader(String name) {
        for (String[] header : headers) {
            if (header[0].equalsIgnoreCase(name))
                return header[1];
        }
        return null;
    }

    @Override
    public List<String> getHeaders(String name) {
        List<String> values = new ArrayList<>();
        for (String[] header : headers) {
            if (header[0].equalsIgnoreCase(name))
                values.add(header[1]);
        }
        return values;
    }

    @Override
    public List<String> getHeaderNames() {
        List<String> names = new ArrayList<>();
        for (String[] header : headers) {
            boolean seen = false;
            for (String name : names) {
                if (name.equalsIgnoreCase(header[0]))
                    seen = true;
            }
            if (!seen)
                names.add(header[0]);
        }
        return names;
    }

    /**
     * Sets the header, replacing every value of it with one kept at the first one's position.
     */
    @Override
    public void setHeader(String name, String value) {
        boolean replaced = false;
        Iterator<String[]> it = headers.iterator();
        while (it.hasNext()) {
            String[] header = it.next();
            if (header[0].equalsIgnoreCase(name)) {
                if (replaced) {
                    it.remove();
                } else {
                    header[1] = value;
                    replaced = true;
                }
            }
        }
        if (!replaced)
            headers.add(new String[] {name, value});
    }

    @Override
    public void removeHeader(String name) {
        Iterator<String[]> it = headers.iterator();
        while (it.hasNext()) {
            if (it.next()[0].equalsIgnoreCase(name))
                it.remove();
        }
    }

    @Override
    public boolean hasBody() {
        return body != null;
    }

    @Override
    public byte[] getBodyBytes() {
        return body;
    }
}
