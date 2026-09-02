package com.pumpkin.intellij.navigation;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
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

    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(
            @Nullable PsiElement sourceElement,
            int offset,
            Editor editor) {

        if (sourceElement == null) return null;

        GherkinStep step = GherkinPsiUtil.findEnclosingStep(sourceElement);
        if (step == null || !GherkinPsiUtil.isProcessStep(step)) return null;

        String invocationText = GherkinPsiUtil.getProcessInvocationText(step);
        if (invocationText == null) return null;

        List<PumpkinProcessDefinition> matches =
                PumpkinProcessService.getInstance(sourceElement.getProject())
                        .findMatchingProcesses(invocationText);

        return matches.stream()
                .map(PumpkinProcessDefinition::getScenarioPsiElement)
                .filter(scenario -> scenario instanceof GherkinScenario && scenario.isValid())
                .toArray(PsiElement[]::new);
    }
}
