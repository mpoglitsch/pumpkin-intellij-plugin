package com.pumpkin.intellij.navigation;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.psi.PsiElement;
import com.pumpkin.intellij.model.PumpkinProcessDefinition;
import com.pumpkin.intellij.repository.PumpkinProcessService;
import com.pumpkin.intellij.util.GherkinPsiUtil;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinScenario;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;

import java.util.List;

/**
 * Intercepts "Go to Declaration" (Alt/Opt-click, Cmd/Ctrl+B) on Process steps and
 * navigates to the matching {@code @pumpkin}-tagged Gherkin Scenario instead of
 * letting Cucumber for Java resolve it as a step-definition method.
 *
 * <p>Registered with {@code order="first"} so it runs before Cucumber's handler.
 * Returns {@code null} for non-Process steps so Cucumber's handler still works normally.
 */
public class PumpkinGotoDeclarationHandler implements GotoDeclarationHandler {

    private static final Logger LOG = Logger.getInstance(PumpkinGotoDeclarationHandler.class);

    /**
     * Catches broadly (except {@link ProcessCanceledException}, which must always propagate -
     * see {@code PumpkinProcessAnnotator}'s own doc comment for why this pattern exists at all):
     * a {@link GotoDeclarationHandler} that lets an exception escape can get disabled by the
     * platform for the rest of the session, exactly like an {@code Annotator} would - "restart
     * fixes it" for navigation is that same failure mode, not just a highlighting-only risk.
     */
    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(
            @Nullable PsiElement sourceElement,
            int offset,
            Editor editor) {
        try {
            return doGetGotoDeclarationTargets(sourceElement);
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (Throwable t) {
            LOG.warn("Pumpkin Process navigation failed for " + sourceElement, t);
            return null;
        }
    }

    private PsiElement @Nullable [] doGetGotoDeclarationTargets(@Nullable PsiElement sourceElement) {
        if (sourceElement == null) return null;

        GherkinStep step = GherkinPsiUtil.findEnclosingStep(sourceElement);
        if (step == null || !GherkinPsiUtil.isProcessStep(step)) return null;

        String invocationText = GherkinPsiUtil.getProcessInvocationText(step);
        if (invocationText == null) return null;

        // Filtered by the step's own "| ContextName" suffix (null = default variant) so that a
        // process with more than one context variant navigates straight to the one this exact
        // invocation actually resolves to at runtime, instead of showing every name-matching
        // variant as an ambiguous multi-target picker.
        String requestedContext = GherkinPsiUtil.getInvocationContextName(step);
        PumpkinProcessService service = PumpkinProcessService.getInstance(sourceElement.getProject());
        List<PumpkinProcessDefinition> matches = service.findMatchingProcesses(invocationText, requestedContext);

        return matches.stream()
                .map(PumpkinProcessDefinition::getScenarioPsiElement)
                .filter(scenario -> scenario instanceof GherkinScenario && scenario.isValid())
                .toArray(PsiElement[]::new);
    }
}
