package com.pumpkin.intellij.api.postprocessor;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiDeclarationStatement;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiSwitchBlock;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.pumpkin.intellij.api.ApiEndpointResolver;
import com.pumpkin.intellij.api.PumpkinPostprocessorResolver;
import com.pumpkin.intellij.endpoint.NameUtils;
import com.pumpkin.intellij.endpoint.SwitchMethodEditor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates (or extends) an {@code AbstractApiPostProcessor} subclass from an {@link
 * AddPostProcessorDialog} submission - either splicing one new {@code case} into an
 * already-existing PostProcessor's {@code postProcess} switch, or - if none exists yet for the
 * chosen proxy's {@code ApiNotation} - building the whole class from scratch (mirroring {@code
 * ApiProxyCodeGenerator}'s whole-class-as-a-string style) and wiring it into {@code
 * ApiPostprocessorDispatcher} so it's live immediately rather than dead code.
 *
 * <p>Every {@link PsiClass}/{@link PsiMethod} handle here is re-fetched fresh (by qualified name,
 * via {@link #requireClass}) immediately before each {@code SwitchMethodEditor} call, rather than
 * reused across edits - inserting text triggers a PSI commit that can invalidate previously held
 * elements in the same file, per {@code SwitchMethodEditor}'s own class doc.
 */
public final class PostProcessorCodeGenerator {

    private static final String POSTPROCESSOR_PACKAGE = "at.compax.rp.test.services.api.postprocess";
    private static final String ASSERT_FQN = "org.junit.Assert";
    private static final String RPTA_CONTEXT_FQN = "at.compax.rp.test.context.RPTAContext";
    private static final String AUTOWIRED_FQN = "org.springframework.beans.factory.annotation.Autowired";

    /** A Value that is *only* {@code %name%} - the whole cell - compares against that named context parameter instead of a literal. */
    private static final Pattern CONTEXT_PLACEHOLDER = Pattern.compile("^%([a-zA-Z_][a-zA-Z0-9_]*)%$");

    private PostProcessorCodeGenerator() {}

    public static void generate(@NotNull Project project, @NotNull NewPostProcessorSpec spec) {
        PsiClass proxyClass = spec.proxyClass();
        PsiEnumConstant endpoint = spec.endpoint();
        PsiClass endpointEnum = endpoint.getContainingClass();
        if (endpointEnum == null) {
            throw new IllegalStateException("Could not determine the endpoint enum containing " + endpoint.getName() + ".");
        }
        String proxySimpleName = proxyClass.getName();
        String proxyFqn = proxyClass.getQualifiedName();
        if (proxySimpleName == null || proxyFqn == null) {
            throw new IllegalStateException("Could not determine the proxy class name.");
        }
        String base = proxySimpleName.endsWith("Proxy")
                ? proxySimpleName.substring(0, proxySimpleName.length() - "Proxy".length())
                : proxySimpleName;
        String postProcessorClassName = base + "Postprocessor";
        String switchCastType = proxySimpleName + "." + endpointEnum.getName();
        String endpointName = endpoint.getName();

        WriteCommandAction.runWriteCommandAction(project, "Add PostProcessor", null, () -> {
            PsiClass existing = PumpkinPostprocessorResolver.findExistingPostProcessorClass(project, spec.apiNotation());
            VirtualFile result = existing != null
                    ? addCaseToExisting(project, existing, endpointName, spec.mappings())
                    : createNewPostProcessorAndWireDispatcher(project, proxyClass, proxyFqn, postProcessorClassName,
                            switchCastType, endpointName, spec.mappings(), spec.apiNotation());
            FileEditorManager.getInstance(project).openFile(result, true);
        }, proxyClass.getContainingFile());
    }

    // -------------------------------------------------------------------------
    // Branch 1: PostProcessor already exists - just splice a case
    // -------------------------------------------------------------------------

    private static @NotNull VirtualFile addCaseToExisting(@NotNull Project project, @NotNull PsiClass postProcessorClass,
                                                          @NotNull String endpointName, @NotNull List<FieldMapping> mappings) {
        String qualifiedName = postProcessorClass.getQualifiedName();
        PsiFile containingFile = postProcessorClass.getContainingFile();
        VirtualFile virtualFile = containingFile == null ? null : containingFile.getVirtualFile();
        if (qualifiedName == null || virtualFile == null) {
            throw new IllegalStateException("Could not resolve the existing PostProcessor class's file.");
        }

        PsiMethod[] methods = postProcessorClass.findMethodsByName("postProcess", false);
        if (methods.length == 0) {
            throw new IllegalStateException(postProcessorClass.getName() + " has no postProcess(...) method to add a case to.");
        }
        String respVar = findResponseJsonVariableName(methods[0]);
        String caseText = buildCaseText(endpointName, respVar, mappings);

        if (usesAssert(mappings)) {
            SwitchMethodEditor.ensureImport(project, (PsiJavaFile) requireClass(project, qualifiedName).getContainingFile(), ASSERT_FQN);
        }
        if (usesContext(mappings)) {
            SwitchMethodEditor.ensureImport(project, (PsiJavaFile) requireClass(project, qualifiedName).getContainingFile(), RPTA_CONTEXT_FQN);
        }

        // Guard against whatever case used to be immediately before "default:" silently falling
        // through into the one we're about to splice in - see
        // SwitchMethodEditor.ensurePreviousCaseTerminates's own doc for why this only mattered
        // once a new case starts landing right after it. postProcess(...) is always void, so a
        // bare "return;" is always the right terminator to add if one's missing.
        SwitchMethodEditor.ensurePreviousCaseTerminates(project, requirePostProcessMethod(project, qualifiedName), "return;");

        SwitchMethodEditor.upsertCase(project, requireClass(project, qualifiedName), "postProcess",
                "/* unreachable - postProcess(...) already exists on every AbstractApiPostProcessor subclass */", caseText);

        return virtualFile;
    }

    private static @NotNull PsiMethod requirePostProcessMethod(@NotNull Project project, @NotNull String qualifiedName) {
        PsiMethod[] methods = requireClass(project, qualifiedName).findMethodsByName("postProcess", false);
        if (methods.length == 0) {
            throw new IllegalStateException("postProcess(...) disappeared from " + qualifiedName + ".");
        }
        return methods[0];
    }

    // -------------------------------------------------------------------------
    // Branch 2: no PostProcessor yet - create the whole class + wire the dispatcher
    // -------------------------------------------------------------------------

    private static @NotNull VirtualFile createNewPostProcessorAndWireDispatcher(
            @NotNull Project project, @NotNull PsiClass proxyClass, @NotNull String proxyFqn,
            @NotNull String postProcessorClassName, @NotNull String switchCastType, @NotNull String endpointName,
            @NotNull List<FieldMapping> mappings, @NotNull String apiNotation) {

        String source = buildPostProcessorSource(proxyFqn, postProcessorClassName, switchCastType, endpointName, mappings);

        List<PsiClass> existingPostProcessors =
                ApiEndpointResolver.findDirectSubclasses(project, PumpkinPostprocessorResolver.ABSTRACT_POSTPROCESSOR_FQN);
        PsiClass anchor = existingPostProcessors.isEmpty() ? proxyClass : existingPostProcessors.get(0);

        PsiFile file = createJavaFile(project, POSTPROCESSOR_PACKAGE, postProcessorClassName, source, anchor);
        if (file == null || file.getVirtualFile() == null) {
            throw new IllegalStateException("Could not create " + postProcessorClassName + ".java.");
        }
        VirtualFile virtualFile = file.getVirtualFile();

        wireDispatcher(project, apiNotation, postProcessorClassName);

        return virtualFile;
    }

    /**
     * Adds an {@code @Autowired} field of the new PostProcessor (if the dispatcher doesn't
     * already have one) and a {@code case <apiNotation>: return Optional.of(<field>);} to
     * whichever of the dispatcher's own methods contains its {@code ApiNotation} switch - found
     * the same tolerant way {@code PumpkinPostprocessorResolver.findPostProcessorClass} does (by
     * scanning every method for one with a switch, not an assumed method name).
     */
    private static void wireDispatcher(@NotNull Project project, @NotNull String apiNotation,
                                       @NotNull String postProcessorClassName) {
        PsiClass dispatcher = requireClass(project, PumpkinPostprocessorResolver.DISPATCHER_FQN);
        String dispatchMethodName = findDispatchMethodName(dispatcher);
        if (dispatchMethodName == null) {
            throw new IllegalStateException(postProcessorClassName + " was created, but no existing ApiNotation "
                    + "switch could be found inside " + PumpkinPostprocessorResolver.DISPATCHER_FQN
                    + " to wire it into - add the @Autowired field and its case by hand.");
        }

        String fieldName = existingFieldNameFor(dispatcher, postProcessorClassName);
        if (fieldName == null) {
            fieldName = NameUtils.toCamelCase(postProcessorClassName);
            SwitchMethodEditor.appendMethod(project, dispatcher,
                    "@Autowired\nprivate " + postProcessorClassName + " " + fieldName + ";");

            SwitchMethodEditor.ensureImport(project,
                    (PsiJavaFile) requireClass(project, PumpkinPostprocessorResolver.DISPATCHER_FQN).getContainingFile(),
                    AUTOWIRED_FQN);
            SwitchMethodEditor.ensureImport(project,
                    (PsiJavaFile) requireClass(project, PumpkinPostprocessorResolver.DISPATCHER_FQN).getContainingFile(),
                    POSTPROCESSOR_PACKAGE + "." + postProcessorClassName);
        }

        String caseText = "case " + apiNotation + ":\n  return Optional.of(" + fieldName + ");";
        SwitchMethodEditor.upsertCase(project, requireClass(project, PumpkinPostprocessorResolver.DISPATCHER_FQN),
                dispatchMethodName, "/* unreachable - the dispatch method was already found to exist */", caseText);
    }

    private static @Nullable String findDispatchMethodName(@NotNull PsiClass dispatcher) {
        for (PsiMethod method : dispatcher.getMethods()) {
            PsiCodeBlock body = method.getBody();
            if (body != null && PsiTreeUtil.findChildOfType(body, PsiSwitchBlock.class) != null) {
                return method.getName();
            }
        }
        return null;
    }

    private static @Nullable String existingFieldNameFor(@NotNull PsiClass dispatcher, @NotNull String className) {
        for (PsiField field : dispatcher.getFields()) {
            if (className.equals(field.getType().getPresentableText())) {
                return field.getName();
            }
        }
        return null;
    }

    private static @NotNull PsiClass requireClass(@NotNull Project project, @NotNull String qualifiedName) {
        PsiClass psiClass = JavaPsiFacade.getInstance(project).findClass(qualifiedName, GlobalSearchScope.allScope(project));
        if (psiClass == null) {
            throw new IllegalStateException("Could not find " + qualifiedName + " anymore.");
        }
        return psiClass;
    }

    /**
     * Mirrors {@code SwitchMethodEditor.findMapVariableName}'s own style: scans {@code
     * postProcess}'s own declared local variables for the {@code Optional<JSONObject>} one every
     * case body calls {@code .ifPresent(...)} on - read back rather than assumed to be named
     * {@code responseJson}, since an existing PostProcessor may have named it differently.
     */
    private static @NotNull String findResponseJsonVariableName(@NotNull PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            throw new IllegalStateException(method.getName() + "() has no body.");
        }
        for (PsiStatement stmt : body.getStatements()) {
            if (stmt instanceof PsiDeclarationStatement decl) {
                for (PsiElement el : decl.getDeclaredElements()) {
                    if (el instanceof PsiLocalVariable v
                            && v.getType().getPresentableText().contains("JSONObject")) {
                        return v.getName();
                    }
                }
            }
        }
        throw new IllegalStateException(
                "Could not find the Optional<JSONObject> response variable in " + method.getName() + "().");
    }

    // -------------------------------------------------------------------------
    // Case / mapping text
    // -------------------------------------------------------------------------

    private static boolean usesAssert(@NotNull List<FieldMapping> mappings) {
        return mappings.stream().anyMatch(m -> m.action() == MappingAction.EQUALS);
    }

    private static boolean usesContext(@NotNull List<FieldMapping> mappings) {
        return mappings.stream()
                .anyMatch(m -> m.action() == MappingAction.EQUALS && CONTEXT_PLACEHOLDER.matcher(m.value()).matches());
    }

    private static @NotNull String buildCaseText(@NotNull String endpointName, @NotNull String respVar,
                                                 @NotNull List<FieldMapping> mappings) {
        StringBuilder sb = new StringBuilder();
        sb.append("case ").append(endpointName).append(":\n");
        sb.append(respVar).append(".ifPresent(resp -> {\n");
        sb.append(buildMappingStatements(mappings));
        sb.append("});\n");
        sb.append("return;");
        return sb.toString();
    }

    private static @NotNull String buildMappingStatements(@NotNull List<FieldMapping> mappings) {
        StringBuilder sb = new StringBuilder();
        for (FieldMapping mapping : mappings) {
            String accessExpr = buildAccessExpression(mapping.fieldPath(), mapping.leafType());
            if (mapping.action() == MappingAction.SAVE_TO_CONTEXT) {
                sb.append("writeParamToContext(\"").append(escapeJavaString(mapping.value())).append("\", ")
                        .append(accessExpr).append(");\n");
            } else {
                // The 3-arg assertEquals(message, expected, actual) - the leading field-path
                // string is JUnit's failure-message label, not a third comparand, so a failed
                // assertion tells the user which response field didn't match instead of just
                // showing two raw values with no context.
                String expectedExpr = buildExpectedExpression(mapping.value());
                sb.append("Assert.assertEquals(\"").append(escapeJavaString(mapping.fieldPath())).append("\", ")
                        .append(expectedExpr).append(", ").append(accessExpr).append(");\n");
            }
        }
        return sb.toString();
    }

    /** {@code "car.carname"} + STRING -> {@code resp.getJSONObject("car").getString("carname")}; a non-string leaf is wrapped in {@code String.valueOf(...)}. */
    private static @NotNull String buildAccessExpression(@NotNull String fieldPath, @NotNull JsonSampleFields.LeafType leafType) {
        String[] segments = fieldPath.split("\\.");
        StringBuilder expr = new StringBuilder("resp");
        for (int i = 0; i < segments.length - 1; i++) {
            expr.append(".getJSONObject(\"").append(segments[i]).append("\")");
        }
        String leaf = segments[segments.length - 1];
        if (leafType == JsonSampleFields.LeafType.STRING) {
            expr.append(".getString(\"").append(leaf).append("\")");
            return expr.toString();
        }
        expr.append(".get(\"").append(leaf).append("\")");
        return "String.valueOf(" + expr + ")";
    }

    /** A {@code %name%}-shaped value compares against that context parameter's current value instead of a literal. */
    private static @NotNull String buildExpectedExpression(@NotNull String value) {
        Matcher m = CONTEXT_PLACEHOLDER.matcher(value);
        if (m.matches()) {
            return "RPTAContext.getInstance().getApiContext().getContextParameters().get(\""
                    + escapeJavaString(m.group(1)) + "\")";
        }
        return "\"" + escapeJavaString(value) + "\"";
    }

    private static @NotNull String escapeJavaString(@NotNull String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // -------------------------------------------------------------------------
    // Whole-class source (mirrors ApiProxyCodeGenerator.buildProxySource's style)
    // -------------------------------------------------------------------------

    private static @NotNull String buildPostProcessorSource(@NotNull String proxyFqn, @NotNull String className,
                                                             @NotNull String switchCastType, @NotNull String endpointName,
                                                             @NotNull List<FieldMapping> mappings) {
        boolean usesAssert = usesAssert(mappings);
        boolean usesContext = usesContext(mappings);

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(POSTPROCESSOR_PACKAGE).append(";\n\n");
        sb.append("import java.util.Optional;\n\n");
        if (usesAssert) {
            sb.append("import ").append(ASSERT_FQN).append(";\n");
        }
        sb.append("import org.json.JSONObject;\n");
        sb.append("import org.springframework.stereotype.Service;\n\n");
        sb.append("import at.compax.rp.test.model.api.ApiRequestDefinition;\n");
        sb.append("import at.compax.rp.test.model.api.ApiResponseData;\n");
        if (usesContext) {
            sb.append("import ").append(RPTA_CONTEXT_FQN).append(";\n");
        }
        sb.append("import ").append(proxyFqn).append(";\n\n");
        sb.append("import lombok.extern.log4j.Log4j2;\n\n");
        sb.append("@Log4j2\n");
        sb.append("@Service\n");
        sb.append("public class ").append(className).append(" extends AbstractApiPostProcessor {\n\n");
        sb.append("  @Override\n");
        sb.append("  public void postProcess(ApiResponseData apiResponseData,\n");
        sb.append("      ApiRequestDefinition apiRequestDefinition) {\n");
        sb.append("    Optional<JSONObject> responseJson = responseAsJsonIf2xxSuccessful(apiResponseData);\n");
        sb.append("    switch ((").append(switchCastType).append(") apiResponseData.getSentRequest()\n");
        sb.append("        .getApiEndpoint()) {\n");
        sb.append("      ").append(buildCaseText(endpointName, "responseJson", mappings).replace("\n", "\n      ")).append("\n");
        sb.append("      default:\n");
        sb.append("    }\n");
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // File creation (duplicated from ApiProxyCodeGenerator - same small-helper-duplication style
    // already established there rather than sharing across packages for logic this size)
    // -------------------------------------------------------------------------

    private static @Nullable PsiFile createJavaFile(@NotNull Project project, @NotNull String packageFqn,
                                                     @NotNull String simpleClassName, @NotNull String source,
                                                     @NotNull PsiClass anchor) {
        VirtualFile dir = findOrCreatePackageDirectory(project, packageFqn, anchor);
        if (dir == null) {
            throw new IllegalStateException("Could not find or create a source directory for " + packageFqn + ".");
        }
        String fileName = simpleClassName + ".java";
        if (dir.findChild(fileName) != null) {
            throw new IllegalStateException("File already exists: " + fileName);
        }
        try {
            VirtualFile file = dir.createChildData(PostProcessorCodeGenerator.class, fileName);
            VfsUtil.saveText(file, source);
            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            if (psiFile != null) {
                CodeStyleManager.getInstance(project).reformat(psiFile);
            }
            return psiFile;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create " + fileName, e);
        }
    }

    private static @Nullable VirtualFile findOrCreatePackageDirectory(@NotNull Project project,
                                                                       @NotNull String packageFqn,
                                                                       @NotNull PsiClass anchor) {
        PsiPackage psiPackage = JavaPsiFacade.getInstance(project).findPackage(packageFqn);
        if (psiPackage != null) {
            PsiDirectory[] dirs = psiPackage.getDirectories();
            if (dirs.length > 0) return dirs[0].getVirtualFile();
        }

        Module module = ModuleUtilCore.findModuleForPsiElement(anchor);
        if (module == null) return null;

        String relativePath = packageFqn.replace('.', '/');
        for (VirtualFile root : ModuleRootManager.getInstance(module).getSourceRoots()) {
            VirtualFile existing = root.findFileByRelativePath(relativePath);
            if (existing != null) return existing;
        }

        VirtualFile anchorFile = anchor.getContainingFile().getVirtualFile();
        for (VirtualFile root : ModuleRootManager.getInstance(module).getSourceRoots()) {
            if (VfsUtilCore.isAncestor(root, anchorFile, false)) {
                try {
                    return VfsUtil.createDirectoryIfMissing(root, relativePath);
                } catch (IOException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
