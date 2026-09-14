package com.pumpkin.intellij.refactor;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.pumpkin.intellij.model.PumpkinProcessDefinition;
import com.pumpkin.intellij.repository.PumpkinProcessService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Dimension;

/** Popover for entering the new Process's name; see {@link ExtractProcessGenerator} for what happens on OK. */
public class ExtractProcessDialog extends DialogWrapper {

    private final Project project;
    private final JBTextField nameField = new JBTextField();

    public ExtractProcessDialog(@NotNull Project project) {
        super(project, true);
        this.project = project;

        nameField.getEmptyText().setText("Renewal of service of customer {customerId}");

        setTitle("Extract Process");
        init();
    }

    /** Validate as soon as the dialog opens (and continuously after), instead of only on first OK click. */
    @Override
    protected boolean postponeValidation() {
        return false;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        // addComponent (not addLabeledComponent) puts the label and field on their own full-width
        // rows instead of side by side - addLabeledComponent squeezes the field into whatever
        // space is left next to the label, which is why it looked cramped.
        JPanel panel = FormBuilder.createFormBuilder()
                .addComponent(new JLabel("Process name:"))
                .addComponent(nameField)
                .getPanel();
        panel.setPreferredSize(new Dimension(500, panel.getPreferredSize().height));
        return panel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        try {
            return doValidateInternal();
        } catch (RuntimeException ex) {
            // Guarantees the user always sees *something* rather than a silently-disabled OK
            // button if an unexpected PSI lookup fails here.
            return new ValidationInfo("Validation failed: " + ex.getMessage());
        }
    }

    private @Nullable ValidationInfo doValidateInternal() {
        String name = getProcessName();
        if (name.isEmpty()) {
            return new ValidationInfo("Enter a process name.", nameField);
        }

        for (PumpkinProcessDefinition def : PumpkinProcessService.getInstance(project).getProcesses()) {
            if (name.equals(def.getProcessName())) {
                return new ValidationInfo("A Process named \"" + name + "\" already exists.", nameField);
            }
        }

        return null;
    }

    /** Call only after {@link #showAndGet()} returns true. */
    public @NotNull String getProcessName() {
        return nameField.getText().trim();
    }
}
