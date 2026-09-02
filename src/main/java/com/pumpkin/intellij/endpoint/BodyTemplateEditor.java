package com.pumpkin.intellij.endpoint;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Step 4: creates the JSON template resource file, adds the {@code @Value}-annotated
 * {@code Resource} field, and wires it into {@code getBodyTemplate}.
 */
final class BodyTemplateEditor {

    private static final String METHOD_NAME = "getBodyTemplate";
    private static final String VALUE_FQN = "org.springframework.beans.factory.annotation.Value";
    private static final String RESOURCE_FQN = "org.springframework.core.io.Resource";

    private BodyTemplateEditor() {}

    static void generate(@NotNull Project project, @NotNull SmartPsiElementPointer<PsiClass> proxyPtr,
                         @NotNull String enumSimpleName, @NotNull String constantName,
                         @NotNull String camelName, @NotNull String apiNameKebab,
                         @NotNull String bodyJson) {
        PsiClass proxyClass = SwitchMethodEditor.requireValid(proxyPtr);
        Module module = ModuleUtilCore.findModuleForPsiElement(proxyClass);
        if (module == null) {
            throw new IllegalStateException("Could not determine the module for " + proxyClass.getName());
        }

        String relativePath = "api/templates/" + apiNameKebab + "/" + camelName + ".json";
        createTemplateFile(project, module, relativePath, bodyJson);

        String fieldName = camelName + "Template";
        addResourceField(project, proxyPtr, relativePath, fieldName);

        proxyClass = SwitchMethodEditor.requireValid(proxyPtr);
        String caseText = "case " + constantName + ":\n  return Optional.of(loadPayloadTemplate(" + fieldName + "));";
        String fullMethod =
                "@Override\n" +
                "protected Optional<String> " + METHOD_NAME + "(ApiEndpoint apiEndpoint,\n" +
                "    Map<String, String> providedParameters) {\n" +
                "  switch ((" + enumSimpleName + ") apiEndpoint) {\n" +
                "    " + caseText.replace("\n", "\n    ") + "\n" +
                "    default:\n" +
                "      return Optional.empty();\n" +
                "  }\n" +
                "}";

        SwitchMethodEditor.upsertCase(project, proxyClass, METHOD_NAME, fullMethod, caseText);
    }

    private static void createTemplateFile(@NotNull Project project, @NotNull Module module,
                                           @NotNull String relativePath, @NotNull String content) {
        VirtualFile root = findTemplatesSourceRoot(project, module);
        if (root == null) {
            throw new IllegalStateException("Could not find a resources source root to create the template file in.");
        }

        int lastSlash = relativePath.lastIndexOf('/');
        String dirPath = relativePath.substring(0, lastSlash);
        String fileName = relativePath.substring(lastSlash + 1);

        try {
            VirtualFile dir = VfsUtil.createDirectoryIfMissing(root, dirPath);
            if (dir.findChild(fileName) != null) {
                throw new IllegalStateException("Template file already exists: " + relativePath);
            }
            VirtualFile file = dir.createChildData(BodyTemplateEditor.class, fileName);
            VfsUtil.saveText(file, content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create template file: " + relativePath, e);
        }
    }

    /** Prefers a source root that already has an {@code api/templates} directory. */
    private static @Nullable VirtualFile findTemplatesSourceRoot(@NotNull Project project, @NotNull Module module) {
        for (Module m : ModuleManager.getInstance(project).getModules()) {
            for (VirtualFile root : ModuleRootManager.getInstance(m).getSourceRoots()) {
                if (root.findFileByRelativePath("api/templates") != null) return root;
            }
        }
        VirtualFile[] roots = ModuleRootManager.getInstance(module).getSourceRoots();
        for (VirtualFile root : roots) {
            if (root.getPath().endsWith("resources")) return root;
        }
        return roots.length > 0 ? roots[0] : null;
    }

    private static void addResourceField(@NotNull Project project, @NotNull SmartPsiElementPointer<PsiClass> proxyPtr,
                                         @NotNull String relativePath, @NotNull String fieldName) {
        PsiClass proxyClass = SwitchMethodEditor.requireValid(proxyPtr);
        if (proxyClass.getContainingFile() instanceof PsiJavaFile javaFile) {
            SwitchMethodEditor.ensureImport(project, javaFile, RESOURCE_FQN);
            SwitchMethodEditor.ensureImport(project, javaFile, VALUE_FQN);
        }

        // Re-fetch: the import edits above shifted every offset in the file.
        proxyClass = SwitchMethodEditor.requireValid(proxyPtr);
        PsiFile file = proxyClass.getContainingFile();
        Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
        if (doc == null) return;

        PsiField anchor = findLastTemplateField(proxyClass);
        int insertOffset;
        if (anchor != null) {
            insertOffset = anchor.getTextRange().getEndOffset();
        } else {
            PsiElement lBrace = proxyClass.getLBrace();
            insertOffset = lBrace != null
                    ? lBrace.getTextRange().getEndOffset()
                    : proxyClass.getTextRange().getStartOffset();
        }

        String fieldText = "\n\n  @Value(\"classpath:" + relativePath + "\")\n  Resource " + fieldName + ";";
        doc.insertString(insertOffset, fieldText);
        PsiDocumentManager.getInstance(project).commitDocument(doc);
        CodeStyleManager.getInstance(project)
                .reformatText(file, insertOffset, insertOffset + fieldText.length());
    }

    /** The last existing field whose {@code @Value("classpath:api/templates...")} matches this convention. */
    private static @Nullable PsiField findLastTemplateField(@NotNull PsiClass proxyClass) {
        PsiField last = null;
        for (PsiField field : proxyClass.getFields()) {
            PsiAnnotation ann = field.getAnnotation(VALUE_FQN);
            if (ann == null) continue;
            PsiAnnotationMemberValue value = ann.findAttributeValue("value");
            if (value instanceof PsiLiteralExpression lit
                    && lit.getValue() instanceof String s
                    && s.startsWith("classpath:api/templates")) {
                last = field;
            }
        }
        return last;
    }
}
