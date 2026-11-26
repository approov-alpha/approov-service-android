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

import org.gradle.api.provider.ListProperty;

/**
 * ApproovExtension is the approov extension of the Approov Gradle plugin. Its cronetDependencyPackages, set as
 * approov { cronetDependencyPackages = ['com.margelo.nitro.nitrofetch'] }, lists the dependency packages whose
 * Cronet builder creation is woven. The default is react-native-nitro-fetch and an empty list weaves the app's
 * own classes only.
 */
public abstract class ApproovExtension {
    // react-native-nitro-fetch's package, woven by default
    public static final String NITRO_FETCH_PACKAGE = "com.margelo.nitro.nitrofetch";

    /**
     * Gets the dotted package names of the dependencies whose Cronet engine builder creation is woven, in addition
     * to the app's own classes. A package that is in the app must contain a builder creation the weave recognises,
     * or the build fails.
     *
     * @return the dotted package names
     */
    public abstract ListProperty<String> getCronetDependencyPackages();
}
