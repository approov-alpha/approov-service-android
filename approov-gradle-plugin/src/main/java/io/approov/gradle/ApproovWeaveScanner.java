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
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

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
 * ApproovWeaveScanner finds, in a module's own classes, the network client construction sites the weave protects
 * and those it cannot, without rewriting anything. The weave report task lists both, so a module where nothing
 * was woven, or where a client is built in a way the weave does not see, is visible in the build instead of
 * shipping silently unprotected. Already woven classes are recognised too, so it can read classes before or after
 * the transform.
 */
final class ApproovWeaveScanner {
    // the names of the stacks in the report
    static final String OKHTTP = "OkHttp";
    static final String URL_CONNECTION = "HttpsURLConnection";
    static final String VOLLEY = "Volley";
    static final String CRONET = "Cronet";

    // the classes whose calls are found, and the Approov weavers of already woven code
    private static final String OKHTTP_CLIENT = "okhttp3/OkHttpClient";
    private static final String OKHTTP_BUILDER = "okhttp3/OkHttpClient$Builder";
    private static final String OKHTTP_WEAVER = "io/approov/service/android/okhttp/ApproovOkHttpWeaver";
    private static final String URL = "java/net/URL";
    private static final String URL_WEAVER = "io/approov/service/android/httpsurlconn/ApproovUrlConnectionWeaver";
    private static final String VOLLEY_OWNER = "com/android/volley/toolbox/Volley";
    private static final String VOLLEY_WEAVER = "io/approov/service/android/volley/ApproovVolleyWeaver";
    private static final String HTTP_ENGINE_BUILDER = "android/net/http/HttpEngine$Builder";
    private static final String RETROFIT_BUILDER = "retrofit2/Retrofit$Builder";

    // Report holds what a scan found
    static final class Report {
        // the protected construction sites per stack, each "dotted.Class.method: what"
        final Map<String, List<String>> woven = new LinkedHashMap<>();

        // the construction sites the weave does not protect, each "dotted.Class.method: what and why"
        final List<String> unwoven = new ArrayList<>();

        // the sites whose protection depends on how they are used
        final List<String> notes = new ArrayList<>();

        Report() {
            for (String stack : new String[] {OKHTTP, URL_CONNECTION, VOLLEY, CRONET})
                woven.put(stack, new ArrayList<>());
        }

        int wovenCount() {
            int n = 0;
            for (List<String> sites : woven.values())
                n += sites.size();
            return n;
        }

