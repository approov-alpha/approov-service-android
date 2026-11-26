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

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * StandardDecisions records the exceptions that the standard request path decisions (the CLOSE_FAILURE
 * decisions, which are also the defaults of ApproovServiceMutator) throw during one service mutator hook
 * call, so that the layer can tell them from an ApproovException the app's own hook code throws. Only the
 * token fetch decision and the refusal of a null request context throw. Each exception is recorded by
 * identity for the hook call running on the current thread, so a custom mutator that inherits or calls a
 * decision keeps its Approov form, and an app that catches a decision and throws it again throws the decision.
 */
final class StandardDecisions {
    // the decisions raised in the hook call running on this thread, or null outside a hook call
    private static final ThreadLocal<Set<Throwable>> CURRENT = new ThreadLocal<>();

    /**
     * Construction is disallowed as this is a static only class.
     */
    private StandardDecisions() {
    }

    // records an exception a standard decision is about to throw, and returns it
    static <E extends ApproovException> E made(E decision) {
        Set<Throwable> made = CURRENT.get();
        if (made != null)
            made.add(decision);
        return decision;
    }

    // returns the recording of any enclosing hook call, to be restored by end()
    static Set<Throwable> begin() {
        Set<Throwable> enclosing = CURRENT.get();
        CURRENT.set(Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>()));
        return enclosing;
    }

    // whether a standard decision raised the exception in the hook call running on this thread
    static boolean contains(Throwable e) {
        Set<Throwable> made = CURRENT.get();
        return (made != null) && made.contains(e);
    }

    static void end(Set<Throwable> enclosing) {
        if (enclosing == null)
            CURRENT.remove();
        else
            CURRENT.set(enclosing);
    }
}
