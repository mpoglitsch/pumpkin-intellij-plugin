package com.pumpkin.intellij.refactor;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;
import org.jetbrains.plugins.cucumber.psi.GherkinStepsHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * "Extract Process..." - select a run of Gherkin steps and extract them into a new, separate
 * Pumpkin Process feature file under a sibling {@code _processes} folder, replacing the selection
 * with the generated {@code Process: <name> without data} invocation. Registered under the
 * standard {@code RefactoringMenu} group (appears in both the main Refactor menu and the editor's
 * right-click Refactor submenu - confirmed via the bundled platform's own action XML, since that
 * group isn't just a plain popup) with {@code use-shortcut-of="ExtractMethod"} so it shares Java's
 * Extract Method shortcut in whatever keymap is active, including a user's own customization.
 */
public class ExtractProcessAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(findSelectedSteps(e) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        List<GherkinStep> steps = findSelectedSteps(e);
        if (project == null || steps == null) return;

        if (DumbService.isDumb(project)) {
            Messages.showInfoMessage(project, "Please wait until indexing finishes.", "Extract Process");
            return;
        }

        ExtractProcessDialog dialog = new ExtractProcessDialog(project);
        if (!dialog.showAndGet()) return;

        try {
            ExtractProcessGenerator.extract(project, e.getData(CommonDataKeys.EDITOR), steps, dialog.getProcessName());
        } catch (RuntimeException ex) {
            Messages.showErrorDialog(project, String.valueOf(ex.getMessage()), "Extract Process Failed");
        }
    }

    /**
     * Returns the ordered list of {@link GherkinStep}s fully contained by the current selection
     * (a step only partially covered by the selection doesn't count - same spirit as Extract
     * Method requiring a clean, statement-aligned selection), all belonging to the same
     * {@link GherkinStepsHolder} - the common interface behind {@code Scenario:}, {@code Scenario
     * Outline:}, and {@code Background:} alike, since {@code GherkinScenarioOutline} is a sibling
     * interface of {@code GherkinScenario}, not a subtype of it; matching only {@code
     * GherkinScenario} previously made every step inside a {@code Scenario Outline:} invisible to
     * this action regardless of selection or file location - in a file with some ancestor
     * directory literally named {@code features} (case-insensitive - confirmed with the user, the
     * segments below it can be anything) - or {@code null} if any of that doesn't hold, meaning
     * there's nothing valid to extract right now.
     */
    private static @Nullable List<GherkinStep> findSelectedSteps(@NotNull AnActionEvent e) {
        PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (!(file instanceof GherkinFile) || editor == null) return null;
        if (!hasFeaturesAncestor(file)) return null;

        SelectionModel selection = editor.getSelectionModel();
        if (!selection.hasSelection()) return null;
        TextRange selectionRange = TextRange.create(selection.getSelectionStart(), selection.getSelectionEnd());

        List<GherkinStep> selectedSteps = new ArrayList<>();
        GherkinStepsHolder scenario = null;
        for (GherkinStep step : PsiTreeUtil.findChildrenOfType(file, GherkinStep.class)) {
            if (!selectionRange.contains(step.getTextRange())) continue;

            GherkinStepsHolder enclosing = PsiTreeUtil.getParentOfType(step, GherkinStepsHolder.class);
            if (enclosing == null) return null;
            if (scenario == null) {
                scenario = enclosing;
            } else if (scenario != enclosing) {
                return null; // selection spans more than one Scenario/Outline/Background
            }
            selectedSteps.add(step);
        }
        return selectedSteps.isEmpty() ? null : selectedSteps;
    }

    private static boolean hasFeaturesAncestor(@NotNull PsiFile file) {
        VirtualFile vf = file.getVirtualFile();
        for (VirtualFile dir = vf == null ? null : vf.getParent(); dir != null; dir = dir.getParent()) {
            if ("features".equalsIgnoreCase(dir.getName())) return true;
        }
        return false;
    }
}
