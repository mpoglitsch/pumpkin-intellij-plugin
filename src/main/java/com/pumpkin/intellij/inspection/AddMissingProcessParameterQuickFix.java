package com.pumpkin.intellij.inspection;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;
import org.jetbrains.plugins.cucumber.psi.GherkinTable;
import org.jetbrains.plugins.cucumber.psi.GherkinTableRow;

import java.util.List;

/**
 * Quick fix that inserts one or more missing required parameter rows into the Process
 * data table, or creates the table if none exists yet.
 */
public class AddMissingProcessParameterQuickFix implements LocalQuickFix {

    private final @NotNull List<String> missingParameters;

    public AddMissingProcessParameterQuickFix(@NotNull List<String> missingParameters) {
        this.missingParameters = List.copyOf(missingParameters);
    }

    @Override
    public @NotNull String getName() {
        if (missingParameters.size() == 1) {
            return "Add missing Pumpkin Process parameter '" + missingParameters.get(0) + "'";
        }
        return "Add missing Pumpkin Process parameters: " + String.join(", ", missingParameters);
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Pumpkin Process";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiElement element = descriptor.getPsiElement();
        GherkinStep step = PsiTreeUtil.getParentOfType(element, GherkinStep.class, false);
        if (step == null || !step.isValid()) return;

        GherkinTable table = step.getTable();

        // Determine the key column width (for alignment).
        int maxKeyLen = missingParameters.stream().mapToInt(String::length).max().orElse(0);
        if (table != null) {
            // Also account for existing key widths so the added rows align visually.
            for (GherkinTableRow row : PsiTreeUtil.findChildrenOfType(table, GherkinTableRow.class)) {
                java.util.List<org.jetbrains.plugins.cucumber.psi.GherkinTableCell> cells = row.getPsiCells();
                if (cells != null && !cells.isEmpty()) {
                    maxKeyLen = Math.max(maxKeyLen, cells.get(0).getText().trim().length());
                }
            }
        }

        String indent = computeIndent(step);
        StringBuilder rowsToInsert = new StringBuilder();
        for (String param : missingParameters) {
            int pad = maxKeyLen - param.length();
            rowsToInsert.append("\n").append(indent)
                    .append("| ").append(param).append(" ".repeat(pad)).append(" |  |");
        }

        if (table == null) {
            // No table yet: append after the step text.
            appendAfterStep(step, rowsToInsert.toString());
        } else {
            // Append rows at the end of the existing table.
            PsiElement lastChild = table.getLastChild();
            if (lastChild == null) return;
            appendText(lastChild, rowsToInsert.toString());
        }
    }

    private void appendAfterStep(@NotNull GherkinStep step, @NotNull String text) {
        appendText(step.getLastChild() != null ? step.getLastChild() : step, text);
    }

    private void appendText(@NotNull PsiElement anchor, @NotNull String text) {
        com.intellij.openapi.editor.Document doc = getDocument(anchor);
        if (doc == null) return;
        int insertOffset = anchor.getTextRange().getEndOffset();
        doc.insertString(insertOffset, text);
    }

    private com.intellij.openapi.editor.Document getDocument(@NotNull PsiElement element) {
        com.intellij.psi.PsiFile file = element.getContainingFile();
        if (file == null) return null;
        return com.intellij.psi.PsiDocumentManager.getInstance(element.getProject())
                .getDocument(file);
    }

    private @NotNull String computeIndent(@NotNull GherkinStep step) {
        try {
            String text = step.getContainingFile().getText();
            int lineStart = text.lastIndexOf('\n', step.getTextOffset()) + 1;
            int contentStart = lineStart;
            while (contentStart < text.length() && text.charAt(contentStart) == ' ') contentStart++;
            return text.substring(lineStart, contentStart) + "  ";
        } catch (Exception ignored) {
            return "    ";
        }
    }
}
