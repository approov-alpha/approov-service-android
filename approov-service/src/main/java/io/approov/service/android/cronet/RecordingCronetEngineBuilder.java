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

import org.chromium.net.ConnectionMigrationOptions;
import org.chromium.net.CronetEngine;
import org.chromium.net.DnsOptions;
import org.chromium.net.ExperimentalCronetEngine;
import org.chromium.net.ICronetEngineBuilder;
import org.chromium.net.ProxyOptions;
import org.chromium.net.QuicOptions;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * RecordingCronetEngineBuilder records what your app, or a library such as react-native-nitro-fetch, sets
 * on its Cronet engine builder, through Cronet's public CronetEngine.Builder(ICronetEngineBuilder) delegate
 * API, and replays it onto fresh real builders from a Fresh supplier. Every pinned engine generation is
 * built from such a fresh builder, so the pins of one generation never accumulate into the next.
 * <p>
 * As with a real builder, failures show at your app's own calls. One real builder is made when this one
 * is, so Cronet being unavailable fails there and an app's "try Cronet, else OkHttp" fallback still runs,
 * and each setting is applied to it as it is recorded, so a value the real builder refuses throws at that
 * call and is not recorded. That builder then serves the first generation. Settings are recorded on the
 * app's thread and generations are built later on Approov threads.
 */
@SuppressWarnings("deprecation") // ExperimentalCronetEngine is the type Cronet's delegate API returns
final class RecordingCronetEngineBuilder extends ICronetEngineBuilder {
    // Fresh supplies a new real builder
    interface Fresh {
        CronetEngine.Builder newBuilder();
    }

    // Op is one recorded setting, applied to a real builder
    private interface Op {
        void apply(CronetEngine.Builder builder);
    }

    // supplier of fresh real builders
    private final Fresh fresh;

    // lock guarding the call site builder and the recording
    private final Object lock = new Object();

    // the recorded settings, in your app's order
    private final List<Op> ops = new CopyOnWriteArrayList<>();

    // the storage path your app set, or null
    private volatile String storagePath;

    // the real builder made at your app's call site with every setting applied, taken by the first generation
    private CronetEngine.Builder callSiteBuilder;

    // the supplier is called once here, so a Cronet that cannot make a builder fails at your app's call site
    RecordingCronetEngineBuilder(Fresh fresh) {
        this.fresh = fresh;
        this.callSiteBuilder = fresh.newBuilder();
    }

    // applies every recorded setting, in your app's order
    CronetEngine.Builder newRealBuilder() {
        synchronized (lock) {
            // the first generation takes the call site builder, which already has every setting
            if (callSiteBuilder != null) {
                CronetEngine.Builder first = callSiteBuilder;
                callSiteBuilder = null;
                return first;
            }

            // later generations replay every setting onto a fresh builder
            CronetEngine.Builder builder = fresh.newBuilder();
            for (Op op : ops)
                op.apply(builder);
            return builder;
        }
    }

    String storagePath() {
        return storagePath;
    }

    // for tests and diagnostics
    int recordedCount() {
        return ops.size();
    }

    // applies to the call site builder first, so a refused value throws there, then records it
    private ICronetEngineBuilder rec(Op op) {
        synchronized (lock) {
            if (callSiteBuilder != null)
                op.apply(callSiteBuilder);
            ops.add(op);
        }
        return this;
    }

    @Override
    public ICronetEngineBuilder addPublicKeyPins(final String hostName, final Set<byte[]> pinsSha256,
            final boolean includeSubdomains, final Date expirationDate) {
        final Set<byte[]> copy = new HashSet<>(pinsSha256);
        return rec(b -> b.addPublicKeyPins(hostName, copy, includeSubdomains, expirationDate));
    }

    @Override
    public ICronetEngineBuilder addQuicHint(final String host, final int port, final int alternatePort) {
        return rec(b -> b.addQuicHint(host, port, alternatePort));
    }

