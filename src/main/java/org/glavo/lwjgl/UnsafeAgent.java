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
import java.lang.reflect.AccessFlag;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;
import java.util.stream.Collectors;

import static java.lang.constant.ConstantDescs.*;

public final class UnsafeAgent {

    private static void log(String msg, PrintStream out) {
        out.println("[lwjgl-unsafe-agent] " + msg);
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        init(inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        init(inst);
    }

    private static void init(Instrumentation inst) {
        log("LWJGL Unsafe Agent version: " + BuildConfig.PROJECT_VERSION, System.out);

        inst.addTransformer(new MemoryUtilTransformer(inst));
    }

    private static final class MemoryUtilTransformer implements ClassFileTransformer {
        private static final String MEMORY_UTIL_CLASS = "org/lwjgl/system/MemoryUtil";
        private static final ClassDesc CD_Unsafe = ClassDesc.of("jdk.internal.misc.Unsafe");

        private final Instrumentation instrumentation;

        MemoryUtilTransformer(Instrumentation instrumentation) {
            this.instrumentation = instrumentation;
        }

        private abstract static class MemoryMethodBody implements Consumer<CodeBuilder> {
            protected static final MethodTypeDesc MTD_getUnsafe = MethodTypeDesc.of(CD_Unsafe);

            protected final MethodTypeDesc type;
            protected final String unsafeMethod;

            private MemoryMethodBody(MethodTypeDesc type, String unsafeMethod) {
                this.type = type;
                this.unsafeMethod = unsafeMethod;
            }

            static final class Get extends MemoryMethodBody {
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

            static final class Put extends MemoryMethodBody {
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

        private final Map<String, MemoryMethodBody> bodies = Map.ofEntries(
                // memGetXxx
                Map.entry("memGetByte", new MemoryMethodBody.Get(CD_byte, "getByte", CodeBuilder::ireturn)),
                Map.entry("memGetShort", new MemoryMethodBody.Get(CD_short, "getShort", CodeBuilder::ireturn)),
                Map.entry("memGetInt", new MemoryMethodBody.Get(CD_int, "getInt", CodeBuilder::ireturn)),
                Map.entry("memGetLong", new MemoryMethodBody.Get(CD_long, "getLong", CodeBuilder::lreturn)),
                Map.entry("memGetFloat", new MemoryMethodBody.Get(CD_float, "getFloat", CodeBuilder::freturn)),
                Map.entry("memGetDouble", new MemoryMethodBody.Get(CD_double, "getDouble", CodeBuilder::dreturn)),
                Map.entry("memGetAddress", new MemoryMethodBody.Get(CD_long, "getAddress", CodeBuilder::lreturn)),

                // memPutXxx
                Map.entry("memPutByte", new MemoryMethodBody.Put(CD_byte, "putByte", CodeBuilder::iload)),
                Map.entry("memPutShort", new MemoryMethodBody.Put(CD_short, "putShort", CodeBuilder::iload)),
                Map.entry("memPutInt", new MemoryMethodBody.Put(CD_int, "putInt", CodeBuilder::iload)),
                Map.entry("memPutLong", new MemoryMethodBody.Put(CD_long, "putLong", CodeBuilder::lload)),
                Map.entry("memPutFloat", new MemoryMethodBody.Put(CD_float, "putFloat", CodeBuilder::fload)),
                Map.entry("memPutDouble", new MemoryMethodBody.Put(CD_double, "putDouble", CodeBuilder::dload)),
                Map.entry("memPutAddress", new MemoryMethodBody.Put(CD_long, "putAddress", CodeBuilder::lload))
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

                    String targetModuleName;
                    if (module.isNamed()) {
                        targetModuleName = module.getName();
                    } else {
                        targetModuleName = "<unnamed module for " + loader + ">";
                    }

                    log("Add exports %s/%s to %s".formatted(javaBase.getName(), miscPackage, targetModuleName), System.out);
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
            } catch (Exception e) {
                log("Failed to transform MemoryUtil", System.err);
                e.printStackTrace(System.err);
                return null;
            }
        }

        private void transform(ClassBuilder classBuilder, ClassElement classElement) {
            if (classElement instanceof MethodModel methodModel) {
                String methodName = methodModel.methodName().stringValue();
                AccessFlags methodFlags = methodModel.flags();

                MemoryMethodBody body = bodies.get(methodName);
                if (methodFlags.has(AccessFlag.STATIC)
                        && body != null
                        && body.type.descriptorString().equals(methodModel.methodType().stringValue())) {
                    classBuilder.withMethod(methodName, body.type, methodFlags.flagsMask(), mb -> {
                        for (MethodElement me : methodModel) {
                            if (me instanceof CodeModel) {
                                // Replace the method body with a direct call to `jdk.internal.misc.Unsafe`.
                                mb.withCode(body);
                            } else {
                                // Preserve non-code method attributes (annotations, etc.)
                                mb.with(me);
                            }
                        }
                    });

                    log("Rewrote %s.%s(%s)".formatted(
                            MEMORY_UTIL_CLASS.replace('/', '.'),
                            methodName,
                            body.type.parameterList().stream()
                                    .map(ClassDesc::displayName)
                                    .collect(Collectors.joining(", "))
                            ), System.out);
                    return;
                }
            }
            classBuilder.with(classElement);
        }
    }

}
