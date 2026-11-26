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

import android.content.Context;

import com.android.volley.RequestQueue;
import com.android.volley.toolbox.BaseHttpStack;
import com.android.volley.toolbox.Volley;

/**
 * ApproovVolleyWeaver is the runtime target of the Approov Gradle plugin for Volley. The plugin rewrites
 * single argument Volley.newRequestQueue(context) call sites in the app to call newRequestQueue() here
 * instead, which builds the queue on the Approov BaseHttpStack so that every request is Approov protected
 * as on every other adapter.
 * <p>
 * Note that this class must not be renamed or moved, as the plugin references it by its fully qualified
 * name and method signature.
 */
public final class ApproovVolleyWeaver {
    // factory for the request queue, which tests may replace
    interface QueueFactory {
        RequestQueue create(Context context, BaseHttpStack stack);
    }

    // any queue factory set by tests, or null to use Volley
    private static volatile QueueFactory queueFactory;

    /**
     * Construction is disallowed as this is a static only class.
     */
    private ApproovVolleyWeaver() {
    }

    static void setQueueFactoryForTesting(QueueFactory factory) {
        queueFactory = factory;
    }

    static void resetForTesting() {
        queueFactory = null;
    }

    /**
     * Builds a Volley RequestQueue on the Approov protected stack. The stack is installed even before
     * ApproovService.initialize(), so a queue created early (for example one cached by the app) becomes
     * protected once initialization completes, and requests made before then are sent without Approov
     * processing.
     *
     * @param context is the Android context
     * @return the RequestQueue on the Approov protected stack
     */
    public static RequestQueue newRequestQueue(Context context) {
        // build the queue on the Approov stack, using any factory set by tests
        BaseHttpStack stack = ApproovVolley.getBaseHttpStack();
        QueueFactory factory = queueFactory;
        if (factory != null)
            return factory.create(context, stack);
        return Volley.newRequestQueue(context, stack);
    }
}
