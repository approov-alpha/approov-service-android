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

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CronetWeaveCheck logs an error, once per process, if react-native-nitro-fetch is in the app but the
 * Approov Gradle plugin io.approov.gradle has not woven it, since its requests then have no Approov
 * protection and nothing else would show it. It also reports any stored nitro-fetch prefetch on app start
 * or token refresh configuration, which bypasses the layer. Only nitro-fetch is checked, not other
 * libraries that use Cronet.
 * <p>
 * The plugin adds a string resource, kept through resource shrinking, whose value lists the dependency
 * packages it wove (such as "2;packages=a.b,c.d"). The check looks up the resource by name, requires the
 * nitro-fetch package (or a parent) in that list and probes for nitro-fetch by class name without loading
 * any Cronet class. It runs once per process from the first successful initialize, which it never fails, on
 * a thread of its own when nitro-fetch is present, so that no storage is read on the initializing thread.
 * The marker is looked up under the application ID and, where that differs, under the namespace.
 */
final class CronetWeaveCheck {
    // logging tag
    private static final String TAG = "ApproovService";

    // the string resource the Gradle plugin generates in ApproovWeaveMarkerTask
    static final String MARKER_RESOURCE = "approov_cronet_weave";

    // the react-native-nitro-fetch class probed for, its Cronet engine holder in 1.7.0 and 1.8.0
    static final String NITRO_FETCH_CLASS = "com.margelo.nitro.nitrofetch.HybridNitroFetch";

    // the prefix of the list of woven dependency packages in the marker value
    static final String PACKAGES_PREFIX = "packages=";

    // the SharedPreferences of nitro-fetch (NitroFetchSecureAtRest.PREFS_NAME in nitro-fetch 1.7.0)
    static final String NITRO_STORAGE = "nitro_fetch_storage";

    // the start-up features nitro-fetch stores, each as its storage key and the feature name for the error
    static final String[][] NITRO_FEATURES = {
            {"nitrofetch_autoprefetch_queue", "prefetch on app start"},
            {"nitro_token_refresh_fetch", "token refresh (fetch)"},
            {"nitro_token_refresh_websocket", "token refresh (websocket)"},
    };

    // ClassPresence determines whether a class is in the app, without initializing it
    interface ClassPresence {
        boolean isPresent(String className);
    }

    // ErrorLog writes an error line, using ApproovLog.e() in production
    interface ErrorLog {
        void error(String message);
    }

    // runs the check on a thread of its own, as initialize is often called on the main thread, where StrictMode
    // reports the read of nitro-fetch's SharedPreferences
    static final Executor BACKGROUND = task -> {
        Thread thread = new Thread(task, "ApproovWeaveCheck");
        thread.setDaemon(true);
        thread.start();
    };

    // true once the check has run in this process
    private static final AtomicBoolean CHECKED = new AtomicBoolean();

    /**
     * Construction is disallowed as this is a static only class.
     */
    private CronetWeaveCheck() {
    }

