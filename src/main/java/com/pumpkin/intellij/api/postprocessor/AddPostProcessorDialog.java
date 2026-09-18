package com.pumpkin.intellij.api.postprocessor;

import com.intellij.json.JsonLanguage;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiField;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.pumpkin.intellij.api.ApiEndpointResolver;
import com.pumpkin.intellij.api.PumpkinPostprocessorResolver;
import com.pumpkin.intellij.ui.ScrollableLanguageTextField;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractCellEditor;
import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Popover for adding a PostProcessor case for one API endpoint; see {@code PostProcessorCodeGenerator} for what happens on OK. */
public class AddPostProcessorDialog extends DialogWrapper {

    private static final String CARD_EMPTY = "empty";
    private static final String CARD_TABLE = "table";

    /** See {@code AddEndpointDialog.ProxyChoice}'s doc for why {@code toString()} is overridden. */
    private record ProxyChoice(@NotNull PsiClass proxy, @NotNull String displayText) {
        @Override public String toString() { return displayText; }
    }

    private record EndpointChoice(@NotNull PsiEnumConstant constant, @NotNull String displayText) {
        @Override public String toString() { return displayText; }
    }

    private final Project project;
    private final List<PsiClass> proxies;
    private final List<ProxyChoice> proxyChoices;

    private final TextFieldWithBrowseButton proxyField = new TextFieldWithBrowseButton(e -> openProxyPicker());
    private @Nullable PsiClass selectedProxy;
    private @Nullable String selectedApiNotation;

    private List<EndpointChoice> endpointChoices = new ArrayList<>();
    private final TextFieldWithBrowseButton endpointField = new TextFieldWithBrowseButton(e -> openEndpointPicker());
    private @Nullable PsiEnumConstant selectedEndpoint;

    private final EditorTextField responseField;
    private List<JsonSampleFields.LeafField> currentFields = new ArrayList<>();

    private final JPanel mappingsPanel = new JPanel(new CardLayout());
    private final DefaultTableModel mappingsModel = new DefaultTableModel(new Object[]{"Field", "Action", "Value"}, 0);
    private final JBTable mappingsTable = new JBTable(mappingsModel);

    public AddPostProcessorDialog(@NotNull Project project) {
        // MODELESS - see AddEndpointDialog's constructor doc for why (generation happens in
        // doOKAction() below, not after showAndGet()).
        super(project, true, IdeModalityType.MODELESS);
        this.project = project;

        this.proxies = new ArrayList<>(ApiEndpointResolver.findAllProxyClasses(project));
        this.proxies.sort(Comparator.comparing(AddPostProcessorDialog::proxyDisplayText, String.CASE_INSENSITIVE_ORDER));

        this.proxyChoices = new ArrayList<>();
        for (PsiClass proxy : proxies) {
            proxyChoices.add(new ProxyChoice(proxy, proxyDisplayText(proxy)));
        }

        proxyField.setEditable(false);
        endpointField.setEditable(false);
        if (proxyChoices.isEmpty()) {
            proxyField.setText("(none)");
            proxyField.setEnabled(false);
            endpointField.setEnabled(false);
        } else {
            selectProxy(proxyChoices.get(0));
        }

        this.responseField = new ScrollableLanguageTextField(JsonLanguage.INSTANCE, project, "");
        responseField.setPreferredSize(new Dimension(480, 180));
        responseField.addDocumentListener(new DocumentListener() {
            @Override public void documentChanged(@NotNull DocumentEvent event) {
                refreshFields();
            }
        });

        setupMappingsTable();

        setTitle("Add PostProcessor");
        setOKActionEnabled(!proxyChoices.isEmpty());
        init();
    }

    /** Validate as soon as the dialog opens (and continuously after), instead of only on first OK click. */
    @Override
    protected boolean postponeValidation() {
        return false;
    }

    // -------------------------------------------------------------------------
    // Proxy / Endpoint pickers
    // -------------------------------------------------------------------------

