package com.pumpkin.intellij.highlighting;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.progress.ProcessCanceledException;
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

    private static final Logger LOG = Logger.getInstance(PumpkinProcessAnnotator.class);

    private static final String PROCESS_COLON = "Process:";
    private static final String PROCESS_PREFIX = "Process: ";
    // Mirrors GherkinPsiUtil's suffixes so the marker gets the same color as "Process:".
    private static final String WITH_DATA_SUFFIX = " with data";
    private static final String WITHOUT_DATA_SUFFIX = " without data";

    /**
     * A misbehaving {@link Annotator} that lets an exception escape {@code annotate()} gets
     * silently disabled by the platform for the rest of the IDE session (logged as a fatal
     * error, no further highlighting from it until restart) - which is exactly the "Pumpkin
     * highlighting and navigation both stop working until I restart" symptom this class used to
     * be able to cause. Every range below is derived from manual text-offset arithmetic rather
     * than PSI-validated boundaries (unlike the other two consumers of {@code
     * PumpkinProcessService}, {@code PumpkinGotoDeclarationHandler} and {@code
     * PumpkinContextParametersInlayHintsProvider}, which is why only this one was ever at real
     * risk of it) - a transient/malformed parse state while editing (e.g. Gherkin's incremental
     * parser briefly misattributing a table to the wrong step while a new Process is being typed
     * elsewhere in the file) can make a computed range fall outside the step's own bounds, and
     * {@code AnnotationHolder.range(...)} throws {@code IllegalArgumentException} for that.
     * Catching broadly here - except {@link ProcessCanceledException}, which must always
     * propagate, per SDK convention, since it drives read-action cancellation - means a bug here
     * degrades to "this one step doesn't get colored this pass" instead of "nothing works until
     * you restart IntelliJ".
     */
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        try {
            doAnnotate(element, holder);
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (Throwable t) {
            LOG.warn("Pumpkin Process annotation failed for " + element, t);
        }
    }

    private void doAnnotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof GherkinStep step)) return;
        if (!GherkinPsiUtil.isProcessStep(step)) return;

        TextRange stepRange = step.getTextRange();
        String fullStepText = step.getText(); // includes keyword, e.g. "* Process: ..."
        int stepAbsOffset = step.getTextOffset();

        // -- Highlight "Process:" keyword --
        int keywordRelIdx = fullStepText.indexOf(PROCESS_COLON);
        if (keywordRelIdx < 0) return;

        annotateRange(holder, stepRange, new TextRange(
                stepAbsOffset + keywordRelIdx,
                stepAbsOffset + keywordRelIdx + PROCESS_COLON.length()
        ), PumpkinTextAttributeKeys.PROCESS_KEYWORD);

        // -- Highlight trailing "with data"/"without data" marker with the same color --
        int invocationRelStart = keywordRelIdx + PROCESS_PREFIX.length();
        if (invocationRelStart <= fullStepText.length()) {
            int newlineIdx = fullStepText.indexOf('\n', invocationRelStart);
            int invocationRelEnd = newlineIdx >= 0 ? newlineIdx : fullStepText.length();
            // Windows (CRLF) files leave a trailing '\r' right before the '\n' - excluded here so
            // it doesn't defeat the endsWith(...) suffix checks below.
            if (invocationRelEnd > invocationRelStart && fullStepText.charAt(invocationRelEnd - 1) == '\r') {
                invocationRelEnd--;
            }
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
                annotateRange(holder, stepRange, new TextRange(
                        stepAbsOffset + suffixRelStart,
                        stepAbsOffset + invocationRelEnd
                ), PumpkinTextAttributeKeys.PROCESS_KEYWORD);
            }
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

                annotateRange(holder, stepRange, valueRange, PumpkinTextAttributeKeys.PROCESS_VARIABLE);
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
                annotateDefaultText(holder, stepRange, invocationAbsOffset + cursor, varStart);
            }

            annotateRange(holder, stepRange, new TextRange(varStart, varEnd), PumpkinTextAttributeKeys.PROCESS_VARIABLE);

            cursor = Math.max(cursor, vm.getEndOffset());
        }
        if (cursor < invocationText.length()) {
            annotateDefaultText(holder, stepRange, invocationAbsOffset + cursor, invocationAbsOffset + invocationText.length());
        }
    }

    private static void annotateDefaultText(@NotNull AnnotationHolder holder, @NotNull TextRange stepRange,
                                            int start, int end) {
        if (start >= end) return;
        annotateRange(holder, stepRange, new TextRange(start, end), PumpkinTextAttributeKeys.PROCESS_TEXT);
    }

    /**
     * Creates the annotation only if {@code range} actually falls within {@code stepRange} -
     * {@code AnnotationHolder.range(...)} throws {@code IllegalArgumentException} otherwise, which
     * is the concrete mechanism behind this class's own doc comment on why every range here is
     * validated rather than trusted. Silently skipping one out-of-bounds range (this pass just
     * doesn't color that bit) is a far better failure mode than losing all Pumpkin highlighting
     * until a restart.
     */
    private static void annotateRange(@NotNull AnnotationHolder holder, @NotNull TextRange stepRange,
                                      @NotNull TextRange range, @NotNull TextAttributesKey key) {
        if (range.isEmpty() || !stepRange.contains(range)) return;
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(range)
                .enforcedTextAttributes(resolve(key))
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
