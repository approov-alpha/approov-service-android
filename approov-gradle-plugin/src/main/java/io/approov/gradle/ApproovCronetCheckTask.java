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
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * ApproovCronetCheckTask fails the build when a configured Cronet dependency package (react-native-nitro-fetch by
 * default) is on the app's runtime classpath but the weave would rewrite no Cronet builder creation in it, or it
 * creates a builder in a shape the weave cannot protect, so that a library release that changes how it creates
 * its engine cannot ship without Approov protection. It lists every site it will rewrite.
 */
@CacheableTask
public abstract class ApproovCronetCheckTask extends DefaultTask {
    @Classpath
    public abstract ConfigurableFileCollection getClasspath();

    @Input
    public abstract ListProperty<String> getPackages();

    @OutputFile
    public abstract RegularFileProperty getReport();

    @TaskAction
    public void check() throws IOException {
        // a shape the weave cannot protect, or a package with nothing to rewrite, fails the build
        Map<String, ApproovCronetScanner.PackageResult> results;
        try {
            results = ApproovCronetScanner.requireSites(
                    ApproovCronetScanner.scan(getClasspath().getFiles(), getPackages().get()));
        } catch (ApproovWeaveException e) {
            throw new GradleException(e.getMessage(), e);
        }
        // log and report the sites that will be rewritten in each package
        StringBuilder report = new StringBuilder();
        for (ApproovCronetScanner.PackageResult r : results.values()) {
            if (r.classes == 0) {
                getLogger().info("Approov Cronet weave: " + r.packageName + " is not in this app");
                report.append(r.packageName).append(": not present\n");
                continue;
            }
            for (ApproovCronetRewriter.Site s : r.sites) {
                getLogger().lifecycle("Approov Cronet weave: will rewrite " + s);
                report.append(s).append('\n');
            }
        }
        Files.writeString(getReport().get().getAsFile().toPath(), report.toString(), StandardCharsets.UTF_8);
    }
}