        // such as "OkHttp 2, HttpsURLConnection 0, Volley 0, Cronet 1"
        String counts() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, List<String>> e : woven.entrySet()) {
                if (sb.length() > 0)
                    sb.append(", ");
                sb.append(e.getKey()).append(' ').append(e.getValue().size());
            }
            return sb.toString();
        }
    }

    private ApproovWeaveScanner() {
    }

    static Report scan(Iterable<File> jars, Iterable<File> dirs) {
        Report report = new Report();
        for (File dir : dirs) {
            if (!dir.isDirectory())
                continue;
            try (Stream<Path> walk = Files.walk(dir.toPath())) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (p.toString().endsWith(".class") && Files.isRegularFile(p)) {
                        try (InputStream in = Files.newInputStream(p)) {
                            scanClass(in, report);
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        for (File jar : jars) {
            if (!jar.isFile())
                continue;
            try (ZipFile zip = new ZipFile(jar)) {
                for (ZipEntry e : Collections.list(zip.entries())) {
                    if (!e.isDirectory() && e.getName().endsWith(".class")) {
                        try (InputStream in = zip.getInputStream(e)) {
                            scanClass(in, report);
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return report;
    }

    static void scanClass(InputStream in, Report report) throws IOException {
        ClassReader reader = new ClassReader(in);
        if (isLibrary(reader.getClassName()))
            return;
        // the Cronet sites come from the LENIENT rewrite, which writes nothing here
        ApproovCronetRewriter cronet = new ApproovCronetRewriter(null, ApproovCronetRewriter.Mode.LENIENT);
        reader.accept(new Analyzer(cronet, report), 0);
        for (ApproovCronetRewriter.Site site : cronet.sites())
            report.woven.get(CRONET).add(site.toString());
        report.unwoven.addAll(cronet.warnings());
    }

    // the layer and the libraries themselves are never woven
    private static boolean isLibrary(String internalName) {
        return internalName.startsWith("io/approov/service/") || internalName.startsWith("okhttp3/")
                || internalName.startsWith("com/android/volley/") || internalName.startsWith("org/chromium/net/");
    }

    private static final class Analyzer extends ClassVisitor {
        // the report to add to
        private final Report report;

        // the dotted name of the visited class
        private String className = "?";

        Analyzer(ClassVisitor next, Report report) {
            super(Opcodes.ASM9, next);
            this.report = report;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                String[] interfaces) {
            className = name.replace('/', '.');
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
            MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new Method(next, className + "." + name);
        }

        private final class Method extends MethodVisitor {
            // the "dotted.Class.method" of the visited method
            private final String site;

            Method(MethodVisitor next, String site) {
                super(Opcodes.ASM9, next);
                this.site = site;
            }

            private void woven(String stack, String what) {
                report.woven.get(stack).add(site + ": " + what);
            }

            private void unwoven(String what) {
                report.unwoven.add(site + ": " + what);
            }

            @Override
            public void visitTypeInsn(int opcode, String type) {
                if ((opcode == Opcodes.NEW) && HTTP_ENGINE_BUILDER.equals(type))
                    unwoven("android.net.http.HttpEngine is not woven; its requests are NOT protected by Approov");
                else if ((opcode == Opcodes.NEW) && ApproovCronetRewriter.WEAVE_BUILDER.equals(type))
                    woven(CRONET, "new ApproovCronetEngineBuilder (already woven)");
                super.visitTypeInsn(opcode, type);
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                    boolean isInterface) {
                // each call is woven, already woven, not woven or a note
                String what = owner.substring(owner.lastIndexOf('/') + 1).replace('$', '.') + "." + name + "()";
                if (OKHTTP_BUILDER.equals(owner) && "build".equals(name) && (opcode == Opcodes.INVOKEVIRTUAL)) {
                    woven(OKHTTP, what);
                } else if (OKHTTP_WEAVER.equals(owner)) {
                    woven(OKHTTP, "ApproovOkHttpWeaver.build() (already woven)");
                } else if (OKHTTP_CLIENT.equals(owner) && "<init>".equals(name) && "()V".equals(descriptor)) {
                    unwoven("new OkHttpClient() is not woven (only OkHttpClient.Builder.build() is); its client "
                            + "has no Approov protection, use new OkHttpClient.Builder().build()");
                } else if (URL.equals(owner) && "openConnection".equals(name)) {
                    woven(URL_CONNECTION, what);
                } else if (URL_WEAVER.equals(owner)) {
                    woven(URL_CONNECTION, "ApproovUrlConnectionWeaver.openConnection() (already woven)");
                } else if (URL.equals(owner) && "openStream".equals(name)) {
                    unwoven("URL.openStream() is not woven; its request has no Approov protection, use "
                            + "url.openConnection().getInputStream()");
                } else if (VOLLEY_OWNER.equals(owner) && "newRequestQueue".equals(name)) {
                    if ("(Landroid/content/Context;)Lcom/android/volley/RequestQueue;".equals(descriptor))
                        woven(VOLLEY, what);
                    else
                        report.notes.add(site + ": Volley.newRequestQueue" + descriptor + " is not woven (only "
                                + "newRequestQueue(Context) is); it is protected only if its stack is "
                                + "ApproovVolley.getBaseHttpStack()");
                } else if (VOLLEY_WEAVER.equals(owner)) {
                    woven(VOLLEY, "ApproovVolleyWeaver.newRequestQueue() (already woven)");
                } else if (ApproovCronetRewriter.WEAVER.equals(owner)) {
                    woven(CRONET, "ApproovCronetWeaver.createBuilder() (already woven)");
                } else if (RETROFIT_BUILDER.equals(owner) && "build".equals(name)) {
                    report.notes.add(site + ": Retrofit.Builder.build() is protected only if its client(...) is "
                            + "an OkHttpClient built with OkHttpClient.Builder.build() in woven code; without "
                            + "client(...) Retrofit makes its own new OkHttpClient(), which is not woven");
                }
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }

            @Override
            public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... args) {
                // only plain metafactory method references are woven, as serializable ones use altMetafactory
                boolean metafactory = ApproovClassVisitorFactory.LAMBDA_METAFACTORY.equals(bootstrap.getOwner())
                        && ApproovClassVisitorFactory.METAFACTORY.equals(bootstrap.getName());
                for (Object arg : args) {
                    if (!(arg instanceof Handle))
                        continue;
                    Handle h = (Handle) arg;
                    String ref = h.getOwner().substring(h.getOwner().lastIndexOf('/') + 1).replace('$', '.') + "::"
                            + (h.getName().equals("<init>") ? "new" : h.getName());
                    if (OKHTTP_WEAVER.equals(h.getOwner()) || URL_WEAVER.equals(h.getOwner())
                            || VOLLEY_WEAVER.equals(h.getOwner())) {
                        woven(stackOfWeaver(h.getOwner()), "method reference " + ref + " (already woven)");
                    } else if (ApproovClassVisitorFactory.wovenHandle(h) != null) {
                        if (metafactory)
                            woven(stackOf(h.getOwner()), "method reference " + ref);
                        else
                            unwoven("serializable method reference " + ref + " is not woven; its client has no "
                                    + "Approov protection, use a lambda");
                    } else if ((h.getTag() == Opcodes.H_NEWINVOKESPECIAL) && (OKHTTP_CLIENT.equals(h.getOwner())
                            || ApproovCronetRewriter.BUILDER.equals(h.getOwner())
                            || ApproovCronetRewriter.EXPERIMENTAL_BUILDER.equals(h.getOwner()))) {
                        unwoven("constructor reference " + ref + " is not woven; use a lambda");
                    } else if (URL.equals(h.getOwner()) && "openStream".equals(h.getName())) {
                        unwoven("method reference " + ref + " is not woven; its request has no Approov protection");
                    } else if (h.getOwner().startsWith(ApproovCronetRewriter.CRONET)
                            && ApproovCronetRewriter.CREATE_BUILDER.equals(h.getName())) {
                        unwoven("method reference " + ref + " is not woven; use a lambda");
                    }
                }
                super.visitInvokeDynamicInsn(name, descriptor, bootstrap, args);
            }
        }
    }

    private static String stackOfWeaver(String weaver) {
        if (weaver.equals(OKHTTP_WEAVER))
            return OKHTTP;
        return weaver.equals(URL_WEAVER) ? URL_CONNECTION : VOLLEY;
    }

    private static String stackOf(String owner) {
        if (owner.startsWith("okhttp3/"))
            return OKHTTP;
        if (owner.equals(URL))
            return URL_CONNECTION;
        return VOLLEY;
    }
}
