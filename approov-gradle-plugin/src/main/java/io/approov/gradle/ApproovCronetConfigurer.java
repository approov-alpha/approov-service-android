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

import com.android.build.api.instrumentation.InstrumentationScope;
import com.android.build.api.variant.SourceDirectories;
import com.android.build.api.variant.Variant;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.tasks.TaskProvider;

import java.util.List;

import kotlin.Unit;

/**
 * ApproovCronetConfigurer registers, for one variant of an application module, the Cronet weaving of the
 * configured dependency packages, the check that fails the build when a package in the app has nothing the weave
 * recognises, and the marker resource the layer reads at runtime.
 */
final class ApproovCronetConfigurer {
    // attribute used to select the class jars of the dependencies
    private static final Attribute<String> ARTIFACT_TYPE = Attribute.of("artifactType", String.class);

    // the Approov layer and Cronet itself, which a configured package must not contain or be inside
    static final String[] NEVER_WOVEN = {"io.approov.service", "org.chromium.net"};

    private ApproovCronetConfigurer() {
    }

    static void configure(Project project, Variant variant, ApproovExtension extension) {
        String name = variant.getName();
        String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);

        // the marker string, and a keep file so resource shrinking does not remove it
        SourceDirectories.Layered res = variant.getSources().getRes();
        if (res == null) {
            throw new GradleException("Approov Gradle plugin: cannot add the marker resource to variant " + name
                    + " of " + project.getPath() + " as it has no Android resources");
        }
        // each configured package must be a dotted package outside the layer and Cronet
        List<String> packages = extension.getCronetDependencyPackages().get();
        for (String p : packages) {
            if (p.isEmpty() || (p.indexOf('/') >= 0) || p.startsWith(".") || p.endsWith(".")
                    || (p.indexOf('.') < 0)) {
                throw new GradleException("approov.cronetDependencyPackages: '" + p + "' is not a dotted package "
                        + "name such as com.margelo.nitro.nitrofetch");
            }
            for (String own : NEVER_WOVEN) {
                if (own.equals(p) || own.startsWith(p + ".") || p.startsWith(own + ".")) {
                    throw new GradleException("approov.cronetDependencyPackages: '" + p + "' contains or is inside "
                            + own + ", which is never woven; name the library's own package, such as "
                            + "com.margelo.nitro.nitrofetch");
                }
            }
        }
        // the marker lists the woven packages, so the layer reports react-native-nitro-fetch as unprotected when
        // the plugin ran without weaving it, such as with an empty list
        TaskProvider<ApproovWeaveMarkerTask> marker = project.getTasks().register(
                "generateApproovWeaveMarker" + cap, ApproovWeaveMarkerTask.class,
                t -> t.getPackages().set(packages));
        res.addGeneratedSourceDirectory(marker, ApproovWeaveMarkerTask::getOutputDir);
        if (packages.isEmpty())
            return;

        // weave the configured dependency packages, which needs InstrumentationScope.ALL
        variant.getInstrumentation().transformClassesWith(
                ApproovCronetVisitorFactory.class,
                InstrumentationScope.ALL,
                params -> {
                    params.getPackages().set(packages);
                    return Unit.INSTANCE;
                });

        // check before every build that each configured package in the app has something to rewrite
        TaskProvider<ApproovCronetCheckTask> check = project.getTasks().register(
                "checkApproovCronetWeave" + cap, ApproovCronetCheckTask.class, t -> {
                    t.setGroup("verification");
                    t.setDescription("Fails unless the Approov weave rewrites Cronet engine creation in every "
                            + "configured dependency package present in the app");
                    t.getPackages().set(packages);
                    t.getClasspath().from(variant.getRuntimeConfiguration().getIncoming().artifactView(view -> {
                        view.setLenient(true);
                        view.attributes(a -> a.attribute(ARTIFACT_TYPE, "android-classes-jar"));
                    }).getFiles());
                    t.getReport().set(project.getLayout().getBuildDirectory()
                            .file("outputs/approov/cronet-weave-" + name + ".txt"));
                });
        variant.getLifecycleTasks().registerPreBuild(check);
    }
}
