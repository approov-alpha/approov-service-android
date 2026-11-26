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
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ApproovWeaveMarkerTask generates the string resource the layer reads at runtime to confirm that the plugin wove
 * Cronet in this app, and a keep file for it. Its value, such as "2;packages=a.b,c.d", lists the dependency
 * packages whose Cronet engine creation was woven, so the layer can tell an unwoven react-native-nitro-fetch from
 * a woven one even when the plugin ran. The keep file is needed as the layer looks the marker up by name, so
 * resource shrinking would otherwise remove it and the layer would report the plugin as missing.
 */
@CacheableTask
public abstract class ApproovWeaveMarkerTask extends DefaultTask {
    // the resource the layer looks up, which must match CronetWeaveCheck.MARKER_RESOURCE
    static final String MARKER = "approov_cronet_weave";

    // the generated values and keep files
    static final String VALUES_FILE = "values/approov_cronet_weave.xml";
    static final String KEEP_FILE = "raw/approov_cronet_weave_keep.xml";

    // the format of the marker value that CronetWeaveCheck reads, not a plugin or library version
    static final String FORMAT = "2";

    @Input
    public String getFormat() {
        return FORMAT;
    }

    /** Gets the dependency packages whose Cronet engine creation is woven. */
    @Input
    public abstract ListProperty<String> getPackages();

    static String value(java.util.List<String> packages) {
        return FORMAT + ";packages=" + String.join(",", packages);
    }

    // a generated resource rather than a resValue, as resValues are off by default from AGP 9
    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    static String valuesXml(String value) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<resources>\n"
                + "    <!-- Approov Gradle plugin io.approov.gradle: Cronet woven in the app's classes and in"
                + " these dependency packages -->\n"
                + "    <string name=\"" + MARKER + "\" translatable=\"false\">" + value + "</string>\n"
                + "</resources>\n";
    }

    static String keepXml() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<resources xmlns:tools=\"http://schemas.android.com/tools\"\n"
                + "    tools:keep=\"@string/" + MARKER + "\" />\n";
    }

    @TaskAction
    public void generate() throws IOException {
        Path root = getOutputDir().get().getAsFile().toPath();
        write(root.resolve(VALUES_FILE), valuesXml(value(getPackages().getOrElse(java.util.Collections.emptyList()))));
        write(root.resolve(KEEP_FILE), keepXml());
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
