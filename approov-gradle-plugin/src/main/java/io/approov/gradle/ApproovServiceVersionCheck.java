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
package io.approov.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ComponentSelector;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * ApproovServiceVersionCheck checks the io.approov:service.android runtime that the plugin weaves calls to. The
 * plugin and the library ship as one release with one version, so the build fails when nothing on the app's
 * runtime classpath provides the runtime, as the woven call sites would end in NoClassDefFoundError at startup,
 * or when the library's version differs from the plugin's own. The runtime is provided by the
 * io.approov:service.android module, by a project of the build that is the library (this repository's sample
 * apps, or a composite build substituting it), or by a local .aar or .jar file dependency that contains the
 * weaver classes. Old service layer coordinates do not provide it.
 */
final class ApproovServiceVersionCheck {
    // the coordinates of the library and the plugin
    static final String LIBRARY_GROUP = "io.approov";
    static final String LIBRARY_NAME = "service.android";
    static final String LIBRARY = LIBRARY_GROUP + ":" + LIBRARY_NAME;
    static final String PLUGIN = LIBRARY_GROUP + ":service.android-gradle-plugin";

    // the library's project name in this repository
    static final String LIBRARY_PROJECT_NAME = "approov-service";

    // the property that turns a failure into a warning, for development only
    static final String ALLOW_PROPERTY = "approov.allowVersionMismatch";

    // a class every woven OkHttp call site links against, which ships with the whole runtime
    static final String WEAVER_ENTRY = "io/approov/service/android/okhttp/ApproovOkHttpWeaver.class";

    private ApproovServiceVersionCheck() {
    }

    // null if run from a class directory
    static String pluginVersion() {
        Package pkg = ApproovServiceVersionCheck.class.getPackage();
        return (pkg == null) ? null : pkg.getImplementationVersion();
    }

    // Visitor collects what one walk of a resolved graph finds
    private interface Visitor {
        void component(ResolvedComponentResult component, boolean isRoot);

        void unresolved(ModuleComponentSelector requested);
    }

    private static void walk(ResolvedComponentResult root, Visitor visitor) {
        Set<ResolvedComponentResult> seen = new HashSet<>();
        List<ResolvedComponentResult> todo = new ArrayList<>();
        todo.add(root);
        // visit each component once, and report each unresolved module dependency
        while (!todo.isEmpty()) {
            ResolvedComponentResult component = todo.remove(todo.size() - 1);
            if (!seen.add(component))
                continue;
            visitor.component(component, component == root);
            for (DependencyResult dependency : component.getDependencies()) {
                if (dependency instanceof ResolvedDependencyResult) {
                    todo.add(((ResolvedDependencyResult) dependency).getSelected());
                } else if (dependency instanceof UnresolvedDependencyResult) {
                    ComponentSelector requested = ((UnresolvedDependencyResult) dependency).getRequested();
                    if (requested instanceof ModuleComponentSelector)
                        visitor.unresolved((ModuleComponentSelector) requested);
                }
            }
        }
    }

    // project and included build components are not module versions, libraryProjects() finds those
    static List<String> libraryVersions(ResolvedComponentResult root) {
        final Set<String> versions = new TreeSet<>();
        walk(root, new Visitor() {
            @Override
            public void component(ResolvedComponentResult component, boolean isRoot) {
                if (component.getId() instanceof ModuleComponentIdentifier) {
                    ModuleComponentIdentifier id = (ModuleComponentIdentifier) component.getId();
                    if (LIBRARY_GROUP.equals(id.getGroup()) && LIBRARY_NAME.equals(id.getModule()))
                        versions.add(id.getVersion());
                }
            }

            @Override
            public void unresolved(ModuleComponentSelector requested) {
            }
        });
        return new ArrayList<>(versions);
    }

    // Cronet's Java fallback engine (JavaCronetEngine), which accepts pins but never checks them
    static final String CRONET_FALLBACK = "org.chromium.net:cronet-fallback";

