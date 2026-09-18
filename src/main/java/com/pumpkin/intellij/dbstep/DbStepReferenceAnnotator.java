package com.pumpkin.intellij.dbstep;

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
import com.pumpkin.intellij.highlighting.PumpkinTextAttributeKeys;
import com.pumpkin.intellij.util.GherkinPsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;
import org.jetbrains.plugins.cucumber.psi.GherkinTable;
import org.jetbrains.plugins.cucumber.psi.GherkinTableCell;
import org.jetbrains.plugins.cucumber.psi.GherkinTableRow;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Highlights the external table/column reference actually under test inside a DB-step header
 * cell's {@code join(...)}/{@code rejoin(...)} spec (see {@link DbStepCompletionContributor} for
 * how these specs are authored):
 * <ul>
 *   <li>{@code join(column, table.column)} - the whole 2nd argument ({@code table.column}) is the
 *       external reference being verified, so it's highlighted in full.</li>
 *   <li>{@code rejoin(table1.column1, column2)} - {@code table1} and {@code column2} are the
 *       parts under test; {@code column1} is purely the structural FK-linking column (it always
 *       joins back to this step's own table's {@code id}) and is deliberately left unhighlighted.</li>
 * </ul>
 *
 * Uses {@code enforcedTextAttributes(...)} rather than {@code textAttributes(key)}, mirroring
 * {@code PumpkinProcessAnnotator} - see that class's doc for why (Gherkin's own bundled step-text
 * highlighting otherwise wins the normal layered attribute merge for these ranges).
 */
public class DbStepReferenceAnnotator implements Annotator {

    private static final Logger LOG = Logger.getInstance(DbStepReferenceAnnotator.class);

    // join(column, table.column) - group 1 is the whole "table.column" 2nd argument.
    private static final Pattern JOIN_CALL = Pattern.compile(
            "join\\(\\s*[^,()]+?\\s*,\\s*([^,()]+?\\.[^,()]+?)\\s*\\)", Pattern.CASE_INSENSITIVE);

    // rejoin(table1.column1, column2) - group 1 is "table1" only (not "column1"), group 2 is
    // the whole "column2" 2nd argument.
    private static final Pattern REJOIN_CALL = Pattern.compile(
            "rejoin\\(\\s*([^,.()]+?)\\s*\\.\\s*[^,()]+?\\s*,\\s*([^,()]+?)\\s*\\)", Pattern.CASE_INSENSITIVE);

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        try {
            doAnnotate(element, holder);
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (Throwable t) {
            LOG.warn("Pumpkin DB step reference annotation failed for " + element, t);
        }
    }

    private void doAnnotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof GherkinTableCell cell)) return;

        GherkinStep step = PsiTreeUtil.getParentOfType(cell, GherkinStep.class);
        if (step == null || !GherkinPsiUtil.isDbPresentStep(step)) return;

        GherkinTable table = step.getTable();
        GherkinTableRow row = PsiTreeUtil.getParentOfType(cell, GherkinTableRow.class);
        if (table == null || row == null || row != table.getHeaderRow()) return;

        String text = cell.getText();
        TextRange cellRange = cell.getTextRange();

        Matcher join = JOIN_CALL.matcher(text);
        if (join.find()) {
            annotateGroup(holder, cellRange, join, 1);
            return;
        }

        Matcher rejoin = REJOIN_CALL.matcher(text);
        if (rejoin.find()) {
            annotateGroup(holder, cellRange, rejoin, 1);
            annotateGroup(holder, cellRange, rejoin, 2);
        }
    }

    private static void annotateGroup(@NotNull AnnotationHolder holder, @NotNull TextRange cellRange,
                                      @NotNull Matcher matcher, int group) {
        TextRange range = new TextRange(
                cellRange.getStartOffset() + matcher.start(group),
                cellRange.getStartOffset() + matcher.end(group));
        // Guards against a transient/malformed parse mid-edit producing an out-of-bounds range -
        // see PumpkinProcessAnnotator's own doc on why every annotator range here is validated.
        if (range.isEmpty() || !cellRange.contains(range)) return;
        holder.newSilentAnnotation(HighlightSeverity.ERROR)
                .range(range)
                .enforcedTextAttributes(resolve(PumpkinTextAttributeKeys.DB_REFERENCE))
                .create();
    }

    private static @NotNull TextAttributes resolve(@NotNull TextAttributesKey key) {
        return EditorColorsManager.getInstance().getGlobalScheme().getAttributes(key);
    }
}