    private static @NotNull String proxyDisplayText(@NotNull PsiClass proxy) {
        String notation = ApiEndpointResolver.apiNotationOf(proxy);
        return notation != null ? proxy.getName() + " (" + notation + ")" : proxy.getName();
    }

    private void openProxyPicker() {
        if (proxyChoices.isEmpty()) return;
        JBPopupFactory.getInstance()
                .createPopupChooserBuilder(proxyChoices)
                .setTitle("Choose Proxy")
                .setRenderer(SimpleListCellRenderer.create("", ProxyChoice::displayText))
                .setNamerForFiltering(ProxyChoice::displayText)
                .setItemChosenCallback(choice -> {
                    selectProxy(choice);
                    initValidation();
                })
                .createPopup()
                .showUnderneathOf(proxyField);
    }

    private void selectProxy(@NotNull ProxyChoice choice) {
        selectedProxy = choice.proxy();
        selectedApiNotation = ApiEndpointResolver.apiNotationOf(choice.proxy());
        proxyField.setText(choice.displayText());
        rebuildEndpointChoices();
    }

    /** Every endpoint on the selected proxy, minus ones that already have a PostProcessor case. */
    private void rebuildEndpointChoices() {
        endpointChoices = new ArrayList<>();
        PsiClass proxy = selectedProxy;
        if (proxy != null) {
            Set<String> handled = selectedApiNotation != null
                    ? PumpkinPostprocessorResolver.findHandledEndpointNames(project, selectedApiNotation)
                    : Set.of();
            for (PsiClass inner : proxy.getInnerClasses()) {
                if (!inner.isEnum()) continue;
                for (PsiField field : inner.getFields()) {
                    if (field instanceof PsiEnumConstant ec && !handled.contains(ec.getName())) {
                        endpointChoices.add(new EndpointChoice(ec, ec.getName().toLowerCase(Locale.ROOT).replace('_', ' ')));
                    }
                }
            }
        }
        endpointChoices.sort(Comparator.comparing(EndpointChoice::displayText));

        if (endpointChoices.isEmpty()) {
            selectedEndpoint = null;
            endpointField.setText("(none available)");
        } else {
            selectEndpoint(endpointChoices.get(0));
        }
    }

    private void openEndpointPicker() {
        if (endpointChoices.isEmpty()) return;
        JBPopupFactory.getInstance()
                .createPopupChooserBuilder(endpointChoices)
                .setTitle("Choose Endpoint")
                .setRenderer(SimpleListCellRenderer.create("", EndpointChoice::displayText))
                .setNamerForFiltering(EndpointChoice::displayText)
                .setItemChosenCallback(choice -> {
                    selectEndpoint(choice);
                    initValidation();
                })
                .createPopup()
                .showUnderneathOf(endpointField);
    }

    private void selectEndpoint(@NotNull EndpointChoice choice) {
        selectedEndpoint = choice.constant();
        endpointField.setText(choice.displayText());
    }

    // -------------------------------------------------------------------------
    // Expected response / field mappings
    // -------------------------------------------------------------------------

    private void refreshFields() {
        String json = responseField.getText();
        currentFields = JsonSampleFields.parse(project, json == null ? "" : json);

        CardLayout layout = (CardLayout) mappingsPanel.getLayout();
        if (currentFields.isEmpty()) {
            if (mappingsTable.isEditing()) mappingsTable.getCellEditor().stopCellEditing();
            mappingsModel.setRowCount(0);
            layout.show(mappingsPanel, CARD_EMPTY);
        } else {
            layout.show(mappingsPanel, CARD_TABLE);
        }
    }

    private void setupMappingsTable() {
        mappingsTable.getColumnModel().getColumn(0).setCellEditor(new FieldCellEditor());
        mappingsTable.getColumnModel().getColumn(1)
                .setCellEditor(new DefaultCellEditor(new JComboBox<>(MappingAction.values())));

        mappingsPanel.add(new JBLabel("Paste a JSON object above to define field mappings."), CARD_EMPTY);
        mappingsPanel.add(wrapMappingsTable(), CARD_TABLE);
        ((CardLayout) mappingsPanel.getLayout()).show(mappingsPanel, CARD_EMPTY);
    }

