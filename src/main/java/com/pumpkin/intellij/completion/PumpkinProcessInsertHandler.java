package com.pumpkin.intellij.completion;

import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.pumpkin.intellij.model.PumpkinProcessDefinition;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Handles completion insertion for a Pumpkin Process.
 *
 * <p>After selection:
 * <ol>
 *   <li>Replaces the typed text (from the start of the process name) with the resolved
 *       process invocation – variable placeholders are stripped, leaving the user to type values.</li>
 *   <li>Appends a Gherkin data-table with one row per required parameter.</li>
 *   <li>Positions the caret after the first variable placeholder so the user can type immediately.</li>
 * </ol>
 */
public class PumpkinProcessInsertHandler implements InsertHandler<LookupElement> {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{[a-zA-Z_][a-zA-Z0-9_]*}");

    @Override
    public void handleInsert(@NotNull InsertionContext context, @NotNull LookupElement item) {
        Object obj = item.getObject();
        if (!(obj instanceof PumpkinProcessDefinition def)) return;

        Document document = context.getDocument();
        Editor editor = context.getEditor();

        // Find the GherkinStep at the insertion point.
        context.commitDocument();
        PsiElement pos = context.getFile().findElementAt(context.getStartOffset());
        GherkinStep step = PsiTreeUtil.getParentOfType(pos, GherkinStep.class, false);

        // Compute the range to replace: from the start of the process name to the tail offset.
        int replaceStart = locateProcessNameStart(step, document, context.getStartOffset());
        int replaceEnd   = context.getTailOffset();

        // Build the text to insert (invocation line + optional table).
        String processText = buildProcessText(def);
        String tableText   = buildTableText(def, step);
        String insertText  = processText + tableText;

        document.replaceString(replaceStart, replaceEnd, insertText);

        // Position caret: after the process name, ready to type the first variable value.
        int caretOffset = replaceStart + processText.length();
        editor.getCaretModel().moveToOffset(caretOffset);
    }

    // -------------------------------------------------------------------------

    /**
     * Locate the document offset where the process invocation text (after "Process: ") starts.
     * Falls back to {@code fallback} if the step cannot be found.
     */
    private int locateProcessNameStart(@Nullable GherkinStep step,
                                       @NotNull Document document,
                                       int fallback) {
        if (step == null) return fallback;
        String fullText = step.getText();
        int idx = fullText.indexOf("Process: ");
        if (idx < 0) return fallback;
        return step.getTextOffset() + idx + "Process: ".length();
    }

    /**
     * Strips all {@code {variable}} placeholders from the process name, producing
     * the static text the user sees in the invocation line (with trailing space before
     * each stripped placeholder preserved for readability).
     */
    @NotNull
    private String buildProcessText(@NotNull PumpkinProcessDefinition def) {
        // Replace each {variable} with empty string so the user can type the value.
        return VARIABLE_PATTERN.matcher(def.getProcessName()).replaceAll("");
    }

    /**
     * Builds the data-table rows for required parameters, indented to match the step.
     * Returns an empty string when there are no required parameters.
     */
    @NotNull
    private String buildTableText(@NotNull PumpkinProcessDefinition def,
                                  @Nullable GherkinStep step) {
        List<String> required = def.getRequiredParameters();
        if (required.isEmpty()) return "";

        String indent = computeIndent(step);

        // Align key column width.
        int maxKeyLen = required.stream().mapToInt(String::length).max().orElse(0);

        StringBuilder sb = new StringBuilder();
        for (String param : required) {
            int pad = maxKeyLen - param.length();
            sb.append("\n").append(indent).append("| ").append(param)
              .append(" ".repeat(pad)).append(" |  |");
        }
        return sb.toString();
    }

    /** Returns the whitespace prefix of the step's line, used to indent table rows. */
    @NotNull
    private String computeIndent(GherkinStep step) {
        if (step == null) return "    ";
        try {
            String text = step.getContainingFile().getText();
            int lineStart = text.lastIndexOf('\n', step.getTextOffset()) + 1;
            int contentStart = lineStart;
            while (contentStart < text.length() && text.charAt(contentStart) == ' ') contentStart++;
            return text.substring(lineStart, contentStart) + "  "; // extra indent for table
        } catch (Exception ignored) {
            return "    ";
        }
    }
}
