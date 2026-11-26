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

package io.approov.service.android.volley;

import com.android.volley.toolbox.BaseHttpStack;

/**
 * ApproovVolley is the Volley adapter of the Approov service layer. Build your RequestQueue with
 * Volley.newRequestQueue(context, ApproovVolley.getBaseHttpStack()), and all requests executed by the
 * queue are then routed through the ApproovService request pipeline, which adds the Approov token, performs
 * secure string substitutions and applies any configured message signing, and the connections are pinned
 * with the Approov dynamic pins.
 * <p>
 * Secure strings are substituted as on every other adapter, in a registered substitution header returned
 * from the Request's getHeaders() or a query parameter in its URL. Note that a secure string is never
 * placed in a request body (getParams()).
 */
public class ApproovVolley {
    // the shared Approov HurlStack, created lazily
    private static ApproovHurlStack hurlStack = null;

    /**
     * Construction is disallowed as this is a static only class.
     */
    private ApproovVolley() {
    }

    /**
     * Gets the BaseHttpStack to be used with Volley that adds Approov protection
     * and pinning to all requests.
     *
     * @return BaseHttpStack to be passed to Volley.newRequestQueue
     */
    public static synchronized BaseHttpStack getBaseHttpStack() {
        if (hurlStack == null)
            hurlStack = new ApproovHurlStack();
        return hurlStack;
    }
}
