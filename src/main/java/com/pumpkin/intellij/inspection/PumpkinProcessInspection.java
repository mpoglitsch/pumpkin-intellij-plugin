package com.pumpkin.intellij.inspection;

import com.intellij.codeInspection.*;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.plugins.cucumber.psi.GherkinTableCell;
import com.pumpkin.intellij.model.PumpkinProcessDefinition;
import com.pumpkin.intellij.repository.PumpkinProcessService;
import com.pumpkin.intellij.util.GherkinPsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;
import org.jetbrains.plugins.cucumber.psi.GherkinTable;
import org.jetbrains.plugins.cucumber.psi.GherkinTableRow;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Inspection that warns when a Process invocation is missing required table parameters.
 *
 * <p>Reports:
 * <ul>
 *   <li>"Pumpkin Process is missing required parameter: name"</li>
 *   <li>"Pumpkin Process is missing required parameters: service, name"</li>
 *   <li>"Multiple Pumpkin Processes match this invocation." (ambiguous)</li>
 * </ul>
 */
public class PumpkinProcessInspection extends LocalInspectionTool {

    @Override
    public @NotNull String getGroupDisplayName() {
        return "Pumpkin";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Missing Pumpkin Process required parameter";
    }

    @Override
    public @NotNull String getShortName() {
        return "PumpkinMissingRequiredParameter";
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                                   boolean isOnTheFly) {
        return new PsiElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (element instanceof GherkinStep step) {
                    checkStep(step, holder);
                }
            }
        };
    }

    private void checkStep(@NotNull GherkinStep step, @NotNull ProblemsHolder holder) {
        if (!GherkinPsiUtil.isProcessStep(step)) return;

        String invocationText = GherkinPsiUtil.getProcessInvocationText(step);
        if (invocationText == null) return;

        PumpkinProcessService service =
                PumpkinProcessService.getInstance(step.getProject());
        List<PumpkinProcessDefinition> matches = service.findMatchingProcesses(invocationText);

        if (matches.isEmpty()) return; // unresolved – handled by the unresolved-reference highlight

        if (matches.size() > 1) {
            holder.registerProblem(
                    step,
                    "Multiple Pumpkin Processes match this invocation.",
                    ProblemHighlightType.WEAK_WARNING
            );
            return;
        }

        PumpkinProcessDefinition def = matches.get(0);
        List<String> required = def.getRequiredParameters();
        if (required.isEmpty()) return;

        List<String> provided = collectProvidedParameters(step);
        List<String> missing = required.stream()
                .filter(p -> !provided.contains(p))
                .collect(Collectors.toList());

        if (missing.isEmpty()) return;

        String message = missing.size() == 1
                ? "Pumpkin Process is missing required parameter: " + missing.get(0)
                : "Pumpkin Process is missing required parameters: " + String.join(", ", missing);

        holder.registerProblem(
                step,
                message,
                ProblemHighlightType.WARNING,
                new AddMissingProcessParameterQuickFix(missing)
        );
    }

    /** Reads key names from the first column of the step's data table. */
    @NotNull
    private List<String> collectProvidedParameters(@NotNull GherkinStep step) {
        List<String> keys = new ArrayList<>();
        GherkinTable table = step.getTable();
        if (table == null) return keys;

        for (GherkinTableRow row : PsiTreeUtil.findChildrenOfType(table, GherkinTableRow.class)) {
            List<GherkinTableCell> cells = row.getPsiCells();
            if (cells == null || cells.isEmpty()) continue;
            String key = cells.get(0).getText().trim();
            if (!key.isBlank()) {
                keys.add(key);
            }
        }
        return keys;
    }
}
