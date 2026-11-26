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
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ApproovServiceVersionCheckTask fails the build when nothing on the app's runtime classpath provides the
 * io.approov:service.android runtime the plugin weaves calls to, or when the app resolves a version of it that
 * differs from this plugin's version. Every input is a plain value or a file collection, so the task is
 * compatible with the configuration cache.
 */
public abstract class ApproovServiceVersionCheckTask extends DefaultTask {
    @Input
    @Optional
    public abstract Property<String> getPluginVersion();

    @Input
    public abstract ListProperty<String> getResolvedVersions();

    /** Gets the versions of the library that are requested but cannot be resolved, empty if none. */
    @Input
    public abstract ListProperty<String> getUnresolvedVersions();

    /** Gets the projects of the build that are the library, empty if none. */
    @Input
    public abstract ListProperty<String> getLibraryProjects();

    /** Gets the Approov service layers under old coordinates, for the message, empty if none. */
    @Input
    public abstract ListProperty<String> getOldCoordinates();

    /** Gets the app's local file dependencies, which provide the runtime if they contain the weaver classes. */
    @InputFiles
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract ConfigurableFileCollection getLocalFiles();

    @Input
    public abstract Property<Boolean> getAllowMismatch();

    @Input
    public abstract Property<String> getLocation();

    @TaskAction
    public void check() {
        // the library projects and the local files with the weaver classes provide the runtime
        List<String> providers = new ArrayList<>(getLibraryProjects().getOrElse(Collections.<String>emptyList()));
        providers.addAll(ApproovServiceVersionCheck.localFilesWithRuntime(getLocalFiles().getFiles()));
        String result = ApproovServiceVersionCheck.verify(getPluginVersion().getOrNull(),
                getResolvedVersions().get(), getUnresolvedVersions().getOrElse(Collections.<String>emptyList()),
                providers, getOldCoordinates().getOrElse(Collections.<String>emptyList()),
                getAllowMismatch().get(), getLocation().get());
        // a failure turned into a warning is logged as a warning
        if (result.startsWith("WARNING"))
            getLogger().warn("Approov: " + result);
        else
            getLogger().info("Approov: " + result);
    }
}
