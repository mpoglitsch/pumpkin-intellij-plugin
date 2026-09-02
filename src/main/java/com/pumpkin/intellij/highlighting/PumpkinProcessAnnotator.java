package com.pumpkin.intellij.highlighting;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.pumpkin.intellij.matching.PumpkinProcessMatcher;
import com.pumpkin.intellij.model.PumpkinProcessDefinition;
import com.pumpkin.intellij.repository.PumpkinProcessService;
import com.pumpkin.intellij.util.GherkinPsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;

import java.util.List;

/**
 * Annotates Process steps with dedicated Pumpkin text attributes:
 * <ul>
 *   <li>{@code Process:} → {@link PumpkinTextAttributeKeys#PROCESS_KEYWORD}</li>
 *   <li>Matched variable values → {@link PumpkinTextAttributeKeys#PROCESS_VARIABLE}</li>
 * </ul>
 *
 * Normal Gherkin highlighting is left untouched.
 */
public class PumpkinProcessAnnotator implements Annotator {

    private static final String PROCESS_COLON = "Process:";
    private static final String PROCESS_PREFIX = "Process: ";

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
                .textAttributes(PumpkinTextAttributeKeys.PROCESS_KEYWORD)
                .create();

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

        for (PumpkinProcessMatcher.VariableMatch vm : result.getVariables()) {
            int varStart = invocationAbsOffset + vm.getStartOffset();
            int varEnd   = invocationAbsOffset + vm.getEndOffset();
            if (varStart >= varEnd) continue;

            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(new TextRange(varStart, varEnd))
                    .textAttributes(PumpkinTextAttributeKeys.PROCESS_VARIABLE)
                    .create();
        }
    }
}
