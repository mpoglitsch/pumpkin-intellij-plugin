package com.pumpkin.intellij.endpoint;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import org.jetbrains.annotations.NotNull;

/** Step 1: appends the new endpoint's enum constant after the last existing one. */
final class EnumConstantEditor {

    private EnumConstantEditor() {}

    static void appendConstant(@NotNull Project project, @NotNull PsiClass endpointEnum,
                               @NotNull String constantName, @NotNull String httpMethod,
                               @NotNull String path) {
        PsiEnumConstant last = null;
        for (PsiField field : endpointEnum.getFields()) {
            if (field instanceof PsiEnumConstant ec) last = ec;
        }
        if (last == null) {
            throw new IllegalStateException(
                    "Endpoint enum " + endpointEnum.getName() + " has no existing constants to insert after.");
        }

        PsiFile file = endpointEnum.getContainingFile();
        Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
        if (doc == null) return;

        int insertOffset = last.getTextRange().getEndOffset();
        String indent = SwitchMethodEditor.indentOf(doc.getText(), last.getTextOffset());
        String constantText = constantName + "(Method." + httpMethod + ", \"" + path + "\", null)";
        String textToInsert = ",\n" + indent + constantText;

        doc.insertString(insertOffset, textToInsert);
        PsiDocumentManager.getInstance(project).commitDocument(doc);
        CodeStyleManager.getInstance(project)
                .reformatText(file, insertOffset, insertOffset + textToInsert.length());
    }
}
