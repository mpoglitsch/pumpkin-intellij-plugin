package com.pumpkin.intellij.inspection;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.pumpkin.intellij.model.PumpkinProcessDefinition;
import com.pumpkin.intellij.repository.PumpkinProcessService;
import com.pumpkin.intellij.util.GherkinPsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.cucumber.psi.GherkinScenario;

import java.util.List;
import java.util.Objects;

/**
 * Flags a {@code @pumpkin} Scenario whose (scenario name, {@code @ProcessContext(...)}) pair is
 * already used by another Scenario elsewhere in the project - the same ambiguity
 * {@code ProcessExecutor.findProcess} would reject at runtime (two definitions matching both the
 * same name and the same requested context), surfaced directly in the editor instead of only at
 * test-run time. Multiple Scenarios sharing a name is expected and fine as long as each has a
 * distinct context (or exactly one is left untagged as the default).
 */
public class DuplicateProcessContextInspection extends LocalInspectionTool {

    @Override
    public @NotNull String getGroupDisplayName() {
        return "Pumpkin";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Duplicate Pumpkin Process name/context";
    }

    @Override
    public @NotNull String getShortName() {
        return "PumpkinDuplicateProcessContext";
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new PsiElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (!(element instanceof GherkinScenario scenario)) return;
                if (!GherkinPsiUtil.isPumpkinScenario(scenario)) return;

                String scenarioName = scenario.getScenarioName();
                if (scenarioName == null) return;
                String contextName = GherkinPsiUtil.getContextName(scenario);

                List<PumpkinProcessDefinition> all =
                        PumpkinProcessService.getInstance(scenario.getProject()).getProcesses();

                boolean duplicate = false;
                for (PumpkinProcessDefinition other : all) {
                    if (scenario.equals(other.getScenarioPsiElement())) continue; // this declaration itself
                    if (!scenarioName.equals(other.getScenarioName())) continue;
                    if (!Objects.equals(contextName, other.getContextName())) continue;
                    duplicate = true;
                    break;
                }

                if (!duplicate) return;

                String contextDescription = contextName != null
                        ? "context '" + contextName + "'"
                        : "the default (untagged) context";
                holder.registerProblem(
                        scenario,
                        "Another Pumpkin Process already uses this name for " + contextDescription
                                + " - the combination of scenario name and @ProcessContext must be unique.",
                        ProblemHighlightType.GENERIC_ERROR
                );
            }
        };
    }
}
