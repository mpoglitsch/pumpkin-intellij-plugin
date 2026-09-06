package com.pumpkin.intellij.workflow;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.LanguageTextField;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.pumpkin.intellij.settings.PumpkinSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinLanguage;

import javax.swing.Action;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Consumer;

/**
 * "Generator ▸ Workflow Assertion" popover: looks a Workflow ID up against a chosen datasource
 * and generates the "Then an open workflow ... / * workflowitem ..." Gherkin step block, ready to
 * paste into a feature file. See {@link GenerateWorkflowAssertionAction} for how this is opened
 * and {@link WorkflowDataSourceBridge} for the Database-plugin seam.
 *
 * <p>"Generate" does double duty: with no scenario chosen yet, it looks up which scenario(s) the
 * Workflow ID belongs to and immediately continues on to generate from the <em>first</em> one
 * (the workflow's default scenario, if it has one - see {@link WorkflowDataSourceBridge#findScenarios}'s
 * doc on ordering) - one click, one result, even when the ID is ambiguous. Every match is also
 * loaded into the scenario combo, so if the default isn't the one the user actually wants, picking
 * a different entry there and clicking "Generate" again (now with the scenario combo enabled) uses
 * that selection instead. Editing the Workflow ID always resets that combo, since a stale
 * selection from a previous ID must never be reused silently.
 */
public class WorkflowAssertionDialog extends DialogWrapper {

    private final Project project;
    private final WorkflowDataSourceBridge bridge;

    private final JComboBox<PumpkinDataSourceRef> dataSourceCombo = new JComboBox<>();
    private final JBTextField workflowIdField = new JBTextField();
    private final JButton generateButton = new JButton("Generate");
    private final JComboBox<ScenarioMatch> scenarioCombo = new JComboBox<>();
    private final JBLabel errorLabel = new JBLabel();
    private final LanguageTextField outputField;
    private final JButton copyButton = new JButton("Copy");

    /** Guards against reacting to scenarioCombo being repopulated programmatically. */
    private boolean populatingScenarioCombo;