    /**
     * Shows the field name as read-only text; clicking anywhere in the cell (not just the browse
     * button) opens a filterable popup of every leaf path parsed from the Expected response field
     * - same shape as {@code AddApiProxyDialog.EnvironmentCellEditor}, including opening the
     * picker from {@code getTableCellEditorComponent} itself to work around the Swing quirk where
     * the click that activates a cell editor never reaches a listener on its sub-component.
     */
    private final class FieldCellEditor extends AbstractCellEditor implements TableCellEditor {

        private final TextFieldWithBrowseButton field = new TextFieldWithBrowseButton(e -> openPicker());

        FieldCellEditor() {
            field.setEditable(false);
        }

        private void openPicker() {
            if (currentFields.isEmpty()) return;
            List<String> paths = new ArrayList<>();
            for (JsonSampleFields.LeafField leaf : currentFields) paths.add(leaf.path());
            JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(paths)
                    .setTitle("Choose Field")
                    .setRenderer(SimpleListCellRenderer.create("", s -> s))
                    .setNamerForFiltering(s -> s)
                    .setItemChosenCallback(choice -> {
                        field.setText(choice);
                        stopCellEditing();
                    })
                    .createPopup()
                    .showUnderneathOf(field);
        }

        @Override
        public Object getCellEditorValue() {
            return field.getText();
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected,
                                                      int row, int column) {
            field.setText(value == null ? "" : value.toString());
            SwingUtilities.invokeLater(this::openPicker);
            return field;
        }
    }

    /** Mirrors {@code AddEndpointDialog.wrapTable}'s own add/remove-row table decoration. */
    private @NotNull JComponent wrapMappingsTable() {
        mappingsTable.setPreferredScrollableViewportSize(new Dimension(480, 90));
        return ToolbarDecorator.createDecorator(mappingsTable)
                .setAddAction(button -> {
                    if (currentFields.isEmpty()) return;
                    mappingsModel.addRow(new Object[]{currentFields.get(0).path(), MappingAction.SAVE_TO_CONTEXT, ""});
                })
                .setRemoveAction(button -> removeSelectedMappingRows())
                .createPanel();
    }

    private void removeSelectedMappingRows() {
        if (mappingsTable.isEditing()) {
            mappingsTable.getCellEditor().stopCellEditing();
        }
        int[] rows = mappingsTable.getSelectedRows();
        for (int i = rows.length - 1; i >= 0; i--) {
            mappingsModel.removeRow(rows[i]);
        }
    }

    // -------------------------------------------------------------------------
    // Layout / validation / generation
    // -------------------------------------------------------------------------

    @Override
    protected @Nullable JComponent createCenterPanel() {
        FormBuilder builder = new FormBuilder() {
            @Override
            protected int getFill(JComponent component) {
                return component instanceof JComboBox ? GridBagConstraints.HORIZONTAL : super.getFill(component);
            }
        };
        if (proxies.isEmpty()) {
            builder.addComponent(new JBLabel("No classes extending AbstractApiProxy were found in this project."));
        }
        builder.addLabeledComponent("Proxy:", proxyField)
                .addLabeledComponent("Endpoint:", endpointField)
                .addTooltip("Only endpoints without an existing PostProcessor case are shown")
                .addLabeledComponentFillVertically("Expected response (JSON):", responseField)
                .addLabeledComponentFillVertically("Field mappings:", mappingsPanel)
                .addTooltip("Save to Context: Value is the context-parameter name to write the field into. "
                        + "Equals: Value is the literal to compare against, or %contextParamName% to compare "
                        + "against that context parameter's current value instead.");

        JPanel panel = builder.getPanel();
        panel.setPreferredSize(new Dimension(600, panel.getPreferredSize().height));
        return panel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        try {
            return doValidateInternal();
        } catch (RuntimeException ex) {
            return new ValidationInfo("Validation failed: " + ex.getMessage());
        }
    }

