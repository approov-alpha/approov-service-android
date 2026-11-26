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
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.ClassReader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ApproovReactNativeCheckTask fails the build of a React Native application when React Native's
 * OkHttpClientProvider is not on its runtime classpath, or has a shape the weave cannot protect, so that a React
 * Native release that moves or changes the class cannot ship with its networking silently unprotected. It lists
 * every site it will rewrite in build/outputs/approov/react-native-weave-variant.txt.
 */
@CacheableTask
public abstract class ApproovReactNativeCheckTask extends DefaultTask {
    // the class file of React Native's provider
    static final String PROVIDER_ENTRY = ApproovReactNativeRewriter.PROVIDER_CLASS.replace('.', '/') + ".class";

    @Classpath
    public abstract ConfigurableFileCollection getClasspath();

    @OutputFile
    public abstract RegularFileProperty getReport();

    // the sites the weave will rewrite in the first OkHttpClientProvider on the classpath
    static List<String> scan(Iterable<File> classpath) throws IOException {
        for (File f : classpath) {
            if (f.isDirectory()) {
                File cls = new File(f, PROVIDER_ENTRY);
                if (cls.isFile()) {
                    try (InputStream in = Files.newInputStream(cls.toPath())) {
                        return sites(in);
                    }
                }
            } else if (f.isFile() && f.getName().endsWith(".jar")) {
                try (ZipFile zip = new ZipFile(f)) {
                    ZipEntry e = zip.getEntry(PROVIDER_ENTRY);
                    if (e != null) {
                        try (InputStream in = zip.getInputStream(e)) {
                            return sites(in);
                        }
                    }
                }
            }
        }
        throw new ApproovWeaveException(ApproovReactNativeRewriter.PROVIDER_CLASS + " is not on the runtime classpath "
                + "of this React Native application, so React Native's own networking would NOT be protected by "
                + "Approov: update the Approov Gradle plugin for this React Native version");
    }

    private static List<String> sites(InputStream in) throws IOException {
        ApproovReactNativeRewriter rewriter = new ApproovReactNativeRewriter(null);
        new ClassReader(in).accept(rewriter, 0);
        return new ArrayList<>(rewriter.sites());
    }

    @TaskAction
    public void check() throws IOException {
        List<String> sites;
        try {
            sites = scan(getClasspath().getFiles());
        } catch (ApproovWeaveException e) {
            throw new GradleException(e.getMessage(), e);
        }
        StringBuilder report = new StringBuilder();
        for (String s : sites) {
            getLogger().lifecycle("Approov React Native weave: will rewrite " + s);
            report.append(s).append('\n');
        }
        Files.writeString(getReport().get().getAsFile().toPath(), report.toString(), StandardCharsets.UTF_8);
    }
}
