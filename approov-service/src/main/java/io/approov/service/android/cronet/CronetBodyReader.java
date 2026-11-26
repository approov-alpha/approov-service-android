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

import org.chromium.net.UploadDataProvider;
import org.chromium.net.UploadDataSink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * CronetBodyReader reads an UploadDataProvider to the end, once, so the body can be resent on a redirect
 * and covered by a message signature's Content-Digest. Note that the whole body is buffered in memory,
 * whereas Cronet itself streams it, and each chunk read must complete within 30 seconds.
 */
final class CronetBodyReader {
    // size of each chunk read from the provider
    private static final int CHUNK = 16 * 1024;

    // time limit for each chunk read
    private static final long READ_TIMEOUT_SECONDS = 30;

    /**
     * Construction is disallowed as this is a static only class.
     */
    private CronetBodyReader() {
    }

    // blocks the Approov thread while the reads run on your app's executor; the caller closes the provider
    static byte[] readAll(final UploadDataProvider provider, Executor executor) throws IOException {
        // size the buffer from the length the provider reports, if any
        long length = provider.getLength();
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                ((length >= 0) && (length <= Integer.MAX_VALUE)) ? (int) length : CHUNK);
        final ByteBuffer buffer = ByteBuffer.allocateDirect(CHUNK);
        // read chunk by chunk until the reported length or, if there is none, the final chunk
        while ((length < 0) || (out.size() < length)) {
            buffer.clear();
            final BlockingSink sink = new BlockingSink();
            executor.execute(() -> {
                try {
                    provider.read(sink, buffer);
                } catch (Exception e) {
                    sink.onReadError(e);
                }
            });
            boolean finalChunk = sink.await();
            buffer.flip();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            out.write(bytes);
            if ((length < 0) && finalChunk)
                break;
            if ((length >= 0) && (bytes.length == 0))
                throw new IOException("upload body ended after " + out.size() + " of " + length + " bytes");
        }
        return out.toByteArray();
    }

    // BlockingSink receives the result of one read from the provider
    private static final class BlockingSink extends UploadDataSink {
        // released once the read completes or fails
        private final CountDownLatch done = new CountDownLatch(1);

        // true if the read delivered the final chunk
        private volatile boolean finalChunk;

        // the read error, or null if there was none
        private volatile Exception error;

        @Override
        public void onReadSucceeded(boolean finalChunk) {
            this.finalChunk = finalChunk;
            done.countDown();
        }

        @Override
        public void onReadError(Exception exception) {
            error = exception;
            done.countDown();
        }

        @Override
        public void onRewindSucceeded() {
            done.countDown();
        }

        @Override
        public void onRewindError(Exception exception) {
            onReadError(exception);
        }

        // true if the read delivered the final chunk
        boolean await() throws IOException {
            try {
                if (!done.await(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    throw new IOException("upload body read timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("upload body read interrupted", e);
            }
            if (error != null)
                throw new IOException("upload body read failed", error);
            return finalChunk;
        }
    }
}
