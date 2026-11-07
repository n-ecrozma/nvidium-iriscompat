package me.cortex.nvidium.mixin.iris;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that every injector in Nvidium's Iris mixins points at a method (and call) that exists in the Iris jar we
 * build against, so a changed Iris signature fails the build instead of crashing the game.
 */
class IrisMixinTargetsTest {
    private static final List<String> MIXINS = List.of("MixinIrisRenderingPipeline", "MixinShaderCreator");
    private static final List<String> INJECTORS = List.of(
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
            "Lorg/spongepowered/asm/mixin/injection/Inject;");

    @Test
    void injectorsMatchIris() throws IOException {
        String irisJar = System.getProperty("nvidium.test.irisJar");
        assertNotNull(irisJar, "nvidium.test.irisJar not set (see build.gradle)");

        int checked = 0;
        try (ZipFile iris = new ZipFile(irisJar)) {
            for (String mixinName : MIXINS) {
                ClassNode mixin = readOwn("me/cortex/nvidium/mixin/iris/" + mixinName);
                String target = mixinTarget(mixin);
                ClassNode targetClass = read(iris, target);

                for (MethodNode handler : mixin.methods) {
                    for (AnnotationNode annotation : annotations(handler)) {
                        if (!INJECTORS.contains(annotation.desc)) {
                            continue;
                        }
                        String where = mixinName + "." + handler.name;
                        List<MethodNode> methods = new ArrayList<>();
                        for (String selector : stringList(value(annotation, "method"))) {
                            List<MethodNode> matched = targetClass.methods.stream().filter(m -> matches(m, selector)).toList();
                            assertFalse(matched.isEmpty(), where + ": no method '" + selector + "' in " + target);
                            methods.addAll(matched);
                        }
                        for (Object at : (List<?>) value(annotation, "at")) {
                            AnnotationNode atNode = (AnnotationNode) at;
                            if ("INVOKE".equals(value(atNode, "value"))) {
                                String call = (String) value(atNode, "target");
                                assertTrue(methods.stream().anyMatch(m -> calls(m, call)),
                                        where + ": no call to " + call + " in the selected method(s) of " + target);
                            }
                        }
                        checked++;
                    }
                }
            }
        }
        assertEquals(3, checked, "expected to check every injector");
    }

    private static boolean matches(MethodNode method, String selector) {
        int paren = selector.indexOf('(');
        if (paren < 0) {
            return method.name.equals(selector);
        }
        return method.name.equals(selector.substring(0, paren)) && method.desc.equals(selector.substring(paren));
    }

    private static boolean calls(MethodNode method, String target) {
        // Target format: Lowner;name(desc)ret
        int semicolon = target.indexOf(';');
        int paren = target.indexOf('(');
        String owner = target.substring(1, semicolon);
        String name = target.substring(semicolon + 1, paren);
        String desc = target.substring(paren);
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(desc)) {
                return true;
            }
        }
        return false;
    }

    private static String mixinTarget(ClassNode mixin) {
        for (AnnotationNode annotation : annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations)) {
            if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) {
                List<?> targets = (List<?>) value(annotation, "value");
                return ((Type) targets.getFirst()).getInternalName();
            }
        }
        throw new AssertionError(mixin.name + " has no @Mixin");
    }

    private static List<AnnotationNode> annotations(MethodNode method) {
        return annotations(method.visibleAnnotations, method.invisibleAnnotations);
    }

    private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        var all = new ArrayList<AnnotationNode>();
        if (visible != null) all.addAll(visible);
        if (invisible != null) all.addAll(invisible);
        return all;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values != null) {
            for (int i = 0; i < annotation.values.size(); i += 2) {
                if (annotation.values.get(i).equals(key)) {
                    Object value = annotation.values.get(i + 1);
                    return value instanceof String[] enumValue ? enumValue[1] : value;
                }
            }
        }
        return null;
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String.class::cast).toList();
        }
        return value == null ? List.of() : List.of((String) value);
    }

    private static ClassNode readOwn(String internalName) throws IOException {
        try (InputStream in = IrisMixinTargetsTest.class.getResourceAsStream("/" + internalName + ".class")) {
            assertNotNull(in, "compiled " + internalName + " not found");
            return node(in.readAllBytes());
        }
    }

    private static ClassNode read(ZipFile jar, String internalName) throws IOException {
        var entry = jar.getEntry(internalName + ".class");
        assertNotNull(entry, internalName + " not found in the Iris jar");
        try (InputStream in = jar.getInputStream(entry)) {
            return node(in.readAllBytes());
        }
    }

    private static ClassNode node(byte[] bytes) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
