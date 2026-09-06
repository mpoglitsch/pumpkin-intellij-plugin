package com.pumpkin.intellij.navigation;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.pumpkin.intellij.util.GherkinPsiUtil;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinScenario;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;

import java.util.List;
import java.util.Objects;

/**
 * Intercepts "Go to Declaration" (Alt/Opt-click, Cmd/Ctrl+Click) on a {@code @pumpkin} Scenario's
 * header — its tags or the {@code Scenario:} line itself, not one of its own steps — and
 * navigates to every {@code Process: ...} step across the project that invokes it.
 *
 * <p>This is the reverse of {@link PumpkinGotoDeclarationHandler} (which navigates from an
 * invocation to its Scenario). Registered with {@code order="first"} like the other Pumpkin
 * handlers; returns {@code null} for anything that isn't a {@code @pumpkin} Scenario header so
 * Cucumber's own handler (and the other Pumpkin handlers) still work normally elsewhere.
 */
public class PumpkinProcessUsagesGotoDeclarationHandler implements GotoDeclarationHandler {

    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(
            @Nullable PsiElement sourceElement,
            int offset,
            Editor editor) {

        if (sourceElement == null) return null;
        if (DumbService.isDumb(sourceElement.getProject())) return null;

        // Clicking inside one of the scenario's own steps has its own, more specific navigation
        // (e.g. that step might itself be a Process: invocation) — don't hijack it here.
        if (PsiTreeUtil.getParentOfType(sourceElement, GherkinStep.class) != null) return null;

        GherkinScenario scenario = GherkinPsiUtil.findEnclosingScenario(sourceElement);
        if (scenario == null || !GherkinPsiUtil.isPumpkinScenario(scenario)) return null;

        List<GherkinStep> usages = PumpkinProcessUsagesFinder.findUsages(sourceElement.getProject(), scenario);
        if (usages.isEmpty()) return null;

        // Wrapped so the "Choose Declaration" popup shows each usage's file:line instead of the
        // step text - every usage here invokes the same Process, so the step text itself is
        // always identical and tells the user nothing; see PumpkinNavigationTarget's doc.
        return usages.stream()
                .map(step -> (PsiElement) new PumpkinNavigationTarget(step,
                        Objects.requireNonNullElseGet(
                                PumpkinGherkinStepItemPresentationProvider.locationOf(step),
                                () -> step.getText().strip())))
                .toArray(PsiElement[]::new);
    }
}
