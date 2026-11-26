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

import com.android.build.api.variant.Variant;

import org.gradle.api.Project;
import org.gradle.api.artifacts.FileCollectionDependency;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * ApproovVersionConfigurer registers, for each variant, the check that the app's runtime classpath provides the
 * library the plugin weaves calls to, in the plugin's own version.
 */
final class ApproovVersionConfigurer {
    private ApproovVersionConfigurer() {
    }

    static void configure(Project project, Variant variant) {
        String name = variant.getName();
        String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        TaskProvider<ApproovServiceVersionCheckTask> check = project.getTasks().register(
                "checkApproovServiceVersion" + cap, ApproovServiceVersionCheckTask.class, t -> {
                    t.setGroup("verification");
                    t.setDescription("Fails unless the " + name + " variant's runtime classpath provides "
                            + ApproovServiceVersionCheck.LIBRARY + " in the version of this plugin");
                    // the plugin version is unknown when not running from a release jar
                    String pluginVersion = ApproovServiceVersionCheck.pluginVersion();
                    if (pluginVersion != null)
                        t.getPluginVersion().set(pluginVersion);
                    Provider<ResolvedComponentResult> root = variant.getRuntimeConfiguration().getIncoming()
                            .getResolutionResult().getRootComponent();
                    t.getResolvedVersions().set(root.map(ApproovServiceVersionCheck::libraryVersions));
                    t.getUnresolvedVersions().set(root.map(ApproovServiceVersionCheck::unresolvedLibraryVersions));
                    t.getLibraryProjects().set(root.map(ApproovServiceVersionCheck::libraryProjects));
                    t.getOldCoordinates().set(root.map(ApproovServiceVersionCheck::oldCoordinates));
                    // file dependencies (a local .aar) are not in the resolution graph
                    t.getLocalFiles().from(project.provider(() -> {
                        List<Object> files = new ArrayList<>();
                        for (FileCollectionDependency d : variant.getRuntimeConfiguration().getAllDependencies()
                                .withType(FileCollectionDependency.class))
                            files.add(d.getFiles());
                        return files;
                    }));
                    t.getAllowMismatch().set(project.getProviders()
                            .gradleProperty(ApproovServiceVersionCheck.ALLOW_PROPERTY)
                            .map(Boolean::parseBoolean).orElse(false));
                    t.getLocation().set(project.getPath() + " variant " + name);
                });
        variant.getLifecycleTasks().registerPreBuild(check);
    }
}