    public WorkflowAssertionDialog(@NotNull Project project, @NotNull WorkflowDataSourceBridge bridge) {
        super(project, true);
        this.project = project;
        this.bridge = bridge;

        this.outputField = new LanguageTextField(GherkinLanguage.INSTANCE, project, "", false);
        outputField.setOneLineMode(false);
        outputField.setPreferredSize(new Dimension(560, 220));
        outputField.setViewer(true);

        errorLabel.setForeground(JBColor.RED);
        errorLabel.setVisible(false);

        workflowIdField.getEmptyText().setText("Workflow ID");
        scenarioCombo.setRenderer(SimpleListCellRenderer.create("(only needed for an ambiguous Workflow ID)",
                m -> m.scenarioId() + " - " + m.scenarioName()));
        scenarioCombo.setEnabled(false);

        populateDataSources();

        generateButton.addActionListener(e -> onGenerateClicked());
        copyButton.addActionListener(e -> onCopyClicked());
        dataSourceCombo.addActionListener(e -> onDataSourceChanged());
        // Any edit to the Workflow ID invalidates whatever scenario list/output is currently
        // showing - otherwise a later Generate click could silently reuse a stale scenario
        // selection that belonged to a different ID.
        workflowIdField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onWorkflowIdEdited(); }
            @Override public void removeUpdate(DocumentEvent e) { onWorkflowIdEdited(); }
            @Override public void changedUpdate(DocumentEvent e) { onWorkflowIdEdited(); }
        });

        setTitle("Generate Workflow Assertion");
        init();
    }

    private void populateDataSources() {
        List<PumpkinDataSourceRef> dataSources = bridge.listDataSources(project);
        DefaultComboBoxModel<PumpkinDataSourceRef> model = new DefaultComboBoxModel<>();
        for (PumpkinDataSourceRef ds : dataSources) {
            model.addElement(ds);
        }
        dataSourceCombo.setModel(model);

        String rememberedId = PumpkinSettingsState.getInstance(project).workflowAssertionDataSourceId;
        if (rememberedId != null) {
            for (PumpkinDataSourceRef ds : dataSources) {
                if (ds.id().equals(rememberedId)) {
                    dataSourceCombo.setSelectedItem(ds);
                    break;
                }
            }
        }
    }

    private void onDataSourceChanged() {
        PumpkinDataSourceRef selected = (PumpkinDataSourceRef) dataSourceCombo.getSelectedItem();
        if (selected != null) {
            PumpkinSettingsState.getInstance(project).workflowAssertionDataSourceId = selected.id();
        }
    }

    private void onWorkflowIdEdited() {
        clearError();
        outputField.setText("");
        setScenarioChoices(new ScenarioMatch[0]);
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel generateRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        generateRow.add(generateButton);

        JPanel outputHeaderPanel = new JPanel(new BorderLayout());
        outputHeaderPanel.add(new JBLabel("Generated steps:"), BorderLayout.WEST);
        outputHeaderPanel.add(copyButton, BorderLayout.EAST);

        FormBuilder builder = new FormBuilder() {
            @Override
            protected int getFill(JComponent component) {
                return component instanceof JComboBox ? GridBagConstraints.HORIZONTAL : super.getFill(component);
            }
        };
        builder.addLabeledComponent("Datasource:", dataSourceCombo)
                .addLabeledComponent("Workflow ID:", workflowIdField)
                .addLabeledComponent("Scenario:", scenarioCombo)
                .addComponent(generateRow)
                .addComponent(errorLabel)
                .addSeparator()
                .addComponent(outputHeaderPanel)
                .addComponentFillVertically(outputField, 4);

        JPanel panel = builder.getPanel();
        panel.setPreferredSize(new Dimension(600, panel.getPreferredSize().height));
        return panel;
    }

    /** Only a single "Close" action - Generate/Copy (in the center panel) are the real actions. */
    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{new DialogWrapperAction("Close") {
            @Override
            protected void doAction(ActionEvent e) {
                close(CANCEL_EXIT_CODE);
            }
        }};
    }

    // -------------------------------------------------------------------------
    // Generate flow
    // -------------------------------------------------------------------------

    private void onGenerateClicked() {
        clearError();

        PumpkinDataSourceRef dataSource = (PumpkinDataSourceRef) dataSourceCombo.getSelectedItem();
        if (dataSource == null) {
            showError("Select a datasource first.");
            return;
        }

        Long workflowId = parseWorkflowId();
        if (workflowId == null) return;

        if (scenarioCombo.isEnabled()) {
            // A previous click already found more than one scenario for this same Workflow ID
            // (still enabled - onWorkflowIdEdited() disables it the moment the ID changes) - this
            // click confirms whichever one is currently selected.
            ScenarioMatch selected = (ScenarioMatch) scenarioCombo.getSelectedItem();
            if (selected == null) {
                showError("Select a scenario first.");
                return;
            }
            loadWorkflowItems(dataSource, workflowId, selected.scenarioId());
            return;
        }

        outputField.setText("");
        setBusy(true);
        runInBackground(
                () -> bridge.findScenarios(project, dataSource, workflowId),
                scenarios -> {
                    if (scenarios.isEmpty()) {
                        setBusy(false);
                        showError("Workflow " + workflowId + " does not exist.");
                        return;
                    }
                    // The bridge orders these with the workflow's default scenario (if any)
                    // first - generate from that immediately; every match is still loaded into
                    // the combo so the user can pick a different one and regenerate.
                    setScenarioChoices(scenarios.toArray(new ScenarioMatch[0]));
                    scenarioCombo.setSelectedIndex(0);
                    loadWorkflowItems(dataSource, workflowId, scenarios.get(0).scenarioId());
                },
                error -> {
                    setBusy(false);
                    showError("Could not look up workflow " + workflowId + ": " + error.getMessage());
                });
    }

    private void loadWorkflowItems(PumpkinDataSourceRef dataSource, long workflowId, long scenarioId) {
        clearError();
        setBusy(true);
        runInBackground(
                () -> bridge.findWorkflowItems(project, dataSource, workflowId, scenarioId),
                rows -> {
                    setBusy(false);
                    if (rows.isEmpty()) {
                        showError("No workflow items found for workflow " + workflowId
                                + ", scenario " + scenarioId + ".");
                        return;
                    }
                    outputField.setText(WorkflowAssertionTextGenerator.generate(rows));
                },
                error -> {
                    setBusy(false);
                    showError("Could not look up workflow items: " + error.getMessage());
                });
    }

    private void onCopyClicked() {
        String text = outputField.getText();
        if (!text.isEmpty()) {
            CopyPasteManager.getInstance().setContents(new StringSelection(text));
        }
    }

    private @Nullable Long parseWorkflowId() {
        try {
            return Long.parseLong(workflowIdField.getText().trim());
        } catch (NumberFormatException e) {
            showError("Workflow ID must be a number.");
            return null;
        }
    }

    private void setScenarioChoices(ScenarioMatch[] scenarios) {
        populatingScenarioCombo = true;
        try {
            scenarioCombo.setModel(new DefaultComboBoxModel<>(scenarios));
            scenarioCombo.setEnabled(scenarios.length > 0);
        } finally {
            populatingScenarioCombo = false;
        }
    }

    /**
     * Makes it unmistakable that a lookup is running: the Generate button is disabled and
     * relabeled, every other input is locked so nothing can change mid-lookup, and the dialog
     * shows a wait cursor.
     */
    private void setBusy(boolean busy) {
        generateButton.setEnabled(!busy);
        generateButton.setText(busy ? "Generating..." : "Generate");
        dataSourceCombo.setEnabled(!busy);
        workflowIdField.setEnabled(!busy);
        if (!busy) {
            // Re-enable only if there's actually something to choose from - don't resurrect a
            // combo that was deliberately left disabled/empty.
            scenarioCombo.setEnabled(scenarioCombo.getItemCount() > 0);
        } else {
            scenarioCombo.setEnabled(false);
        }
        getRootPane().setCursor(Cursor.getPredefinedCursor(busy ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
    }

    private void clearError() {
        errorLabel.setText("");
        errorLabel.setVisible(false);
    }

    /**
     * Runs {@code work} on a pooled background thread (never the EDT - these are blocking
     * database round trips), then delivers the result or the thrown {@link SQLException} back on
     * the EDT at this dialog's own modality state - a plain {@code invokeLater} can otherwise
     * never fire while this modal dialog is showing.
     */
    private <T> void runInBackground(SqlSupplier<T> work, Consumer<T> onSuccess, Consumer<Exception> onError) {
        ModalityState modality = ModalityState.stateForComponent(getRootPane());
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                T result = work.get();
                ApplicationManager.getApplication().invokeLater(() -> onSuccess.accept(result), modality);
            } catch (Exception e) {
                ApplicationManager.getApplication().invokeLater(() -> onError.accept(e), modality);
            }
        });
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }
}
