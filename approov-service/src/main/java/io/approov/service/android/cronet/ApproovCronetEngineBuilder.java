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

import android.content.Context;

import org.chromium.net.CronetEngine;
import org.chromium.net.ExperimentalCronetEngine;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * ApproovCronetEngineBuilder is a Cronet engine builder whose engine is protected by Approov. It is
 * what the Approov Gradle plugin io.approov.gradle weaves in place of new CronetEngine.Builder(context)
 * and, through ApproovCronetWeaver.createBuilder(), of provider.createBuilder(). It is also what
 * ApproovCronet.newBuilder() returns.
 * <p>
 * Settings chained on it (HTTP/2, QUIC, Brotli, disk cache, user agent and so on) are only recorded.
 * build() returns an ApproovCronetEngine whose requests go through the ApproovService pipeline, and
 * whose real engines, one per Approov pin set, are each built from a fresh real builder with the
 * recorded settings replayed and the Approov pins added. One real builder is made where your app makes
 * this one, so Cronet being unavailable or a refused setting fails at your app's own call. No real
 * engine is built until the first request, so build() is cheap on any thread. It is an
 * ExperimentalCronetEngine.Builder and builds an ExperimentalCronetEngine, as real Cronet's builders
 * do, so a cast of the builder or the engine to the Experimental types stays legal.
 */
@SuppressWarnings("deprecation") // ExperimentalCronetEngine.Builder takes experimental options
public final class ApproovCronetEngineBuilder extends ExperimentalCronetEngine.Builder {
    // last storage index used for each storage root name, so each engine gets its own directory
    private static final Map<String, Integer> ROOT_INDEX = new HashMap<>();

    // the builder recording the settings chained on this one
    private final RecordingCronetEngineBuilder recording;

    // the woven call site, used for diagnostics and storage names
    private final String site;

    // the app's cache directory, or null if not known
    private final File cacheDir;

    /**
     * Constructs a new builder, woven in place of new CronetEngine.Builder(context). Each fresh builder
     * picks its provider as that constructor does. Note that only the application context is kept, as
     * the engine lives as long as the app holds it and the context passed is often an Activity.
     *
     * @param context is the context your app gave its builder
     * @param site is the woven call site, as "dotted.Class.method", for diagnostics
     */
    public ApproovCronetEngineBuilder(Context context, String site) {
        this(freshBuilders(applicationContextOf(context)), site, cacheDirOf(context));
    }

    // ContextBuilders makes a real builder from a context, as new CronetEngine.Builder(context) does
    interface ContextBuilders {
        CronetEngine.Builder newBuilder(Context context);
    }

    // maker of real builders from a context, replaced in JVM tests
    static volatile ContextBuilders contextBuilders = ExperimentalCronetEngine.Builder::new;

    private static RecordingCronetEngineBuilder freshBuilders(final Context context) {
        final ContextBuilders builders = contextBuilders;
        return new RecordingCronetEngineBuilder(() -> builders.newBuilder(context));
    }

    static Context applicationContextOf(Context context) {
        if (context == null)
            return null;
        try {
            Context application = context.getApplicationContext();
            return (application != null) ? application : context;
        } catch (RuntimeException e) {
            return context;
        }
    }

    ApproovCronetEngineBuilder(RecordingCronetEngineBuilder recording, String site, File cacheDir) {
        super(recording);
        this.recording = recording;
        this.site = site;
        this.cacheDir = cacheDir;
    }

    /**
     * Builds the Approov engine. No real engine is built here, as the first generation is built for the
     * first request, after its token fetch.
     *
     * @return an engine whose requests are protected by Approov
     */
    @Override
    public ExperimentalCronetEngine build() {
        return ApproovCronetEngine.create(purposeOf(site), recording, storageRoot());
    }

    // for tests only
    RecordingCronetEngineBuilder recording() {
        return recording;
    }

    // each engine gets its own directory, named after the call site and its order, so no disk cache is shared
    File storageRoot() {
        // choose the base directory
        File base;
        if (recording.storagePath() != null)
            base = new File(recording.storagePath());
        else if (cacheDir != null)
            base = cacheDir;
        else
            base = new File(System.getProperty("java.io.tmpdir", "."));
        // number the directory by its order of creation for this name
        String name = "approov_cronet_" + purposeOf(site).replaceAll("[^A-Za-z0-9_]", "_");
        int index;
        synchronized (ROOT_INDEX) {
            String key = base.getAbsolutePath() + "/" + name;
            Integer last = ROOT_INDEX.get(key);
            index = (last == null) ? 1 : (last + 1);
            ROOT_INDEX.put(key, index);
        }
        return new File(base, name + "_" + index);
    }

    private static File cacheDirOf(Context context) {
        try {
            return (context == null) ? null : context.getCacheDir();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // "pkg.HybridNitroFetch$Companion.getEngine" becomes "HybridNitroFetch.getEngine", or "engine" without a site
    static String purposeOf(String site) {
        if ((site == null) || site.isEmpty())
            return "engine";
        int dot = site.lastIndexOf('.');
        if (dot < 0)
            return site;
        String method = site.substring(dot + 1);
        String cls = site.substring(0, dot);
        cls = cls.substring(cls.lastIndexOf('.') + 1);
        if (cls.endsWith("$Companion"))
            cls = cls.substring(0, cls.length() - "$Companion".length());
        return cls + "." + method;
    }
}