    // once per process, never throws
    static void atInitialize(Context context) {
        if (!CHECKED.compareAndSet(false, true))
            return;
        // the check thread holds the application context, never an Activity the app initialized with
        Context app = context.getApplicationContext();
        run((app != null) ? app : context, className -> {
            try {
                Class.forName(className, false, CronetWeaveCheck.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
        }, message -> ApproovLog.e(TAG, message), BACKGROUND);
    }

    /**
     * Runs the check on the executor if nitro-fetch is in the app, which is probed on the calling thread.
     *
     * @param context is the context the app initialized with
     * @param classes determines which classes are in the app
     * @param log receives the errors
     * @param executor runs the check
     */
    static void run(Context context, ClassPresence classes, ErrorLog log, Executor executor) {
        try {
            if (!classes.isPresent(NITRO_FETCH_CLASS))
                return;
            executor.execute(() -> {
                try {
                    check(context, classes, log);
                } catch (RuntimeException e) {
                    ApproovLog.w(TAG, "Cronet weave check: " + e.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException e) {
            ApproovLog.w(TAG, "Cronet weave check: " + e.getClass().getSimpleName());
        }
    }

    // for tests only
    static void resetForTesting() {
        CHECKED.set(false);
    }

    static void check(Context context, ClassPresence classes, ErrorLog log) {
        // we only check an app that has nitro-fetch in it
        boolean nitro = classes.isPresent(NITRO_FETCH_CLASS);
        if (!nitro)
            return;

        // report if the plugin did not weave nitro-fetch
        String marker = marker(context);
        if (marker == null) {
            log.error("react-native-nitro-fetch not woven, resource " + MARKER_RESOURCE + " missing: requests are "
                    + "NOT protected by Approov, apply io.approov.gradle in the application module");
        } else if (!wovePackageOf(marker, NITRO_FETCH_CLASS)) {
            log.error("react-native-nitro-fetch not woven, package not in cronetDependencyPackages (" + marker + "): "
                    + "requests are NOT protected by Approov, add com.margelo.nitro.nitrofetch");
        }

        // report any stored nitro-fetch features that bypass the layer
        List<String> stored = storedNitroFeatures(context);
        if (!stored.isEmpty()) {
            StringBuilder features = new StringBuilder();
            for (String feature : stored) {
                if (features.length() > 0)
                    features.append(", ");
                features.append(feature);
            }
            log.error("react-native-nitro-fetch has " + features + " configured: not supported by Approov as "
                    + "these requests can go out with no Approov token or pinning, clear them with "
                    + "removeAllFromAutoprefetch() and clearTokenRefresh()");
        }
    }

    // "" if it has none, or null if the plugin added no marker
    private static String marker(Context context) {
        Resources resources = context.getResources();
        if (resources == null)
            return null;
        int id = 0;
        for (String packageName : resourcePackages(context, resources)) {
            id = resources.getIdentifier(MARKER_RESOURCE, "string", packageName);
            if (id != 0)
                break;
        }
        if (id == 0)
            return null;
        String value = resources.getString(id);
        return (value == null) ? "" : value;
    }

    // the application ID first, then the package of the app's own resources, which is the namespace when the
    // application ID differs from it, as read from the resources the manifest names
    private static Set<String> resourcePackages(Context context, Resources resources) {
        Set<String> packages = new LinkedHashSet<>();
        if (context.getPackageName() != null)
            packages.add(context.getPackageName());
        ApplicationInfo info = context.getApplicationInfo();
        if (info == null)
            return packages;
        for (int id : new int[] {info.labelRes, info.icon, info.theme}) {
            if (id == 0)
                continue;
            try {
                String packageName = resources.getResourcePackageName(id);
                if (packageName != null)
                    packages.add(packageName);
            } catch (Resources.NotFoundException e) {
                // not one of the app's resources
            }
        }
        return packages;
    }

    // true if the marker lists the package of the class, or a parent of it
    static boolean wovePackageOf(String marker, String className) {
        // a marker with only a version is from an earlier plugin, which always wove nitro-fetch unless told not to
        int at = marker.indexOf(PACKAGES_PREFIX);
        if (at < 0)
            return true;
        for (String p : marker.substring(at + PACKAGES_PREFIX.length()).split(",")) {
            String pkg = p.trim();
            if (!pkg.isEmpty() && className.startsWith(pkg + "."))
                return true;
        }
        return false;
    }

    // only the presence of a key is checked; no value is ever read, decrypted or changed
    private static List<String> storedNitroFeatures(Context context) {
        List<String> stored = new ArrayList<>();
        SharedPreferences prefs = context.getSharedPreferences(NITRO_STORAGE, Context.MODE_PRIVATE);
        if (prefs == null)
            return stored;
        for (String[] feature : NITRO_FEATURES) {
            if (prefs.contains(feature[0]))
                stored.add(feature[1]);
        }
        return stored;
    }
}
