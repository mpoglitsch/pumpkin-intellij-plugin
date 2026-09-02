package com.pumpkin.intellij.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiFile;
import com.pumpkin.intellij.endpoint.AddEndpointDialog;
import com.pumpkin.intellij.endpoint.EndpointCodeGenerator;
import com.pumpkin.intellij.endpoint.NewEndpointSpec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;

/** "Add API Endpoint..." item on the {@code Pumpkin.FloatingToolbar} button's popup menu. */
public class AddApiEndpointAction extends AnAction {

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
            Messages.showInfoMessage(project, "Please wait until indexing finishes.", "Add API Endpoint");
            return;
        }

        AddEndpointDialog dialog = new AddEndpointDialog(project);
        if (!dialog.showAndGet()) return;

        NewEndpointSpec spec = dialog.buildSpec();
        try {
            EndpointCodeGenerator.generate(project, spec);
            Messages.showInfoMessage(project,
                    "Added " + spec.endpointName() + " to " + spec.proxyClass().getName() + ".",
                    "Add API Endpoint");
        } catch (RuntimeException ex) {
            Messages.showErrorDialog(project, String.valueOf(ex.getMessage()), "Add API Endpoint Failed");
        }
    }
}
