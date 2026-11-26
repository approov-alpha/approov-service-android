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

package io.approov.service.android.grpc;

import io.approov.service.android.ApproovLog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLPeerUnverifiedException;

import io.approov.service.android.ApproovException;
import io.approov.service.android.ApproovRequestContext;
import io.approov.service.android.ApproovService;
import io.grpc.CallCredentials;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.CompositeCallCredentials;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.SecurityLevel;
import io.grpc.Status;

/**
 * ApproovClientInterceptor is a gRPC ClientInterceptor that routes every call through the ApproovService
 * request pipeline, adding the Approov token to the call metadata (headers) for channels to hosts that are
 * configured to be Approov protected and performing any configured secure string metadata substitutions.
 * Note that an invocation of start may cause an Approov token to be fetched over the network.
 * <p>
 * A call the layer aborts, and a call it protected that is about to be sent over a channel without TLS
 * (usePlaintext), is failed by the transport before any of it is written, with Status UNAVAILABLE carrying
 * the cause (the abort, or an SSLPeerUnverifiedException for the cleartext channel).
 */
public class ApproovClientInterceptor implements ClientInterceptor {
    // logging tag
    private static final String TAG = "ApproovGrpc";

    // host for which to fetch Approov tokens
    private final String host;

    /**
     * Constructs a new interceptor that adds Approov tokens.
     *
     * @param host is the host for which to fetch the Approov token
     */
    public ApproovClientInterceptor(String host) {
        this.host = host;
    }

    /**
     * Constructs a new interceptor that adds Approov tokens.
     *
     * @param channel is the channel for which to fetch the Approov token
     */
    public ApproovClientInterceptor(Channel channel) {
        this(hostFromAuthority(channel.authority()));
    }

    // split at its delimiters rather than parsed as a URI, so no authority makes this throw
    static String hostFromAuthority(String authority) {
        if (authority == null)
            return null;

        // remove any user information
        int at = authority.lastIndexOf('@');
        String hostAndPort = (at < 0) ? authority : authority.substring(at + 1);

        // an IPv6 literal keeps its brackets, otherwise any port is removed
        if (hostAndPort.startsWith("[")) {
            int close = hostAndPort.indexOf(']');
            return (close < 0) ? hostAndPort : hostAndPort.substring(0, close + 1);
        }
        int colon = hostAndPort.lastIndexOf(':');
        return (colon < 0) ? hostAndPort : hostAndPort.substring(0, colon);
    }

    // as the backend rebuilds it from the HTTP/2 pseudo-headers, or null if the method or authority is unknown
    static String wireTargetUri(MethodDescriptor<?, ?> method, CallOptions callOptions, Channel next) {
        if (method == null)
            return null;

        // use the call's authority override, else the channel's
        String authority = (callOptions != null) ? callOptions.getAuthority() : null;
        if ((authority == null) && (next != null))
            authority = next.authority();
        if ((authority == null) || authority.isEmpty())
            return null;
        return "https://" + authority + "/" + method.getFullMethodName();
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions, Channel next) {
        // the guard runs as call credentials, composed with any the call already has, which the transport applies
        // after the call's headers are complete and before any is written, when it knows the transport's security
        TransportGuard guard = new TransportGuard(host);
        CallCredentials existing = callOptions.getCredentials();
        CallOptions guarded = callOptions.withCallCredentials(
                (existing == null) ? guard : new CompositeCallCredentials(existing, guard));
        return new ApproovClientCall<>(next.newCall(method, guarded),
                wireTargetUri(method, callOptions, next), guard);
    }

    /**
     * ApproovClientCall is a call to a remote method where the Approov pipeline is applied to the metadata
     * (headers) before the call is started.
     */
    private final class ApproovClientCall<ReqT, RespT> extends SimpleForwardingClientCall<ReqT, RespT> {
        // the target URI the call sends, or null if unknown
        private final String targetUri;
        // the guard that fails the stream if the call must not be sent
        private final TransportGuard guard;

