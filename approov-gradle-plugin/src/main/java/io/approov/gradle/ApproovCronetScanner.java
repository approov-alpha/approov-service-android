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

import org.objectweb.asm.ClassReader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ApproovCronetScanner runs the STRICT rewrite of the build transform over class jars and directories, without
 * writing anything, and reports what it would rewrite in each configured dependency package. The build uses it to
 * fail when a configured package is in the app but nothing in it would be rewritten, or it has a shape the weave
 * cannot protect.
 */
public final class ApproovCronetScanner {
    // PackageResult holds what a scan found in one package
    public static final class PackageResult {
        // the dotted package name
        public final String packageName;

        // the number of classes found in the package
        public int classes;

        // the sites that would be rewritten in the package
        public final List<ApproovCronetRewriter.Site> sites = new ArrayList<>();

        PackageResult(String packageName) {
            this.packageName = packageName;
        }
    }

    private ApproovCronetScanner() {
    }

    // "com.margelo.nitro.nitrofetch" becomes "com/margelo/nitro/nitrofetch/"
    static String internalPrefix(String packageName) {
        return packageName.replace('.', '/') + "/";
    }

    // null if the entry is not a class in any of the packages
    static String packageOf(String entryName, List<String> packages) {
        if (!entryName.endsWith(".class"))
            return null;
        for (String p : packages) {
            if (entryName.startsWith(internalPrefix(p)))
                return p;
        }
        return null;
    }

    /**
     * Scans a classpath for the Cronet builder creation in each configured package.
     *
     * @param classpath are the class jars and directories to scan
     * @param packages are the configured dotted packages
     * @return the result for each package, in the configured order
     * @throws ApproovWeaveException if a class has a shape the weave cannot protect
     */
    public static Map<String, PackageResult> scan(Iterable<File> classpath, List<String> packages) {
        Map<String, PackageResult> results = new LinkedHashMap<>();
        for (String p : packages)
            results.put(p, new PackageResult(p));
        for (File f : classpath) {
            // a class directory or a class jar
            if (f.isDirectory()) {
                Path root = f.toPath();
                try (Stream<Path> walk = Files.walk(root)) {
                    for (Path p : (Iterable<Path>) walk::iterator) {
                        String rel = root.relativize(p).toString().replace(File.separatorChar, '/');
                        String pkg = packageOf(rel, packages);
                        if ((pkg != null) && Files.isRegularFile(p)) {
                            try (InputStream in = Files.newInputStream(p)) {
                                add(results.get(pkg), in);
                            }
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else if (f.isFile() && f.getName().endsWith(".jar")) {
                try (ZipFile zip = new ZipFile(f)) {
                    for (ZipEntry e : Collections.list(zip.entries())) {
                        String pkg = packageOf(e.getName(), packages);
                        if ((pkg != null) && !e.isDirectory()) {
                            try (InputStream in = zip.getInputStream(e)) {
                                add(results.get(pkg), in);
                            }
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return results;
    }

    private static void add(PackageResult result, InputStream in) throws IOException {
        ApproovCronetRewriter v = new ApproovCronetRewriter(null, ApproovCronetRewriter.Mode.STRICT);
        new ClassReader(in).accept(v, 0);
        result.classes++;
        result.sites.addAll(v.sites());
    }

    /**
     * Checks that every configured package in the app has at least one builder creation to rewrite, as otherwise
     * its Cronet traffic would ship without Approov protection. A package with no classes on the classpath is not
     * in the app and is skipped.
     *
     * @param results are the scan results for each package
     * @return the same results
     * @throws ApproovWeaveException if a package in the app has nothing to rewrite
     */
    public static Map<String, PackageResult> requireSites(Map<String, PackageResult> results) {
        for (PackageResult r : results.values()) {
            if ((r.classes > 0) && r.sites.isEmpty()) {
                throw new ApproovWeaveException("found " + r.classes + " classes of " + r.packageName + " but no "
                        + "Cronet engine builder creation to rewrite, so its requests would NOT be protected by "
                        + "Approov: update the Approov Gradle plugin for this library version, or remove the package "
                        + "from approov.cronetDependencyPackages if it does not use Cronet");
            }
        }
        return results;
    }
}