    // distinct "group:name:version" coordinates, sorted, or empty if absent
    static List<String> cronetFallback(ResolvedComponentResult root) {
        final Set<String> found = new TreeSet<>();
        walk(root, new Visitor() {
            @Override
            public void component(ResolvedComponentResult component, boolean isRoot) {
                if (component.getId() instanceof ModuleComponentIdentifier) {
                    ModuleComponentIdentifier id = (ModuleComponentIdentifier) component.getId();
                    if (CRONET_FALLBACK.equals(id.getGroup() + ":" + id.getModule()))
                        found.add(CRONET_FALLBACK + ":" + id.getVersion());
                }
            }

            @Override
            public void unresolved(ModuleComponentSelector requested) {
            }
        });
        return new ArrayList<>(found);
    }

    // requested versions that could not be resolved, such as one that does not exist
    static List<String> unresolvedLibraryVersions(ResolvedComponentResult root) {
        final Set<String> versions = new TreeSet<>();
        walk(root, new Visitor() {
            @Override
            public void component(ResolvedComponentResult component, boolean isRoot) {
            }

            @Override
            public void unresolved(ModuleComponentSelector module) {
                if (LIBRARY_GROUP.equals(module.getGroup()) && LIBRARY_NAME.equals(module.getModule()))
                    versions.add(module.getVersion());
            }
        });
        return new ArrayList<>(versions);
    }

    // built from the same source as the plugin, so their version is not compared; the app itself never counts
    static List<String> libraryProjects(ResolvedComponentResult root) {
        final Set<String> projects = new TreeSet<>();
        walk(root, new Visitor() {
            @Override
            public void component(ResolvedComponentResult component, boolean isRoot) {
                if (isRoot || !(component.getId() instanceof ProjectComponentIdentifier))
                    return;
                ModuleVersionIdentifier module = component.getModuleVersion();
                if ((module == null) || !LIBRARY_GROUP.equals(module.getGroup())
                        || !(LIBRARY_PROJECT_NAME.equals(module.getName()) || LIBRARY_NAME.equals(module.getName())))
                    return;
                String path = ((ProjectComponentIdentifier) component.getId()).getProjectPath();
                projects.add(((path == null) || path.equals(":")) ? (":" + module.getName()) : path);
            }

            @Override
            public void unresolved(ModuleComponentSelector requested) {
            }
        });
        return new ArrayList<>(projects);
    }

    // per-stack layers, the old approov-service-android name and JitPack builds, named in the failure message
    static List<String> oldCoordinates(ResolvedComponentResult root) {
        final Set<String> found = new TreeSet<>();
        walk(root, new Visitor() {
            @Override
            public void component(ResolvedComponentResult component, boolean isRoot) {
                if (!(component.getId() instanceof ModuleComponentIdentifier))
                    return;
                ModuleComponentIdentifier id = (ModuleComponentIdentifier) component.getId();
                boolean approovGroup = LIBRARY_GROUP.equals(id.getGroup()) || "com.github.approov".equals(id.getGroup());
                if (approovGroup && id.getModule().startsWith("approov-service"))
                    found.add(id.getGroup() + ":" + id.getModule() + ":" + id.getVersion());
            }

            @Override
            public void unresolved(ModuleComponentSelector requested) {
            }
        });
        return new ArrayList<>(found);
    }

    // a .jar holding the weaver entry class, or an .aar whose classes.jar or libs/*.jar does
    static List<String> localFilesWithRuntime(Collection<File> files) {
        List<String> found = new ArrayList<>();
        for (File f : files) {
            if (f.isFile() && hasWeaver(f))
                found.add(f.getName());
        }
        Collections.sort(found);
        return found;
    }

    private static boolean hasWeaver(File f) {
        try (ZipFile zip = new ZipFile(f)) {
            if (zip.getEntry(WEAVER_ENTRY) != null)
                return true;

            // an .aar holds its classes in classes.jar or libs/*.jar
            for (ZipEntry entry : Collections.list(zip.entries())) {
                String name = entry.getName();
                if (name.equals("classes.jar") || (name.startsWith("libs/") && name.endsWith(".jar"))) {
                    try (InputStream in = zip.getInputStream(entry); ZipInputStream inner = new ZipInputStream(in)) {
                        for (ZipEntry e = inner.getNextEntry(); e != null; e = inner.getNextEntry()) {
                            if (e.getName().equals(WEAVER_ENTRY))
                                return true;
                        }
                    }
                }
            }
            return false;
        } catch (IOException e) {
            // a file that cannot be read does not provide the runtime
            return false;
        }
    }

