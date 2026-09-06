package com.pumpkin.intellij.proxy;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.pumpkin.intellij.endpoint.SwitchMethodEditor;
import org.jetbrains.annotations.NotNull;

/**
 * Appends a new, argument-less constant to the {@code ApiNotation} enum - confirmed its existing
 * constants take no constructor arguments, unlike the per-proxy endpoint enums
 * ({@code EnumConstantEditor} in {@code com.pumpkin.intellij.endpoint} handles those).
 */
final class ApiNotationEditor {

    private ApiNotationEditor() {}

    static void appendConstant(@NotNull Project project, @NotNull PsiClass apiNotationEnum,
                               @NotNull String constantName) {
        PsiEnumConstant last = null;
        for (PsiField field : apiNotationEnum.getFields()) {
            if (field instanceof PsiEnumConstant ec) last = ec;
        }
        if (last == null) {
            throw new IllegalStateException("ApiNotation has no existing constants to insert after.");
        }

        PsiFile file = apiNotationEnum.getContainingFile();
        Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
        if (doc == null) return;

        int insertOffset = last.getTextRange().getEndOffset();
        String indent = SwitchMethodEditor.indentOf(doc.getText(), last.getTextOffset());
        String textToInsert = ",\n" + indent + constantName;

        doc.insertString(insertOffset, textToInsert);
        PsiDocumentManager.getInstance(project).commitDocument(doc);
        CodeStyleManager.getInstance(project)
                .reformatText(file, insertOffset, insertOffset + textToInsert.length());
    }
}
