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

import org.gradle.api.DefaultTask;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ApproovWeaveReportTask writes, for one variant of a module, the network client construction sites in the
 * module's own classes that the weave protects and those it cannot to a report file in build/outputs/approov, and
 * logs a summary. It warns for every site the weave does not protect, and when nothing in the module was woven
 * outside a React Native application, since an unprotected client otherwise looks the same as a protected one in the
 * build, and when Cronet's Java fallback (org.chromium.net:cronet-fallback) is on the variant's runtime classpath,
 * since pinned hosts are refused on it. React Native's own networking and Cronet in the configured dependency
 * packages are woven separately and reported in {@code react-native-weave-<variant>.txt} and
 * {@code cronet-weave-<variant>.txt}, which the report names. Like any cached task it logs only when it runs, while
 * the report file stays for every build.
 */
@CacheableTask
public abstract class ApproovWeaveReportTask extends DefaultTask {
    // the most unwoven sites logged as warnings, while the report lists them all
    static final int MAX_WARNINGS = 10;

    /** Gets the module's class jars, from the project scope classes artifact. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ListProperty<RegularFile> getJars();

    /** Gets the module's class directories, from the project scope classes artifact. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ListProperty<Directory> getDirs();

    /** Gets the module and variant, for the messages. */
    @Input
    public abstract Property<String> getLocation();

    /** Gets Cronet's Java fallback on the variant's runtime classpath as "group:name:version", empty if absent. */
    @Input
    public abstract ListProperty<String> getCronetFallback();

    /** Gets whether React Native's own networking is woven in this variant, see ApproovReactNativeConfigurer. */
    @Input
    public abstract Property<Boolean> getReactNativeWeave();

    /** Gets the dependency packages whose Cronet is woven in this variant, empty if none. */
    @Input
    public abstract ListProperty<String> getCronetPackages();

    @OutputFile
    public abstract RegularFileProperty getReport();

    @TaskAction
    public void report() throws IOException {
        // scan the module's own classes and write the report
        List<File> jars = new ArrayList<>();
        for (RegularFile f : getJars().get())
            jars.add(f.getAsFile());
        List<File> dirs = new ArrayList<>();
        for (Directory d : getDirs().get())
            dirs.add(d.getAsFile());
        ApproovWeaveScanner.Report report = ApproovWeaveScanner.scan(jars, dirs);
        File out = getReport().get().getAsFile();
        Files.createDirectories(out.getParentFile().toPath());
        String where = getLocation().get();
        List<String> fallback = getCronetFallback().getOrElse(java.util.Collections.<String>emptyList());
        Others others = new Others(getReactNativeWeave().getOrElse(false),
                getCronetPackages().getOrElse(java.util.Collections.<String>emptyList()), variant(out));
        Files.writeString(out.toPath(), text(where, report, fallback, others), StandardCharsets.UTF_8);

        // log the summary and the warnings
        getLogger().lifecycle("Approov weave (" + where + "): " + report.wovenCount() + " call site(s) woven ("
                + report.counts() + "), " + report.unwoven.size() + " not woven; report " + out);
        int shown = 0;
        for (String site : report.unwoven) {
            if (shown++ == MAX_WARNINGS) {
                getLogger().warn("Approov weave (" + where + "): " + (report.unwoven.size() - MAX_WARNINGS)
                        + " more site(s) not woven, listed in " + out);
                break;
            }
            getLogger().warn("Approov weave: NOT woven: " + site);
        }
        for (String module : fallback)
            getLogger().warn("Approov: " + fallbackWarning(module, where));
        if (report.wovenCount() == 0) {
            String warning = emptyModuleWarning(where, others);
            if (warning != null)
                getLogger().warn(warning);
            else
                getLogger().lifecycle(emptyModuleNote(where, others));
        }
    }

