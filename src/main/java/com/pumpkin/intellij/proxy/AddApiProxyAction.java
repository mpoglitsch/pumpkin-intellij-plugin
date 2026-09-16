package com.pumpkin.intellij.proxy;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;

/** "Add API Proxy..." item on the {@code Pumpkin.FloatingToolbar} button's "APIs" submenu. */
public class AddApiProxyAction extends AnAction {

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
        if (DumbService.isDumb(project)) {
            Messages.showInfoMessage(project, "Please wait until indexing finishes.", "Add API Proxy");
            return;
        }

        // Modeless - see AddApiProxyDialog's constructor doc - so it's shown, not shown-and-waited-
        // on; generation happens inside the dialog's own doOKAction() instead of here.
        new AddApiProxyDialog(project).show();
    }
}