    @Override
    public ICronetEngineBuilder enableHttp2(final boolean value) {
        return rec(b -> b.enableHttp2(value));
    }

    @Override
    public ICronetEngineBuilder enableHttpCache(final int cacheMode, final long maxSize) {
        return rec(b -> b.enableHttpCache(cacheMode, maxSize));
    }

    @Override
    public ICronetEngineBuilder enablePublicKeyPinningBypassForLocalTrustAnchors(final boolean value) {
        // replayed, then overridden: every generation turns the bypass off
        return rec(b -> b.enablePublicKeyPinningBypassForLocalTrustAnchors(value));
    }

    @Override
    public ICronetEngineBuilder enableQuic(final boolean value) {
        return rec(b -> b.enableQuic(value));
    }

    @Override
    public ICronetEngineBuilder enableSdch(final boolean value) {
        return rec(b -> b.enableSdch(value));
    }

    @Override
    public ICronetEngineBuilder enableBrotli(final boolean value) {
        return rec(b -> b.enableBrotli(value));
    }

    @Override
    public ICronetEngineBuilder setQuicOptions(final QuicOptions options) {
        return rec(b -> b.setQuicOptions(options));
    }

    @Override
    public ICronetEngineBuilder setDnsOptions(final DnsOptions options) {
        return rec(b -> b.setDnsOptions(options));
    }

    @Override
    public ICronetEngineBuilder setConnectionMigrationOptions(final ConnectionMigrationOptions options) {
        return rec(b -> b.setConnectionMigrationOptions(options));
    }

    @Override
    public ICronetEngineBuilder setProxyOptions(final ProxyOptions options) {
        return rec(b -> b.setProxyOptions(options));
    }

    @Override
    public ICronetEngineBuilder setExperimentalOptions(final String options) {
        return rec(b -> {
            if (!(b instanceof ExperimentalCronetEngine.Builder))
                throw new IllegalStateException("setExperimentalOptions: not an ExperimentalCronetEngine.Builder: "
                        + b.getClass().getName());
            ((ExperimentalCronetEngine.Builder) b).setExperimentalOptions(options);
        });
    }

    @Override
    public ICronetEngineBuilder setLibraryLoader(final CronetEngine.Builder.LibraryLoader loader) {
        return rec(b -> b.setLibraryLoader(loader));
    }

    @Override
    public ICronetEngineBuilder setStoragePath(final String value) {
        rec(b -> b.setStoragePath(value));
        storagePath = value;
        return this;
    }

    @Override
    public ICronetEngineBuilder setUserAgent(final String userAgent) {
        return rec(b -> b.setUserAgent(userAgent));
    }

    @Override
    public ICronetEngineBuilder enableNetworkQualityEstimator(final boolean value) {
        return rec(b -> b.enableNetworkQualityEstimator(value));
    }

    @Override
    public ICronetEngineBuilder setThreadPriority(final int priority) {
        return rec(b -> b.setThreadPriority(priority));
    }

    @Override
    public String getDefaultUserAgent() {
        synchronized (lock) {
            return ((callSiteBuilder != null) ? callSiteBuilder : fresh.newBuilder()).getDefaultUserAgent();
        }
    }

    /**
     * Gets the supported option groups. Every group is taken directly, so CronetEngine.Builder forwards
     * setQuicOptions() and the like here rather than turning them into an experimental options patch,
     * which it would only apply in its own build(), never called as ApproovCronetEngineBuilder overrides it.
     */
    @Override
    protected Set<Integer> getSupportedConfigOptions() {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                CONNECTION_MIGRATION_OPTIONS, DNS_OPTIONS, QUIC_OPTIONS, PROXY_OPTIONS)));
    }

    /**
     * Builds an engine, which is unreachable as ApproovCronetEngineBuilder.build() never calls its delegate's
     * build().
     */
    @Override
    public ExperimentalCronetEngine build() {
        throw new IllegalStateException("build: use ApproovCronetEngineBuilder.build()");
    }
}
