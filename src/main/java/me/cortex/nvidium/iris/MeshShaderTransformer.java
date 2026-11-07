package me.cortex.nvidium.iris;

import io.github.douira.glsl_transformer.ast.node.Identifier;
import io.github.douira.glsl_transformer.ast.node.Profile;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.VersionStatement;
import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.declaration.Declaration;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.declaration.FunctionParameter;
import io.github.douira.glsl_transformer.ast.node.declaration.InterfaceBlockDeclaration;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.declaration.VariableDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExtensionDirective;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.statement.loop.ForLoopStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.DeclarationStatement;
import io.github.douira.glsl_transformer.ast.node.type.initializer.ExpressionInitializer;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.TypeQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.TypeQualifierPart;
import io.github.douira.glsl_transformer.ast.node.type.struct.StructDeclarator;
import io.github.douira.glsl_transformer.ast.node.type.struct.StructMember;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.print.PrintType;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites a shader pack's terrain vertex shader, as patched by Iris for Sodium, into a form that runs as a plain
 * function inside a mesh shader:
 * <ul>
 *     <li>vertex inputs ({@code in}) become plain globals, filled by Nvidium from its terrain buffer before each call;</li>
 *     <li>outputs ({@code out} variables and blocks) are written to {@code _nv_vs_*} globals, and matching per-vertex
 *     mesh outputs plus copy statements are generated;</li>
 *     <li>{@code iris_SodiumPushConstants} becomes plain globals, so the region offset can be set per section;</li>
 *     <li>built-ins such as {@code gl_Position} and {@code gl_VertexID} are redirected to globals;</li>
 *     <li>{@code main} is renamed so the mesh shader can call it once per vertex;</li>
 *     <li>initializers of global variables are moved into statements that are re-run before every vertex (drivers
 *     evaluate them once per invocation, and a mesh shader invocation runs the pack code for several vertices).</li>
 * </ul>
 * Has no Minecraft or Iris dependencies so it can be unit tested.
 */
public final class MeshShaderTransformer {
    public static final String PACK_MAIN = "_nv_pack_main";
    public static final String VERTEX_SLOT = "_nv_vid";
    public static final String POSITION = "_nv_vs_Position";
    public static final String VERTEX_ID = "_nv_VertexID";
    private static final String OUT_PREFIX = "_nv_vs_";

    private static final Pattern VERSION_PATTERN = Pattern.compile("#version\\s+(\\d+)");
    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^\\s*#version[^\\n]*\\n?");
    private static final Pattern UNSIZED_ARRAY = Pattern.compile("\\[\\s*]");

    /** Uniform blocks that hold per-draw values which Nvidium has to vary per section, so they become globals. */
    private static final Set<String> BLOCKS_TO_GLOBALS = Set.of("iris_SodiumPushConstants");

    private MeshShaderTransformer() {}

    public static PackVertexStage transform(String patchedVertexSource) {
        Matcher matcher = VERSION_PATTERN.matcher(patchedVertexSource);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Vertex shader has no #version directive");
        }

        var transformer = new SingleASTTransformer<>();
        transformer.setRootSupplier(RootSupplier.PREFIX_UNORDERED_ED_EXACT);
        transformer.setPrintType(PrintType.SIMPLE);
        transformer.getLexer().version = Version.fromNumber(Integer.parseInt(matcher.group(1)));

        var work = new Work(transformer);
        transformer.setTransformation((tree, root) -> root.indexBuildSession(() -> work.run(tree, root)));
        String printed = transformer.transform(patchedVertexSource);
        if (work.failure != null) {
            throw work.failure;
        }

