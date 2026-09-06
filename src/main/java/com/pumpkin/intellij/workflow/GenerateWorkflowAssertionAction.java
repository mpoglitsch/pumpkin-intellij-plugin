package com.pumpkin.intellij.workflow;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;

import java.util.List;

/**
 * "Workflow Assertion..." item on the {@code Pumpkin.FloatingToolbar} button's "Generator"
 * submenu. Only usable when the Database Tools plugin (bundled with IntelliJ Ultimate, not
 * Community) is installed and enabled - see {@link WorkflowDataSourceBridge}'s doc.
 */
public class GenerateWorkflowAssertionAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
        e.getPresentation().setEnabledAndVisible(file instanceof GherkinFile);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        List<WorkflowDataSourceBridge> bridges = WorkflowDataSourceBridge.EP_NAME.getExtensionList();
        if (bridges.isEmpty()) {
            Messages.showInfoMessage(project,
                    "Generating workflow assertions needs the Database Tools and SQL plugin, "
                            + "which is bundled with IntelliJ IDEA Ultimate (not Community Edition).",
                    "Workflow Assertion");
            return;
        }

        new WorkflowAssertionDialog(project, bridges.get(0)).show();
    }
}
