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

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

/**
 * ApproovCronetRewriter rewrites Cronet engine builder creation so that the engine comes from the Approov Cronet
 * adapter. Only Cronet's public API is matched, never a library's own method or field names. A call to
 * new CronetEngine.Builder(context) becomes new ApproovCronetEngineBuilder(context, site), a subclass of
 * CronetEngine.Builder, and provider.createBuilder() becomes ApproovCronetWeaver.createBuilder(provider, site),
 * where site is the "class.method" of the call. The code's own builder.build() then dispatches to
 * ApproovCronetEngineBuilder.build(), which returns the Approov engine.
 *
 * <p>STRICT mode, for the configured dependency packages, fails the build with an ApproovWeaveException on a
 * shape it cannot protect: an ExperimentalCronetEngine.Builder, a builder subclass, or a class that builds an
 * engine with no builder creation rewritten. LENIENT mode, for the app's own classes, rewrites what it can and
 * reports the rest as warnings. In both modes a builder constructor other than (Context) fails the build, as
 * its NEW has already been rewritten. Already woven code is recognised, so a class visited twice is not
 * rewritten again.
 */
public class ApproovCronetRewriter extends ClassVisitor {
    // ASM API level used throughout
    static final int ASM_API = Opcodes.ASM9;

    // Mode determines how a shape the weave cannot protect is handled
    public enum Mode { STRICT, LENIENT }

    // the Cronet package, classes and calls that are matched
    static final String CRONET = "org/chromium/net/";
    static final String BUILDER = "org/chromium/net/CronetEngine$Builder";
    static final String EXPERIMENTAL_BUILDER = "org/chromium/net/ExperimentalCronetEngine$Builder";
    static final String CONTEXT_CTOR_DESC = "(Landroid/content/Context;)V";
    static final String CREATE_BUILDER = "createBuilder";
    static final String CREATE_BUILDER_DESC = "()Lorg/chromium/net/CronetEngine$Builder;";

    // the Approov classes and calls they are rewritten to
    static final String WEAVE_BUILDER = "io/approov/service/android/cronet/ApproovCronetEngineBuilder";
    static final String WEAVE_BUILDER_CTOR_DESC = "(Landroid/content/Context;Ljava/lang/String;)V";
    static final String WEAVER = "io/approov/service/android/cronet/ApproovCronetWeaver";
    static final String WEAVER_CREATE_BUILDER_DESC =
            "(Lorg/chromium/net/CronetProvider;Ljava/lang/String;)Lorg/chromium/net/CronetEngine$Builder;";

    // Site is one rewritten call site
    public static final class Site {
        // the internal name of the class
        public final String className;

        // the name of the method
        public final String method;

        // what was rewritten
        public final String kind;

        Site(String className, String method, String kind) {
            this.className = className;
            this.method = method;
            this.kind = kind;
        }

        /**
         * Gets the site ID that the woven code passes to the Approov runtime.
         *
         * @return the site as "dotted.Class.method"
         */
        public String id() {
            return className.replace('/', '.') + "." + method;
        }

        @Override
        public String toString() {
            return id() + ": " + kind;
        }
    }

    // how a shape the weave cannot protect is handled
    private final Mode mode;

    // the sites rewritten in the visited class
    private final List<Site> sites = new ArrayList<>();

    // the shapes found in LENIENT mode that the weave cannot protect
    private final List<String> warnings = new ArrayList<>();

    // the internal name of the visited class
    private String className = "?";

    // true if the visited class is not rewritten
    private boolean skip;

    // true if the visited class calls build() on a Cronet builder
    private boolean buildsEngine;

    // true if the visited class was already woven
    private boolean alreadyWoven;

    /**
     * Constructs a new Cronet rewriter.
     *
     * @param next is the downstream visitor to delegate to, or null to only find the sites
     * @param mode is how a shape the weave cannot protect is handled
     */
    public ApproovCronetRewriter(ClassVisitor next, Mode mode) {
        super(ASM_API, next);
        this.mode = mode;
    }

    /**
     * Gets the sites rewritten in the visited class.
     *
     * @return the rewritten sites
     */
    public List<Site> sites() {
        return sites;
    }

    /**
     * Gets the shapes found in LENIENT mode that the weave cannot protect, each left unprotected.
     *
     * @return the warnings
     */
    public List<String> warnings() {
        return warnings;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        className = name;
        // Cronet's own classes create builders legitimately (providers, the builder itself)
        skip = name.startsWith(CRONET);

        // a Cronet builder subclass cannot be protected
        if (!skip && (BUILDER.equals(superName) || EXPERIMENTAL_BUILDER.equals(superName))) {
            unsupported(null, "extends " + superName.replace('/', '.') + ", a Cronet builder subclass");
            skip = true;
        }
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
            String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        return skip ? mv : new Rewriter(mv, name);
    }