    // only the io.approov:service.android module provides the runtime and nothing is unresolved
    static String verify(String pluginVersion, Collection<String> resolved, boolean allowMismatch, String where) {
        return verify(pluginVersion, resolved, Collections.<String>emptyList(), allowMismatch, where);
    }

    // only the io.approov:service.android module provides the runtime
    static String verify(String pluginVersion, Collection<String> resolved, Collection<String> unresolved,
            boolean allowMismatch, String where) {
        return verify(pluginVersion, resolved, unresolved, Collections.<String>emptyList(),
                Collections.<String>emptyList(), allowMismatch, where);
    }

    // an unresolved requested version counts as requested; the result starts with WARNING if a failure is allowed
    static String verify(String pluginVersion, Collection<String> resolved, Collection<String> unresolved,
            Collection<String> providers, Collection<String> old, boolean allowMismatch, String where) {
        // the plugin's own version that could not be resolved leaves the runtime missing rather than mismatched
        Set<String> unresolvedOther = new TreeSet<>(unresolved);
        if (pluginVersion != null)
            unresolvedOther.remove(pluginVersion);
        boolean present = !resolved.isEmpty() || !providers.isEmpty();
        if (!present && ((pluginVersion == null) || unresolvedOther.isEmpty()))
            return failOrWarn(missing(pluginVersion, unresolved, old, where), allowMismatch);
        if (pluginVersion == null)
            return "the plugin version is unknown (not running from a release jar), so no version was compared";
        // any other resolved or requested version is a mismatch
        Set<String> mismatched = new TreeSet<>(resolved);
        mismatched.remove(pluginVersion);
        mismatched.addAll(unresolvedOther);
        if (mismatched.isEmpty()) {
            return resolved.isEmpty()
                    ? LIBRARY + " provided by " + String.join(", ", providers) + " in " + where
                            + " (built from source, version not compared)"
                    : LIBRARY + ":" + pluginVersion + " matches the plugin in " + where;
        }
        String message = "Approov version mismatch in " + where + ": the Approov Gradle plugin (io.approov.gradle, "
                + PLUGIN + ") is version " + pluginVersion + " but the app resolves " + LIBRARY + " version "
                + String.join(", ", mismatched)
                + (unresolvedOther.isEmpty() ? "" : " (" + String.join(", ", unresolvedOther)
                        + " could not be resolved)")
                + "; the plugin and the library are released together and must "
                + "be the same version: set both to " + pluginVersion + " (or both to the other version)";
        return failOrWarn(message, allowMismatch);
    }

    private static String missing(String pluginVersion, Collection<String> unresolved, Collection<String> old,
            String where) {
        List<String> requested = new ArrayList<>();
        for (String v : new TreeSet<>(unresolved))
            requested.add(LIBRARY + ":" + v);
        return "Approov runtime missing in " + where + ": the Approov Gradle plugin (io.approov.gradle) weaves calls "
                + "to the " + LIBRARY + " library into this app, but its runtime classpath does not provide it"
                + (requested.isEmpty() ? "" : " (" + String.join(", ", requested)
                        + " could not be resolved; check the repositories)")
                + (old.isEmpty() ? "" : "; it has " + String.join(", ", old)
                        + ", which do not contain the woven entry points")
                + ", so the app would fail at startup with NoClassDefFoundError: add implementation '" + LIBRARY
                + ":" + ((pluginVersion != null) ? pluginVersion : "<plugin version>") + "'";
    }

    private static String failOrWarn(String message, boolean allowMismatch) {
        if (allowMismatch)
            return "WARNING (" + ALLOW_PROPERTY + "=true, development only): " + message;
        throw new GradleException(message + " (for local development only, -P" + ALLOW_PROPERTY + "=true "
                + "turns this error into a warning)");
    }
}
