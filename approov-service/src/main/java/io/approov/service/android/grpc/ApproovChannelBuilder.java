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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;

import io.approov.service.android.ApproovPinningVerifier;
import io.approov.service.android.ApproovService;
import io.grpc.ChannelCredentials;
import io.grpc.ForwardingChannelBuilder2;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.okhttp.OkHttpChannelBuilder;

/**
 * ApproovChannelBuilder is the gRPC adapter of the Approov service layer. It builds ManagedChannels whose TLS
 * connections are pinned with the Approov dynamic pins, read live on each verification. Add an
 * ApproovClientInterceptor to also add Approov tokens, and secure string metadata substitutions, to calls
 * made on the channel.
 * <p>
 * Every ManagedChannelBuilder configuration method (such as usePlaintext, keepAliveTime or enableRetry) is
 * forwarded to the underlying OkHttp channel builder.
 */
public class ApproovChannelBuilder extends ForwardingChannelBuilder2<ApproovChannelBuilder> {
    // logging tag
    private static final String TAG = "ApproovGrpc";

    // the channels built here, held weakly, which are moved to IDLE whenever Approov protection is enabled or
    // the pins change, as the pins are checked only at the TLS handshake, so that the next call opens, and
    // pins, a new connection while calls in flight finish on the existing one
    private static final Map<ManagedChannel, Boolean> channels = new WeakHashMap<>();

    // true once the builder listens for pins changes
    private static boolean listeningForPinsChanges = false;

    // the underlying OkHttp channel builder with the pinning verifier installed
    private final OkHttpChannelBuilder channelBuilder;

    /**
     * Constructs a channel builder for the given target with Approov pinning.
     *
     * @param target is the target authority
     */
    protected ApproovChannelBuilder(String target) {
        channelBuilder = OkHttpChannelBuilder.forTarget(target);
        channelBuilder.hostnameVerifier(createPinningVerifier());
    }

    /**
     * Constructs a channel builder for the given target and credentials with Approov pinning. Note that
     * grpc-okhttp does not allow a HostnameVerifier to be set with ChannelCredentials, so this fails rather
     * than build a channel without dynamic pinning.
     *
     * @param target is the target authority
     * @param creds is the channel credentials
     * @throws IllegalArgumentException if the dynamic pinning cannot be installed with the credentials
     */
    protected ApproovChannelBuilder(String target, ChannelCredentials creds) {
        channelBuilder = OkHttpChannelBuilder.forTarget(target, creds);
        try {
            channelBuilder.hostnameVerifier(createPinningVerifier());
        } catch (IllegalStateException exception) {
            // grpc-okhttp freezes the transport security settings, the HostnameVerifier included, when
            // ChannelCredentials are supplied, so we fail rather than silently disable dynamic pinning
            throw new IllegalArgumentException(
                    "ApproovChannelBuilder: dynamic pinning cannot be installed with ChannelCredentials, "
                            + "use forTarget(String) or forAddress(String, int)",
                    exception);
        }
    }

    private static HostnameVerifier createPinningVerifier() {
        return new ApproovPinningVerifier(HttpsURLConnection.getDefaultHostnameVerifier());
    }

    /**
     * Creates a channel builder for the given host and port with Approov pinning.
     *
     * @param host is the host name
     * @param port is the port
     * @return the channel builder
     */
    public static ApproovChannelBuilder forAddress(String host, int port) {
        return new ApproovChannelBuilder(host + ":" + port);
    }

    /**
     * Creates a channel builder for the given target with Approov pinning.
     *
     * @param target is the target authority
     * @return the channel builder
     */
    public static ApproovChannelBuilder forTarget(String target) {
        return new ApproovChannelBuilder(target);
    }

    /**
     * Creates a channel builder for the given target and credentials with Approov pinning.
     *
     * @param target is the target authority
     * @param creds is the channel credentials
     * @return the channel builder
     * @throws IllegalArgumentException if the dynamic pinning cannot be installed with the credentials
     */
    public static ApproovChannelBuilder forTarget(String target, ChannelCredentials creds) {
        return new ApproovChannelBuilder(target, creds);
    }

    /**
     * Builds the channel. It is moved to IDLE whenever Approov protection is enabled or the pins change, so
     * that a new call never uses a connection that was not verified against the current pins.
     *
     * @return the channel
     */
    @Override
    public ManagedChannel build() {
        ManagedChannel channel = super.build();
        register(channel);
        return channel;
    }

    private static synchronized void register(ManagedChannel channel) {
        // listen for pins changes once, and keep the channel so that it can be moved to IDLE
        if (!listeningForPinsChanges) {
            listeningForPinsChanges = true;
            ApproovService.registerPinsChangeListener(ApproovChannelBuilder::pinsChanged);
        }
        channels.put(channel, Boolean.TRUE);
    }

    // moves every live channel to IDLE, so the next call makes a new, pinned connection
    private static void pinsChanged() {
        // take a copy of the channels so that they are moved to IDLE outside the lock
        List<ManagedChannel> current;
        synchronized (ApproovChannelBuilder.class) {
            current = new ArrayList<>(channels.keySet());
        }
        for (ManagedChannel channel : current) {
            if (channel.isShutdown())
                continue;
            try {
                channel.enterIdle();
            } catch (RuntimeException e) {
                ApproovLog.w(TAG, "gRPC channel not moved to idle after a pins change: " + e);
            }
        }
    }

    static synchronized void resetForTesting() {
        channels.clear();
        listeningForPinsChanges = false;
    }

    /**
     * Uses a plaintext connection, as OkHttpChannelBuilder.usePlaintext() does. Such a channel has no TLS and
     * so no pinning, and a call that the ApproovClientInterceptor protects fails with Status UNAVAILABLE,
     * caused by an SSLPeerUnverifiedException, before it is sent, so that an Approov token, signature or secure
     * string never travels in cleartext. Calls Approov does not process are left to the app.
     *
     * @return this builder
     */
    @Override
    public ApproovChannelBuilder usePlaintext() {
        ApproovLog.w(TAG, "Plaintext gRPC channel: no pinning, and calls Approov protects will fail");
        return super.usePlaintext();
    }

    /**
     * Gets the underlying OkHttp channel builder, with the pinning HostnameVerifier installed, to which all
     * ManagedChannelBuilder configuration is forwarded.
     *
     * @return the delegate channel builder
     */
    @Override
    protected ManagedChannelBuilder<?> delegate() {
        return channelBuilder;
    }
}