    @Override
    public void visitEnd() {
        // in STRICT mode a class that builds an engine must have had its builder creation rewritten
        if ((mode == Mode.STRICT) && !skip && buildsEngine && sites.isEmpty() && !alreadyWoven) {
            throw new ApproovWeaveException(dotted() + " builds a Cronet engine (CronetEngine.Builder.build()) but "
                    + "creates its builder in a way the weave does not recognise, so its requests would NOT be "
                    + "protected by Approov: update the Approov Gradle plugin for this library version");
        }
        super.visitEnd();
    }

    private String dotted() {
        return className.replace('/', '.');
    }

    private void unsupported(String method, String what) {
        String where = (method == null) ? dotted() : (dotted() + "." + method);
        if (mode == Mode.STRICT) {
            throw new ApproovWeaveException(where + " " + what + ": the weave cannot protect this engine, update the "
                    + "Approov Gradle plugin for this library version");
        }
        warnings.add(where + " " + what + ": its Cronet requests are NOT protected by Approov, use "
                + "new CronetEngine.Builder(context) or ApproovCronet.newBuilder(...) instead");
    }

    private final class Rewriter extends MethodVisitor {
        // the name of the visited method
        private final String method;

        // rewritten NEW CronetEngine$Builder instructions whose constructor call is still to come
        private int pendingNew;

        // the extra stack slots needed for the site constant
        private int extraStack;

        Rewriter(MethodVisitor next, String method) {
            super(ASM_API, next);
            this.method = method;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            // the NEW type changes with the constructor owner, so the stack keeps its shape
            if ((opcode == Opcodes.NEW) && BUILDER.equals(type)) {
                pendingNew++;
                super.visitTypeInsn(opcode, WEAVE_BUILDER);
                return;
            }
            if ((opcode == Opcodes.NEW) && EXPERIMENTAL_BUILDER.equals(type))
                unsupported(method, "creates an ExperimentalCronetEngine.Builder");
            if ((opcode == Opcodes.NEW) && WEAVE_BUILDER.equals(type))
                alreadyWoven = true;
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if ((opcode == Opcodes.INVOKESPECIAL) && BUILDER.equals(owner) && "<init>".equals(name)) {
                if (pendingNew == 0) {
                    // a constructor chaining to its Cronet builder superclass, reported in visit()
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    return;
                }
                // the NEW is already rewritten, so a constructor other than (Context) fails the build
                if (!CONTEXT_CTOR_DESC.equals(descriptor)) {
                    throw new ApproovWeaveException(dotted() + "." + method + " calls CronetEngine.Builder.<init>"
                            + descriptor + "; only (Context) is supported: update the Approov Gradle plugin, or use "
                            + "ApproovCronet.newBuilder(...)");
                }
                pendingNew--;
                record("new CronetEngine.Builder(Context) -> new ApproovCronetEngineBuilder(Context, site)");
                super.visitLdcInsn(currentSite());
                extraStack = 1;
                super.visitMethodInsn(Opcodes.INVOKESPECIAL, WEAVE_BUILDER, "<init>", WEAVE_BUILDER_CTOR_DESC, false);
                return;
            }
            // a provider's createBuilder() goes through the Approov weaver
            if ((opcode == Opcodes.INVOKEVIRTUAL) && owner.startsWith(CRONET) && CREATE_BUILDER.equals(name)
                    && CREATE_BUILDER_DESC.equals(descriptor)) {
                record(owner.substring(CRONET.length()) + ".createBuilder() -> ApproovCronetWeaver.createBuilder(provider, site)");
                super.visitLdcInsn(currentSite());
                extraStack = 1;
                super.visitMethodInsn(Opcodes.INVOKESTATIC, WEAVER, CREATE_BUILDER, WEAVER_CREATE_BUILDER_DESC, false);
                return;
            }
            // note already woven code and any engine build
            if ((opcode == Opcodes.INVOKESTATIC) && WEAVER.equals(owner))
                alreadyWoven = true;
            if ((opcode == Opcodes.INVOKEVIRTUAL) && (BUILDER.equals(owner) || EXPERIMENTAL_BUILDER.equals(owner))
                    && "build".equals(name) && descriptor.startsWith("()"))
                buildsEngine = true;
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            // the site constant sits on the stack for one instruction
            super.visitMaxs(maxStack + extraStack, maxLocals);
        }

        private String currentSite() {
            return sites.get(sites.size() - 1).id();
        }

        private void record(String kind) {
            sites.add(new Site(className, method, kind));
        }
    }
}
