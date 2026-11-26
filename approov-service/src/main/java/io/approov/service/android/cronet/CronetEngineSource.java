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

import org.chromium.net.CronetEngine;

import java.io.IOException;

import javax.net.ssl.SSLPeerUnverifiedException;

// CronetEngineSource provides the real Cronet engine a request is sent on
interface CronetEngineSource {
    // Lease is a request's hold on one pinned generation, from the moment the engine is chosen until its real
    // request has started or the attempt is abandoned, during which the generation is not shut down by a
    // pin change or an engine shutdown() on another thread
    interface Lease {
        CronetEngine engine();

        // cleartext to a pinned host, or any request to one on the Java fallback engine, fails as a pinning failure
        void checkConnection(String scheme, String host) throws SSLPeerUnverifiedException;

        // may be called more than once
        void release();
    }

    // may build a new generation, so the request path calls it on an Approov thread
    Lease acquire() throws IOException;

    // no hold on the generation; runs on the caller's thread and may build the first generation, and throws
    // IllegalStateException after shutdown()
    CronetEngine current() throws IOException;

    // true once shutdown() has been called
    boolean isShutDown();

    // binds the current generation and every later one to the network, as CronetEngine.bindToNetwork()
    void bindToNetwork(long networkHandle);

    void shutdown();
}