        ApproovClientCall(ClientCall<ReqT, RespT> call, String targetUri, TransportGuard guard) {
            super(call);
            this.targetUri = targetUri;
            this.guard = guard;
        }

        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
            // run the Approov request pipeline on the call's metadata
            try {
                GrpcRequestContext context = new GrpcRequestContext(host, targetUri, headers);
                ApproovService.ApproovPreparedRequest prepared = ApproovService.prepareRequest(context);
                prepared.completeProcessing(context);
                // a processed call may carry a token, the status header or a secure string, none of which may
                // travel in cleartext
                guard.protectedCall = prepared.isProcessed();
            } catch (IOException e) {
                // the call must not proceed, but it is still started so that the app's listener is installed and
                // its later calls are legal, and the guard then fails the stream with UNAVAILABLE before anything
                // is written, as cancelling would let a ready transport write the headers
                guard.abort = e;
            }
            super.start(responseListener, headers);
        }
    }

    /**
     * TransportGuard is the call credentials applied by the transport when the call's stream is created, after
     * the interceptors have completed its headers and before any frame is written. They add no metadata, but
     * fail the stream when Approov processing aborted the call, or when a call that Approov processed is about
     * to go over a transport without TLS, which is reported as a pinning failure.
     */
    static final class TransportGuard extends CallCredentials {
        // the host for which the call is made
        private final String host;
        // whether the call was processed and any abort, set by start() before the transport applies the credentials
        volatile boolean protectedCall;
        volatile IOException abort;

        TransportGuard(String host) {
            this.host = host;
        }

        @Override
        public void applyRequestMetadata(RequestInfo requestInfo, Executor appExecutor,
                MetadataApplier applier) {
            // a processed call over a transport without TLS is a pinning failure
            IOException failure = abort;
            if ((failure == null) && protectedCall && (requestInfo.getSecurityLevel() == SecurityLevel.NONE)) {
                ApproovLog.w(TAG, "Approov pinning: cleartext connection to pinned host " + host);
                failure = new SSLPeerUnverifiedException("Approov pinning: cleartext connection to pinned host " + host);
            }
            // add no metadata if the call may proceed, otherwise fail the stream with the cause
            if (failure == null) {
                applier.apply(new Metadata());
                return;
            }
            applier.fail(Status.UNAVAILABLE.withDescription("Approov: " + failure.getMessage())
                    .withCause(failure));
        }

        @Override
        public void thisUsesUnstableApi() {
        }
    }

    /**
     * GrpcRequestContext adapts gRPC call Metadata to the stack-neutral ApproovRequestContext used by the
     * ApproovService pipeline. The URL is the https URL of the channel host, which the token fetch and the
     * mutator hooks are given, while message signing covers the target URI the call puts on the wire. URL
     * mutation and bodies are not supported.
     */
    static final class GrpcRequestContext implements ApproovRequestContext {
        // the host of the channel
        private final String host;
        // the target URI the call sends, or null if unknown
        private final String targetUri;
        // the call metadata
        private final Metadata headers;

        GrpcRequestContext(String host, Metadata headers) {
            this(host, null, headers);
        }

        GrpcRequestContext(String host, String targetUri, Metadata headers) {
            this.host = host;
            this.targetUri = targetUri;
            this.headers = headers;
        }

        private static Metadata.Key<String> key(String name) {
            return Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER);
        }

        @Override
        public String getMethod() {
            // gRPC over HTTP/2 always uses POST
            return "POST";
        }

        @Override
        public String getURL() {
            return "https://" + host + "/";
        }

        @Override
        public String getTargetURI() {
            return (targetUri != null) ? targetUri : getURL();
        }

        @Override
        public boolean supportsURLMutation() {
            return false;
        }

        @Override
        public void setURL(String url) throws ApproovException {
            throw new ApproovException("URL mutation is not supported for gRPC calls");
        }

        @Override
        public String getHeader(String name) {
            return headers.get(key(name));
        }

        @Override
        public List<String> getHeaders(String name) {
            Iterable<String> all = headers.getAll(key(name));
            if (all == null)
                return Collections.emptyList();
            List<String> values = new ArrayList<>();
            for (String value : all)
                values.add(value);
            return values;
        }

        @Override
        public void setHeader(String name, String value) {
            Metadata.Key<String> metadataKey = key(name);
            headers.discardAll(metadataKey);
            headers.put(metadataKey, value);
        }

        @Override
        public void removeHeader(String name) {
            headers.discardAll(key(name));
        }

        @Override
        public void addHeader(String name, String value) {
            headers.put(key(name), value);
        }

        @Override
        public boolean hasBody() {
            return false;
        }

        @Override
        public byte[] getBodyBytes() {
            return null;
        }
    }
}