    private @Nullable ValidationInfo doValidateInternal() {
        if (proxies.isEmpty()) {
            return new ValidationInfo("No classes extending AbstractApiProxy were found in this project.", proxyField);
        }
        PsiClass proxy = selectedProxy;
        if (proxy == null) {
            return new ValidationInfo("Select a proxy.", proxyField);
        }
        if (selectedApiNotation == null) {
            return new ValidationInfo("Could not determine the ApiNotation for this proxy.", proxyField);
        }
        if (endpointChoices.isEmpty()) {
            return new ValidationInfo("Every endpoint on this proxy already has a PostProcessor case.", endpointField);
        }
        if (selectedEndpoint == null) {
            return new ValidationInfo("Select an endpoint.", endpointField);
        }

        Set<String> seenFields = new HashSet<>();
        Set<String> availablePaths = new HashSet<>();
        for (JsonSampleFields.LeafField leaf : currentFields) availablePaths.add(leaf.path());

        for (int i = 0; i < mappingsModel.getRowCount(); i++) {
            String fieldPath = String.valueOf(mappingsModel.getValueAt(i, 0)).trim();
            String value = String.valueOf(mappingsModel.getValueAt(i, 2)).trim();
            if (fieldPath.isEmpty()) {
                return new ValidationInfo("Select a field for every mapping row.", mappingsTable);
            }
            if (!availablePaths.contains(fieldPath)) {
                return new ValidationInfo(fieldPath + " is no longer present in the expected response.", mappingsTable);
            }
            if (!seenFields.add(fieldPath)) {
                return new ValidationInfo("Duplicate mapping for field: " + fieldPath, mappingsTable);
            }
            if (value.isEmpty()) {
                return new ValidationInfo("Enter a value for the mapping on " + fieldPath + ".", mappingsTable);
            }
        }

        return null;
    }

    private @NotNull NewPostProcessorSpec buildSpec() {
        List<FieldMapping> mappings = new ArrayList<>();
        for (int i = 0; i < mappingsModel.getRowCount(); i++) {
            String fieldPath = String.valueOf(mappingsModel.getValueAt(i, 0)).trim();
            MappingAction action = (MappingAction) mappingsModel.getValueAt(i, 1);
            String value = String.valueOf(mappingsModel.getValueAt(i, 2)).trim();
            JsonSampleFields.LeafType leafType = leafTypeOf(fieldPath);
            mappings.add(new FieldMapping(fieldPath, leafType, action != null ? action : MappingAction.SAVE_TO_CONTEXT, value));
        }
        return new NewPostProcessorSpec(selectedProxy, selectedEndpoint, selectedApiNotation, mappings);
    }

    /** Validation already guarantees {@code fieldPath} is one of {@link #currentFields} by the time this runs. */
    private @NotNull JsonSampleFields.LeafType leafTypeOf(@NotNull String fieldPath) {
        for (JsonSampleFields.LeafField leaf : currentFields) {
            if (leaf.path().equals(fieldPath)) return leaf.type();
        }
        return JsonSampleFields.LeafType.STRING;
    }

    /**
     * Generation happens here, on OK, rather than in the caller after {@code showAndGet()} - see
     * {@code AddEndpointDialog}'s constructor doc for why a modeless dialog rules that pattern
     * out. On failure the dialog stays open so the user can fix the problem and retry.
     */
    @Override
    protected void doOKAction() {
        NewPostProcessorSpec spec = buildSpec();
        try {
            PostProcessorCodeGenerator.generate(project, spec);
            Messages.showInfoMessage(project,
                    "Added a PostProcessor case for " + spec.endpoint().getName() + ".", "Add PostProcessor");
            super.doOKAction();
        } catch (RuntimeException ex) {
            Messages.showErrorDialog(project, String.valueOf(ex.getMessage()), "Add PostProcessor Failed");
        }
    }
}
