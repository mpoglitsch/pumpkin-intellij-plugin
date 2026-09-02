package com.pumpkin.intellij.navigation;

import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.util.ProcessingContext;
import com.pumpkin.intellij.util.GherkinPsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;

/**
 * Registers a {@link PumpkinProcessReference} on every Process step so that
 * Option-click (Mac) / Alt-click (Windows/Linux) navigates to the target scenario
 * without any custom mouse handling.
 */
public class PumpkinProcessReferenceContributor extends PsiReferenceContributor {

    private static final String PROCESS_PREFIX = "Process: ";
    // Mirrors GherkinPsiUtil's suffixes so the reference range covers only the process name.
    private static final String WITH_DATA_SUFFIX = " with data";
    private static final String WITHOUT_DATA_SUFFIX = " without data";

    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement(GherkinStep.class),
                new PsiReferenceProvider() {
                    @Override
                    public PsiReference @NotNull [] getReferencesByElement(
                            @NotNull PsiElement element,
                            @NotNull ProcessingContext context) {

                        if (!(element instanceof GherkinStep step)) return PsiReference.EMPTY_ARRAY;
                        if (!GherkinPsiUtil.isProcessStep(step)) return PsiReference.EMPTY_ARRAY;

                        // The reference range covers the invocation text (everything after "Process: ")
                        // within the step element. We compute it relative to the element start.
                        String fullText = step.getText();
                        int processIdx = fullText.indexOf(PROCESS_PREFIX);
                        if (processIdx < 0) return PsiReference.EMPTY_ARRAY;

                        int invocationStart = processIdx + PROCESS_PREFIX.length();
                        // Stop at the first newline so the reference range does not extend
                        // into a data table that may be part of the step's PSI text.
                        int newlineIdx = fullText.indexOf('\n', invocationStart);
                        int invocationEnd = newlineIdx >= 0 ? newlineIdx : fullText.length();

                        // Exclude the trailing " with data"/" without data" marker, if present,
                        // so the clickable range covers only the process name.
                        String invocationLine = fullText.substring(invocationStart, invocationEnd);
                        if (invocationLine.endsWith(WITH_DATA_SUFFIX)) {
                            invocationEnd -= WITH_DATA_SUFFIX.length();
                        } else if (invocationLine.endsWith(WITHOUT_DATA_SUFFIX)) {
                            invocationEnd -= WITHOUT_DATA_SUFFIX.length();
                        }
                        if (invocationStart >= invocationEnd) return PsiReference.EMPTY_ARRAY;

                        TextRange range = new TextRange(invocationStart, invocationEnd);
                        return new PsiReference[]{ new PumpkinProcessReference(step, range) };
                    }
                },
                PsiReferenceRegistrar.LOWER_PRIORITY
        );
    }
}
