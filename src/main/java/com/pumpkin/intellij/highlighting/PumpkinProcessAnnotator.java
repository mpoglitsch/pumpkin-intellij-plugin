package com.pumpkin.intellij.highlighting;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.pumpkin.intellij.matching.PumpkinProcessMatcher;
import com.pumpkin.intellij.model.PumpkinProcessDefinition;
import com.pumpkin.intellij.repository.PumpkinProcessService;
import com.pumpkin.intellij.util.GherkinPsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;
import org.jetbrains.plugins.cucumber.psi.GherkinTable;
import org.jetbrains.plugins.cucumber.psi.GherkinTableCell;
import org.jetbrains.plugins.cucumber.psi.GherkinTableRow;

import java.util.List;

/**
 * Annotates Process steps with dedicated Pumpkin text attributes:
 * <ul>
 *   <li>{@code Process:} and the trailing "with/without data" marker → {@link PumpkinTextAttributeKeys#PROCESS_KEYWORD}</li>
 *   <li>Matched variable values → {@link PumpkinTextAttributeKeys#PROCESS_VARIABLE}</li>
 *   <li>Data-table value column → {@link PumpkinTextAttributeKeys#PROCESS_VARIABLE}</li>
 *   <li>Literal (non-variable) invocation text → {@link PumpkinTextAttributeKeys#PROCESS_TEXT}, forcing
 *       the default text color over Gherkin's own step-text coloring</li>
 * </ul>
 *
 * All of the above use {@code enforcedTextAttributes(...)} rather than {@code textAttributes(key)}:
 * Gherkin's own bundled highlighting for step text (e.g. its parameter/cucumber-expression
 * coloring) otherwise wins the normal layered attribute merge for these ranges, since it isn't a
 * plain lexer-based SyntaxHighlighter. Enforcing concrete attributes bypasses that merge entirely
 * so Pumpkin's colors always render, while still resolving the color from the (user-configurable)
 * {@link PumpkinTextAttributeKeys} scheme entry rather than a hardcoded value.
 */
public class PumpkinProcessAnnotator implements Annotator {

    private static final String PROCESS_COLON = "Process:";
    private static final String PROCESS_PREFIX = "Process: ";
    // Mirrors GherkinPsiUtil's suffixes so the marker gets the same color as "Process:".
    private static final String WITH_DATA_SUFFIX = " with data";
    private static final String WITHOUT_DATA_SUFFIX = " without data";

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof GherkinStep step)) return;
        if (!GherkinPsiUtil.isProcessStep(step)) return;

        String fullStepText = step.getText(); // includes keyword, e.g. "* Process: ..."
        int stepAbsOffset = step.getTextOffset();

        // -- Highlight "Process:" keyword --
        int keywordRelIdx = fullStepText.indexOf(PROCESS_COLON);
        if (keywordRelIdx < 0) return;

        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(new TextRange(
                        stepAbsOffset + keywordRelIdx,
                        stepAbsOffset + keywordRelIdx + PROCESS_COLON.length()
                ))
                .enforcedTextAttributes(resolve(PumpkinTextAttributeKeys.PROCESS_KEYWORD))
                .create();

        // -- Highlight trailing "with data"/"without data" marker with the same color --
        int invocationRelStart = keywordRelIdx + PROCESS_PREFIX.length();
        int newlineIdx = fullStepText.indexOf('\n', invocationRelStart);
        int invocationRelEnd = newlineIdx >= 0 ? newlineIdx : fullStepText.length();
        String invocationLine = fullStepText.substring(invocationRelStart, invocationRelEnd);

        String suffix = null;
        if (invocationLine.endsWith(WITH_DATA_SUFFIX)) {
            suffix = WITH_DATA_SUFFIX;
        } else if (invocationLine.endsWith(WITHOUT_DATA_SUFFIX)) {
            suffix = WITHOUT_DATA_SUFFIX;
        }
        if (suffix != null) {
            // +1 to skip the leading space so only "with data"/"without data" is colored.
            int suffixRelStart = invocationRelEnd - suffix.length() + 1;
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(new TextRange(
                            stepAbsOffset + suffixRelStart,
                            stepAbsOffset + invocationRelEnd
                    ))
                    .enforcedTextAttributes(resolve(PumpkinTextAttributeKeys.PROCESS_KEYWORD))
                    .create();
        }

        // -- Highlight data-table value column with the same color as invocation variables --
        // Overrides Gherkin's own default TABLE_CELL (blue/PARAMETER) coloring for this column.
        GherkinTable table = step.getTable();
        if (table != null) {
            for (GherkinTableRow row : PsiTreeUtil.findChildrenOfType(table, GherkinTableRow.class)) {
                List<GherkinTableCell> cells = row.getPsiCells();
                if (cells == null || cells.size() < 2) continue;

                TextRange valueRange = cells.get(1).getTextRange();
                if (valueRange.isEmpty()) continue;

                holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                        .range(valueRange)
                        .enforcedTextAttributes(resolve(PumpkinTextAttributeKeys.PROCESS_VARIABLE))
                        .create();
            }
        }

        // -- Highlight variable values --
        String invocationText = GherkinPsiUtil.getProcessInvocationText(step);
        if (invocationText == null || invocationText.isBlank()) return;

        List<PumpkinProcessDefinition> matches =
                PumpkinProcessService.getInstance(step.getProject())
                        .findMatchingProcesses(invocationText);

        if (matches.size() != 1) return; // ambiguous or no match – skip variable highlighting

        PumpkinProcessMatcher.MatchResult result =
                PumpkinProcessMatcher.match(matches.get(0), invocationText);
        if (result == null) return;

        // Absolute start of the invocation text within the document.
        int invocationAbsOffset = stepAbsOffset + keywordRelIdx + PROCESS_PREFIX.length();

        // Literal (non-variable) text between/around variables is forced to the default text
        // color, overriding Gherkin's own step-text coloring for the invocation.
        int cursor = 0;
        for (PumpkinProcessMatcher.VariableMatch vm : result.getVariables()) {
            int varStart = invocationAbsOffset + vm.getStartOffset();
            int varEnd   = invocationAbsOffset + vm.getEndOffset();
            if (varStart >= varEnd) continue;

            if (vm.getStartOffset() > cursor) {
                annotateDefaultText(holder, invocationAbsOffset + cursor, varStart);
            }

            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(new TextRange(varStart, varEnd))
                    .enforcedTextAttributes(resolve(PumpkinTextAttributeKeys.PROCESS_VARIABLE))
                    .create();

            cursor = Math.max(cursor, vm.getEndOffset());
        }
        if (cursor < invocationText.length()) {
            annotateDefaultText(holder, invocationAbsOffset + cursor, invocationAbsOffset + invocationText.length());
        }
    }

    private static void annotateDefaultText(@NotNull AnnotationHolder holder, int start, int end) {
        if (start >= end) return;
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(new TextRange(start, end))
                .enforcedTextAttributes(resolve(PumpkinTextAttributeKeys.PROCESS_TEXT))
                .create();
    }

    /**
     * Resolves a key to a concrete, effective {@link TextAttributes} snapshot from the active
     * scheme, for use with {@code enforcedTextAttributes(...)}. Passing the key itself via
     * {@code textAttributes(key)} lets Gherkin's own step-text highlighting win the normal
     * layered merge for these ranges - see the class doc.
     */
    private static @NotNull TextAttributes resolve(@NotNull TextAttributesKey key) {
        return EditorColorsManager.getInstance().getGlobalScheme().getAttributes(key);
    }
}