    // the warning for a module whose own classes build no client, or null in a React Native application, where
    // React Native's own networking is woven separately. Cronet packages do not lift the warning, as they are
    // configured by default whether or not the app has them.
    static String emptyModuleWarning(String where, Others others) {
        if (others.reactNative)
            return null;
        String warning = "Approov weave (" + where + "): no OkHttp, HttpsURLConnection, Volley or Cronet client "
                + "construction found in this module's own classes, so nothing in it was woven; clients built in "
                + "libraries, in modules without the plugin, by Retrofit without client(...) or by React Native's "
                + "own fetch are not protected by the weave";
        for (String line : others.lines())
            warning += "; " + line;
        return warning;
    }

    // the lifecycle line that replaces the warning in a React Native application
    static String emptyModuleNote(String where, Others others) {
        return "Approov weave (" + where + "): this module's own classes build no OkHttp, HttpsURLConnection, "
                + "Volley or Cronet client; " + String.join("; ", others.lines());
    }

    // the variant name, from the report file weave-<variant>.txt, so the other report files can be named
    static String variant(File report) {
        String name = report.getName();
        if (name.startsWith("weave-") && name.endsWith(".txt"))
            return name.substring("weave-".length(), name.length() - ".txt".length());
        return "<variant>";
    }

    /**
     * Others is what is woven in this variant apart from the module's own classes: React Native's own networking
     * and Cronet in the configured dependency packages, each with its own report file.
     */
    static final class Others {
        // whether React Native's OkHttpClientProvider is woven
        final boolean reactNative;

        // the dependency packages whose Cronet engine creation is woven
        final List<String> cronetPackages;

        // the variant, which names the other report files
        final String variant;

        Others(boolean reactNative, List<String> cronetPackages, String variant) {
            this.reactNative = reactNative;
            this.cronetPackages = cronetPackages;
            this.variant = variant;
        }

        // one line for each other weave, naming its report file
        List<String> lines() {
            List<String> lines = new ArrayList<>();
            if (reactNative)
                lines.add("React Native's own networking is woven separately, see react-native-weave-" + variant
                        + ".txt");
            if (!cronetPackages.isEmpty())
                lines.add("Cronet in " + String.join(", ", cronetPackages) + " is woven separately, see "
                        + "cronet-weave-" + variant + ".txt");
            return lines;
        }
    }

    // a warning rather than a failure, as the fallback is harmless while the device has a native provider
    static String fallbackWarning(String module, String where) {
        return module + " is on the runtime classpath of " + where + ": if Cronet falls back to its Java engine, "
                + "requests to hosts with Approov pins are refused as that engine never checks pins, so for devices "
                + "without Google Play services add org.chromium.net:cronet-embedded";
    }

    static String text(String where, ApproovWeaveScanner.Report report, List<String> fallback, Others others) {
        StringBuilder sb = new StringBuilder();
        sb.append("Approov weave report for ").append(where).append('\n');
        sb.append("woven call sites: ").append(report.wovenCount()).append(" (").append(report.counts())
                .append(")\n");
        for (Map.Entry<String, List<String>> e : report.woven.entrySet()) {
            for (String site : e.getValue())
                sb.append("woven ").append(e.getKey()).append(": ").append(site).append('\n');
        }
        for (String site : report.unwoven)
            sb.append("NOT woven: ").append(site).append('\n');
        for (String note : report.notes)
            sb.append("note: ").append(note).append('\n');
        for (String module : fallback)
            sb.append("WARNING: ").append(fallbackWarning(module, where)).append('\n');
        // name the report files that cover what is woven outside the module's own classes
        for (String line : others.lines())
            sb.append(line).append('\n');
        // and what is not woven at all
        String libraries = others.cronetPackages.isEmpty() ? "libraries" : "other libraries";
        if (others.reactNative) {
            sb.append("Only this module's own classes are listed; ").append(libraries)
                    .append(" and modules without the plugin are not woven\n");
        } else {
            sb.append("Only this module's own classes are listed; ").append(libraries)
                    .append(", modules without the plugin and React Native's own fetch are not woven\n");
        }
        return sb.toString();
    }
}
