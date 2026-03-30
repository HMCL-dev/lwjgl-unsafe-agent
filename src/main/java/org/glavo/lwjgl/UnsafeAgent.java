/*
 * Copyright 2026 Glavo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glavo.lwjgl;

import java.io.PrintStream;
import java.lang.classfile.*;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.VarHandle;
import java.lang.reflect.AccessFlag;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;

import static java.lang.constant.ConstantDescs.*;

public final class UnsafeAgent {
    private static final String MEMORY_UTIL_CLASS = "org/lwjgl/system/MemoryUtil";
    private static final ClassDesc CD_Unsafe = ClassDesc.of("jdk.internal.misc.Unsafe");

    private static void log(String msg, PrintStream out) {
        out.println("[lwjgl-unsafe-agent] " + msg);
    }

    private static Instrumentation instrumentation;

    public static void premain(String agentArgs, Instrumentation inst) {
        init(inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        init(inst);
    }

    private static void init(Instrumentation inst) {
        log("LWJGL Unsafe Agent version: " + BuildConfig.PROJECT_VERSION, System.out);

        instrumentation = inst;
        inst.addTransformer(new MemoryUtilTransformer());

        // Ensure the instrumentation is initialized before we try to transform
        VarHandle.fullFence();
    }

    private static final class MemoryUtilTransformer implements ClassFileTransformer {

        private abstract static class MemoryMethodTransform implements Consumer<CodeBuilder> {
            protected static final MethodTypeDesc MTD_getUnsafe = MethodTypeDesc.of(CD_Unsafe);

            protected final MethodTypeDesc type;
            protected final String unsafeMethod;

            private MemoryMethodTransform(MethodTypeDesc type, String unsafeMethod) {
                this.type = type;
                this.unsafeMethod = unsafeMethod;
            }

            static final class Get extends MemoryMethodTransform {
                private final ClassDesc primaryType;
                private final Consumer<CodeBuilder> emitReturn;

                private Get(ClassDesc primaryType, String unsafeMethod, Consumer<CodeBuilder> emitReturn) {
                    super(MethodTypeDesc.of(primaryType, CD_long), unsafeMethod);
                    this.primaryType = primaryType;
                    this.emitReturn = emitReturn;
                }

                @Override
                public void accept(CodeBuilder codeBuilder) {
                    // Push the Unsafe instance
                    codeBuilder.invokestatic(CD_Unsafe, "getUnsafe", MTD_getUnsafe);

                    // Push the address parameter (slot 0, type long)
                    codeBuilder.lload(0);

                    // Get method: invoke getXxx and return the value
                    codeBuilder.invokevirtual(CD_Unsafe, unsafeMethod,
                            MethodTypeDesc.of(primaryType, CD_long));
                    emitReturn.accept(codeBuilder);
                }
            }

            static final class Put extends MemoryMethodTransform {
                private final ClassDesc primaryType;
                private final ObjIntConsumer<CodeBuilder> loadValue;

                private Put(ClassDesc primaryType, String unsafeMethod, ObjIntConsumer<CodeBuilder> loadValue) {
                    super(MethodTypeDesc.of(CD_void, CD_long, primaryType), unsafeMethod);
                    this.primaryType = primaryType;
                    this.loadValue = loadValue;
                }

                @Override
                public void accept(CodeBuilder codeBuilder) {

                    // Push the Unsafe instance
                    codeBuilder.invokestatic(CD_Unsafe, "getUnsafe", MTD_getUnsafe);

                    // Push the address parameter (slot 0, type long)
                    codeBuilder.lload(0);

                    // Put method: load value parameter (slot 2) and invoke putXxx
                    loadValue.accept(codeBuilder, 2);
                    codeBuilder.invokevirtual(CD_Unsafe, unsafeMethod,
                            MethodTypeDesc.of(CD_void, CD_long, primaryType));
                    codeBuilder.return_();
                }
            }
        }

        private final Map<String, MemoryMethodTransform> transforms = Map.ofEntries(
                // memGetXxx
                Map.entry("memGetByte", new MemoryMethodTransform.Get(CD_byte, "getByte", CodeBuilder::ireturn)),
                Map.entry("memGetShort", new MemoryMethodTransform.Get(CD_short, "getShort", CodeBuilder::ireturn)),
                Map.entry("memGetInt", new MemoryMethodTransform.Get(CD_int, "getInt", CodeBuilder::ireturn)),
                Map.entry("memGetLong", new MemoryMethodTransform.Get(CD_long, "getLong", CodeBuilder::lreturn)),
                Map.entry("memGetFloat", new MemoryMethodTransform.Get(CD_float, "getFloat", CodeBuilder::freturn)),
                Map.entry("memGetDouble", new MemoryMethodTransform.Get(CD_double, "getDouble", CodeBuilder::dreturn)),
                Map.entry("memGetAddress", new MemoryMethodTransform.Get(CD_long, "getAddress", CodeBuilder::lreturn)),

                // memPutXxx
                Map.entry("memPutByte", new MemoryMethodTransform.Put(CD_byte, "putByte", CodeBuilder::iload)),
                Map.entry("memPutShort", new MemoryMethodTransform.Put(CD_short, "putShort", CodeBuilder::iload)),
                Map.entry("memPutInt", new MemoryMethodTransform.Put(CD_int, "putInt", CodeBuilder::iload)),
                Map.entry("memPutLong", new MemoryMethodTransform.Put(CD_long, "putLong", CodeBuilder::lload)),
                Map.entry("memPutFloat", new MemoryMethodTransform.Put(CD_float, "putFloat", CodeBuilder::fload)),
                Map.entry("memPutDouble", new MemoryMethodTransform.Put(CD_double, "putDouble", CodeBuilder::dload)),
                Map.entry("memPutAddress", new MemoryMethodTransform.Put(CD_long, "putAddress", CodeBuilder::lload))
        );

        @Override
        public byte[] transform(Module module,
                                ClassLoader loader, String className,
                                Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain,
                                byte[] classfileBuffer) {
            if (!MEMORY_UTIL_CLASS.equals(className)) {
                return null;
            }

            if (instrumentation == null) {
                log("Instrumentation not initialized", System.err);
                return null;
            }

            try {
                Module javaBase = Object.class.getModule();
                String miscPackage = CD_Unsafe.packageName();
                if (!javaBase.isExported(miscPackage, module)) {
                    instrumentation.redefineModule(javaBase,
                            Set.of(),
                            Map.of(miscPackage, Set.of(module)),
                            Map.of(),
                            Set.of(),
                            Map.of()
                    );
                }
            } catch (Exception e) {
                log("Failed to redefine module", System.err);
                e.printStackTrace(System.err);
                return null;
            }

            try {
                var classFile = ClassFile.of();
                byte[] result = classFile.transformClass(classFile.parse(classfileBuffer), this::transform);
                log("Successfully transformed MemoryUtil", System.out);
                return result;
            } catch (Throwable t) {
                log("Failed to transform MemoryUtil", System.err);
                t.printStackTrace(System.err);
                return null;
            }
        }

        private void transform(ClassBuilder classBuilder, ClassElement classElement) {
            if (classElement instanceof MethodModel mm && mm.flags().has(AccessFlag.STATIC)) {
                String methodName = mm.methodName().stringValue();

                MemoryMethodTransform transform = transforms.get(methodName);
                if (transform.type.descriptorString().equals(mm.methodType().stringValue())) {
                    classBuilder.withMethod(mm.methodName().stringValue(), transform.type, mm.flags().flagsMask(), mb -> {
                        for (var me : mm) {
                            if (me instanceof CodeModel) {
                                // Replace the method body with a direct call to `jdk.internal.misc.Unsafe`.
                                mb.withCode(transform);
                            } else {
                                // Preserve non-code method attributes (annotations, etc.)
                                mb.with(me);
                            }
                        }
                    });

                    log("rewrote %s%s".formatted(methodName, transform.type.displayDescriptor()), System.out);
                    return;
                }
            }
            classBuilder.with(classElement);
        }
    }

}