        String body = VERSION_LINE.matcher(printed).replaceFirst("");
        return new PackVertexStage(
                List.copyOf(work.extensions),
                body,
                java.util.Collections.unmodifiableMap(work.inputs),
                List.copyOf(work.outputDeclarations),
                List.copyOf(work.outputCopies),
                List.copyOf(work.globalInitializers),
                Set.copyOf(work.globals)
        );
    }

    private static String print(ASTNode node) {
        if (node == null) {
            return "";
        }
        return ASTPrinter.printSimple(node).replaceAll("\\s+", " ").trim();
    }

    private static StorageType storageType(TypeQualifier qualifier) {
        if (qualifier == null) {
            return null;
        }
        for (TypeQualifierPart part : qualifier.getParts()) {
            if (part instanceof StorageQualifier storage) {
                return storage.storageType;
            }
        }
        return null;
    }

    private static final class Work {
        private final SingleASTTransformer<?> parser;
        private TranslationUnit tree;
        private Root root;

        final List<String> extensions = new ArrayList<>();
        final Map<String, String> inputs = new LinkedHashMap<>();
        final List<String> outputDeclarations = new ArrayList<>();
        final List<String> outputCopies = new ArrayList<>();
        final List<String> globalInitializers = new ArrayList<>();
        final Set<String> globals = new LinkedHashSet<>();
        /** Output names whose references must be redirected: original name to replacement expression. */
        final Map<String, String> referenceRenames = new LinkedHashMap<>();
        RuntimeException failure;

        Work(SingleASTTransformer<?> parser) {
            this.parser = parser;
        }

        void run(TranslationUnit tree, Root root) {
            this.tree = tree;
            this.root = root;
            try {
                collectExtensions();
                rewriteDeclarations();
                referenceRenames.forEach(this::renameGlobalReferences);
                redirectBuiltins();
                if (!root.rename("main", PACK_MAIN)) {
                    throw new IllegalArgumentException("Vertex shader has no main function");
                }
                moveGlobalInitializers();

                VersionStatement version = tree.getVersionStatement();
                version.version = Version.GLSL46;
                version.profile = Profile.CORE;
            } catch (RuntimeException e) {
                // Exceptions thrown inside the transformation may be wrapped or swallowed; report them afterwards
                failure = e;
            }
        }

        private void collectExtensions() {
            for (ExternalDeclaration declaration : List.copyOf(tree.getChildren())) {
                if (declaration instanceof ExtensionDirective extension) {
                    extensions.add(print(extension));
                    extension.detachAndDelete();
                }
            }
        }

        private void rewriteDeclarations() {
            for (ExternalDeclaration external : List.copyOf(tree.getChildren())) {
                if (!(external instanceof DeclarationExternalDeclaration declarationExternal)) {
                    continue;
                }
                Declaration declaration = declarationExternal.getDeclaration();
                if (declaration instanceof TypeAndInitDeclaration typeAndInit) {
                    rewriteTypeAndInit(declarationExternal, typeAndInit);
                } else if (declaration instanceof InterfaceBlockDeclaration block) {
                    rewriteBlock(declarationExternal, block);
                } else if (declaration instanceof VariableDeclaration) {
                    // "invariant gl_Position;" and similar re-declarations: they only affect invariance, and would
                    // refer to names that no longer exist once outputs and built-ins are redirected
                    declarationExternal.detachAndDelete();
                }
            }
        }

        private void rewriteTypeAndInit(DeclarationExternalDeclaration external, TypeAndInitDeclaration declaration) {
            TypeQualifier qualifier = declaration.getType().getTypeQualifier();
            StorageType storage = storageType(qualifier);
            String type = print(declaration.getType().getTypeSpecifier());

            if (storage == StorageType.IN || storage == StorageType.ATTRIBUTE) {
                var replacement = new StringBuilder(type).append(' ');
                boolean first = true;
                for (DeclarationMember member : declaration.getMembers()) {
                    String name = member.getName().getName();
                    String array = print(member.getArraySpecifier());
                    inputs.put(name, type + array);
                    replacement.append(first ? "" : ", ").append(name).append(array);
                    first = false;
                }
                replace(external, List.of(replacement.append(';').toString()));
            } else if (storage == StorageType.OUT || storage == StorageType.VARYING) {
                String qualifierText = print(qualifier).replaceAll("\\bvarying\\b", "out");
                var globalsText = new ArrayList<String>();
                for (DeclarationMember member : declaration.getMembers()) {
                    String name = member.getName().getName();
                    if (name.startsWith("gl_")) {
                        continue; // re-declared built-in output, handled by redirectBuiltins()
                    }
                    String array = print(member.getArraySpecifier());
                    outputDeclarations.add(qualifierText + " " + type + " " + name + "[]" + array + ";");
                    outputCopies.add(name + "[" + VERTEX_SLOT + "] = " + OUT_PREFIX + name + ";");
                    globalsText.add(type + " " + OUT_PREFIX + name + array + ";");
                    referenceRenames.put(name, OUT_PREFIX + name);
                }
                replace(external, globalsText);
            } else if (storage == null) {
                for (DeclarationMember member : declaration.getMembers()) {
                    globals.add(member.getName().getName());
                }
            }
        }

        private void rewriteBlock(DeclarationExternalDeclaration external, InterfaceBlockDeclaration block) {
            String blockName = block.getBlockName().getName();
            StorageType storage = storageType(block.getTypeQualifier());

            if (BLOCKS_TO_GLOBALS.contains(blockName)) {
                var globalsText = new ArrayList<String>();
                for (StructMember member : block.getStructBody().getMembers()) {
                    String type = print(member.getType().getTypeSpecifier());
                    for (StructDeclarator declarator : member.getDeclarators()) {
                        String name = declarator.getName().getName();
                        globalsText.add(type + " " + name + print(declarator.getArraySpecifier()) + ";");
                        globals.add(name);
                    }
                }
                replace(external, globalsText);
                return;
            }

            if (storage == StorageType.IN) {
                throw new UnsupportedOperationException("Vertex input blocks are not supported (block " + blockName + ")");
            }
            if (storage != StorageType.OUT) {
                return; // uniform and buffer blocks stay as they are
            }
            if (blockName.equals("gl_PerVertex")) {
                external.detachAndDelete(); // re-declared built-in block, handled by redirectBuiltins()
                return;
            }
            if (block.getArraySpecifier() != null) {
                throw new UnsupportedOperationException("Arrayed output blocks are not supported (block " + blockName + ")");
            }

            var instance = block.getVariableName();
            String outputInstance = instance != null ? instance.getName() : "_nv_out_" + blockName;
            var members = new StringBuilder();
            var globalsText = new ArrayList<String>();
            var structMembers = new StringBuilder();
            for (StructMember member : block.getStructBody().getMembers()) {
                members.append(print(member)).append(' ');
                String type = print(member.getType().getTypeSpecifier());
                for (StructDeclarator declarator : member.getDeclarators()) {
                    String name = declarator.getName().getName();
                    String array = print(declarator.getArraySpecifier());
                    if (instance != null) {
                        structMembers.append(type).append(' ').append(name).append(array).append("; ");
                        outputCopies.add(outputInstance + "[" + VERTEX_SLOT + "]." + name + " = " + OUT_PREFIX + outputInstance + "." + name + ";");
                    } else {
                        globalsText.add(type + " " + OUT_PREFIX + name + array + ";");
                        outputCopies.add(outputInstance + "[" + VERTEX_SLOT + "]." + name + " = " + OUT_PREFIX + name + ";");
                        referenceRenames.put(name, OUT_PREFIX + name);
                    }
                }
            }
            outputDeclarations.add(print(block.getTypeQualifier()) + " " + blockName + " { " + members.toString().trim() + " } " + outputInstance + "[];");

            if (instance != null) {
                String structName = "_nv_struct_" + blockName;
                globalsText.add("struct " + structName + " { " + structMembers.toString().trim() + " };");
                globalsText.add(structName + " " + OUT_PREFIX + outputInstance + ";");
                referenceRenames.put(outputInstance, OUT_PREFIX + outputInstance);
            }
            replace(external, globalsText);
        }

        private void redirectBuiltins() {
            var builtins = new ArrayList<String>();
            builtins.add("vec4 " + POSITION + ";");
            root.rename("gl_Position", POSITION);
            if (root.rename("gl_PointSize", "_nv_vs_PointSize")) {
                builtins.add("float _nv_vs_PointSize;");
            }
            // Clip and cull distances are accepted but not forwarded yet
            if (root.rename("gl_ClipDistance", "_nv_vs_ClipDistance")) {
                builtins.add("float _nv_vs_ClipDistance[8];");
            }
            if (root.rename("gl_CullDistance", "_nv_vs_CullDistance")) {
                builtins.add("float _nv_vs_CullDistance[8];");
            }
            boolean vertexId = root.rename("gl_VertexID", VERTEX_ID);
            vertexId |= root.rename("gl_VertexIndex", VERTEX_ID);
            if (vertexId) {
                builtins.add("int " + VERTEX_ID + ";");
            }
            for (String constant : List.of("gl_InstanceID", "gl_InstanceIndex", "gl_DrawID", "gl_BaseVertex", "gl_BaseInstance")) {
                String replacement = "_nv_zero_" + constant.substring(3);
                if (root.rename(constant, replacement)) {
                    builtins.add("const int " + replacement + " = 0;");
                }
            }
            var nodes = builtins.stream().map(text -> parser.parseExternalDeclaration(root, text)).toList();
            tree.getChildren().addAll(firstDeclarationIndex(), nodes);
        }

        /**
         * Global variables with initializers get them evaluated at the start of each shader invocation. The pack's
         * code runs several times per mesh shader invocation, and Iris's initializers read the vertex inputs (for
         * example {@code iris_Entity}, {@code iris_MidTex}), so turn them into statements re-run before each vertex.
         */
        private void moveGlobalInitializers() {
            for (ExternalDeclaration external : tree.getChildren()) {
                if (!(external instanceof DeclarationExternalDeclaration declarationExternal)
                        || !(declarationExternal.getDeclaration() instanceof TypeAndInitDeclaration declaration)) {
                    continue;
                }
                if (storageType(declaration.getType().getTypeQualifier()) != null) {
                    continue; // const, uniform, ... keep their initializers
                }
                String type = print(declaration.getType().getTypeSpecifier());
                for (DeclarationMember member : declaration.getMembers()) {
                    // An array sized only by its initializer (e.g. "vec3[] t = vec3[](...)") would lose its size.
                    // These are constant tables in practice, so evaluating them once per invocation is fine.
                    if (UNSIZED_ARRAY.matcher(type + print(member.getArraySpecifier())).find()) {
                        continue;
                    }
                    if (member.getInitializer() instanceof ExpressionInitializer initializer) {
                        globalInitializers.add(member.getName().getName() + " = " + print(initializer.getExpression()) + ";");
                        member.setInitializer(null);
                    }
                }
            }
        }

        /**
         * Points references to a global (an output being redirected) at its replacement, leaving references to
         * function parameters, locals and loop variables that shadow it alone. Packs often reuse output names such
         * as {@code normal} for parameters and locals.
         */
        private void renameGlobalReferences(String name, String replacement) {
            for (Identifier identifier : List.copyOf(root.identifierIndex.get(name))) {
                if (identifier.getParent() instanceof ReferenceExpression reference && !isShadowed(reference, name)) {
                    identifier.setName(replacement);
                }
            }
        }

        /** Whether a declaration in an enclosing scope hides the global {@code name} at this node (GLSL scoping). */
        private static boolean isShadowed(ASTNode node, String name) {
            ASTNode child = node;
            ASTNode parent = node.getParent();
            while (parent != null) {
                if (parent instanceof CompoundStatement block) {
                    // Only declarations before the statement containing the reference are in scope; a variable's
                    // scope starts after its declarator, so "vec3 normal = normal;" still reads the global
                    for (Statement statement : block.getStatements()) {
                        if (statement == child) {
                            break;
                        }
                        if (statement instanceof DeclarationStatement declaration && declares(declaration.getDeclaration(), name)) {
                            return true;
                        }
                    }
                } else if (parent instanceof ForLoopStatement loop) {
                    if (loop.getInitDeclaration() != null && loop.getInitDeclaration() != child && declares(loop.getInitDeclaration(), name)) {
                        return true;
                    }
                } else if (parent instanceof FunctionDefinition function) {
                    for (FunctionParameter parameter : function.getFunctionPrototype().getParameters()) {
                        if (parameter.getName() != null && parameter.getName().getName().equals(name)) {
                            return true;
                        }
                    }
                    return false;
                }
                child = parent;
                parent = parent.getParent();
            }
            return false;
        }

        private static boolean declares(Declaration declaration, String name) {
            if (declaration instanceof TypeAndInitDeclaration typeAndInit) {
                for (DeclarationMember member : typeAndInit.getMembers()) {
                    if (member.getName().getName().equals(name)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private void replace(DeclarationExternalDeclaration external, List<String> declarations) {
            int index = tree.getChildren().indexOf(external);
            var nodes = declarations.stream().map(text -> parser.parseExternalDeclaration(root, text)).toList();
            tree.getChildren().addAll(index, nodes);
            external.detachAndDelete();
        }

        private int firstDeclarationIndex() {
            var children = tree.getChildren();
            for (int i = 0; i < children.size(); i++) {
                if (!(children.get(i) instanceof ExtensionDirective)) {
                    return i;
                }
            }
            return children.size();
        }
    }
}
