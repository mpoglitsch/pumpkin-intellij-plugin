package com.pumpkin.intellij.navigation;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.pumpkin.intellij.matching.PumpkinProcessMatcher;
import com.pumpkin.intellij.model.PumpkinProcessDefinition;
import com.pumpkin.intellij.repository.PumpkinProcessParser;
import com.pumpkin.intellij.util.GherkinPsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;
import org.jetbrains.plugins.cucumber.psi.GherkinFileType;
import org.jetbrains.plugins.cucumber.psi.GherkinScenario;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds every {@code Process: ...} step usage across the whole project that invokes a given
 * {@code @pumpkin} Scenario — the reverse of {@link PumpkinGotoDeclarationHandler} (step →
 * Scenario). Scans every Gherkin file in the project rather than reusing
 * {@link com.pumpkin.intellij.repository.PumpkinProcessService}, which only knows about process
 * *definitions* inside the configured process directories — invocations can appear anywhere.
 */
final class PumpkinProcessUsagesFinder {

    private PumpkinProcessUsagesFinder() {}

    /**
     * Returns every {@link GherkinStep} project-wide whose invocation text matches
     * {@code scenario}, or an empty list if it isn't a {@code @pumpkin} Scenario or has no usages.
     */
    static @NotNull List<GherkinStep> findUsages(@NotNull Project project, @NotNull GherkinScenario scenario) {
        PumpkinProcessDefinition def = definitionFor(scenario);
        if (def == null) return List.of();

        List<GherkinStep> usages = new ArrayList<>();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        PsiManager psiManager = PsiManager.getInstance(project);

        for (VirtualFile vf : FileTypeIndex.getFiles(GherkinFileType.INSTANCE, scope)) {
            if (!(psiManager.findFile(vf) instanceof GherkinFile gherkinFile)) continue;

            for (GherkinStep step : PsiTreeUtil.findChildrenOfType(gherkinFile, GherkinStep.class)) {
                if (!GherkinPsiUtil.isProcessStep(step)) continue;
                String invocationText = GherkinPsiUtil.getProcessInvocationText(step);
                if (invocationText != null && PumpkinProcessMatcher.matches(def, invocationText)) {
                    usages.add(step);
                }
            }
        }
        return usages;
    }

    /** Builds the {@link PumpkinProcessDefinition} for {@code scenario} by parsing its own file. */
    private static @Nullable PumpkinProcessDefinition definitionFor(@NotNull GherkinScenario scenario) {
        if (!GherkinPsiUtil.isPumpkinScenario(scenario)) return null;
        if (!(scenario.getContainingFile() instanceof GherkinFile gherkinFile)) return null;

        for (PumpkinProcessDefinition def : new PumpkinProcessParser().parse(gherkinFile)) {
            GherkinScenario candidate = def.getScenarioPsiElement();
            if (candidate != null && candidate.isEquivalentTo(scenario)) {
                return def;
            }
        }
        return null;
    }
}
