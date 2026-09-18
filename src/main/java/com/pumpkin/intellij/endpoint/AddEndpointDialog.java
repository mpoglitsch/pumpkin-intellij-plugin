package com.pumpkin.intellij.endpoint;

import com.intellij.json.JsonLanguage;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.pumpkin.intellij.api.ApiEndpointResolver;
import com.pumpkin.intellij.ui.ScrollableLanguageTextField;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Popover for entering a new endpoint's shape; see {@link EndpointCodeGenerator} for what happens on OK. */
public class AddEndpointDialog extends DialogWrapper {

    /**
     * {@code toString()} is overridden (rather than relying on the record-generated one, which
     * would include {@code proxy}) for the same reason {@code ApiStepPopups.ApiChoice} does:
     * Swing's {@code JList} type-ahead-to-select calls {@code toString()} on list model items
     * directly from the raw AWT key event, with no read action - {@code PsiClass.toString()} calls
     * {@code getName()}, which requires one, and would throw
     * {@code Read access is allowed from inside read-action only} on every keystroke otherwise.
     */
    private record ProxyChoice(@NotNull PsiClass proxy, @NotNull String displayText) {
        @Override public String toString() { return displayText; }
    }

    private final Project project;
    private final List<PsiClass> proxies;
    private final List<ProxyChoice> proxyChoices;

    private final TextFieldWithBrowseButton proxyField = new TextFieldWithBrowseButton(e -> openProxyPicker());
    private @Nullable PsiClass selectedProxy;
    private final JBTextField nameField = new JBTextField();
    private final JComboBox<String> methodCombo = new JComboBox<>(new String[]{"GET", "POST", "PUT", "PATCH", "DELETE"});
    private final JBTextField pathField = new JBTextField();

    private final DefaultTableModel queryModel = new DefaultTableModel(new Object[]{"Name", "Required"}, 0) {
        @Override public Class<?> getColumnClass(int columnIndex) { return columnIndex == 1 ? Boolean.class : String.class; }
    };
    private final JBTable queryTable = new JBTable(queryModel);

    private final DefaultTableModel headerModel = new DefaultTableModel(new Object[]{"Provided Parameter", "Header Name"}, 0);
    private final JBTable headerTable = new JBTable(headerModel);

    private final EditorTextField bodyField;
    private final JComboBox<HttpStatusOption> statusCombo =
            new JComboBox<>(HttpStatusOption.ALL.toArray(new HttpStatusOption[0]));

    public AddEndpointDialog(@NotNull Project project) {
        // MODELESS so the user can still click into the editor (e.g. to check an existing path or
        // parameter name) while this dialog stays open - see doOKAction() below for why generation
        // moves here instead of the caller (a modeless dialog's show() doesn't block, so
        // showAndGet() can no longer be used to gate "generate after close").
        super(project, true, IdeModalityType.MODELESS);
        this.project = project;
        this.proxies = new ArrayList<>(ApiEndpointResolver.findAllProxyClasses(project));
        this.proxies.sort(Comparator.comparing(AddEndpointDialog::proxyDisplayText, String.CASE_INSENSITIVE_ORDER));

        this.proxyChoices = new ArrayList<>();
        for (PsiClass proxy : proxies) {
            proxyChoices.add(new ProxyChoice(proxy, proxyDisplayText(proxy)));
        }

        proxyField.setEditable(false);
        if (proxyChoices.isEmpty()) {
            proxyField.setText("(none)");
            proxyField.setEnabled(false);
        } else {
            selectProxy(proxyChoices.get(0));
        }

        this.bodyField = new ScrollableLanguageTextField(JsonLanguage.INSTANCE, project, "");
        bodyField.setPreferredSize(new Dimension(480, 180));
        pathField.getEmptyText().setText("/my/entered/path/with/{variable}");

        setTitle("Add API Endpoint");
        setOKActionEnabled(!proxies.isEmpty());
        init();
    }

    /** Validate as soon as the dialog opens (and continuously after), instead of only on first OK click. */
    @Override
    protected boolean postponeValidation() {
        return false;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        // JComboBox is never stretched by plain FormBuilder (getFill() hardcodes NONE for it),
        // so combo boxes would otherwise stay pinned to their renderer's natural width.
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
                .addTooltip("Only proxies with an existing endpoint enum are supported - click to search")
                .addLabeledComponent("Endpoint name:", nameField)
                .addLabeledComponent("HTTP method:", methodCombo)
                .addLabeledComponent("Path:", pathField)
                .addTooltip("Put path variables in curly braces, e.g. /orders/{orderId}")
                .addLabeledComponentFillVertically("Query parameters:", wrapTable(queryTable, () -> new Object[]{"", Boolean.FALSE}))
                .addLabeledComponentFillVertically("Headers:", wrapTable(headerTable, () -> new Object[]{"", ""}))
                .addLabeledComponentFillVertically("Body template (JSON):", bodyField)
                .addLabeledComponent("Success response code:", statusCombo);

        JPanel panel = builder.getPanel();
        panel.setPreferredSize(new Dimension(600, panel.getPreferredSize().height));
        return panel;
    }

    private static JComponent wrapTable(@NotNull JBTable table, @NotNull java.util.function.Supplier<Object[]> newRow) {
        table.setPreferredScrollableViewportSize(new Dimension(480, 90));
        return ToolbarDecorator.createDecorator(table)
                .setAddAction(button -> ((DefaultTableModel) table.getModel()).addRow(newRow.get()))
                .setRemoveAction(button -> removeSelectedRows(table))
                .createPanel();
    }

    /** Removes every selected row, highest index first so earlier indices stay valid mid-removal. */
    private static void removeSelectedRows(@NotNull JBTable table) {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        int[] rows = table.getSelectedRows();
        DefaultTableModel model = (DefaultTableModel) table.getModel();
        for (int i = rows.length - 1; i >= 0; i--) {
            model.removeRow(rows[i]);
        }
    }

    private static @NotNull String proxyDisplayText(@NotNull PsiClass proxy) {
        String notation = ApiEndpointResolver.apiNotationOf(proxy);
        return notation != null ? proxy.getName() + " (" + notation + ")" : proxy.getName();
    }

    /**
     * Opens a real filterable popup (substring match, results narrow live as you type - see
     * {@code ApiStepPopups} for the same, already-established pattern used by the {@code api:}/
     * {@code auth:} shortcuts) instead of a plain combo box's speed search, which only lets you
     * jump to the next prefix match without ever narrowing what's shown.
     */
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
        proxyField.setText(choice.displayText());
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        try {
            return doValidateInternal();
        } catch (RuntimeException ex) {
            // Guarantees the user always sees *something* rather than a silently-disabled OK
            // button if an unexpected PSI/VFS lookup fails here.
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
        if (EndpointCodeGenerator.findEndpointEnum(proxy) == null) {
            return new ValidationInfo(
                    "Selected proxy has no endpoint enum yet — this action only adds to an existing one.", proxyField);
        }

        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            return new ValidationInfo("Enter an endpoint name.", nameField);
        }
        String constantName = NameUtils.toUpperSnakeCase(name);
        if (ApiEndpointResolver.findEndpointConstant(proxy, constantName) != null) {
            return new ValidationInfo("An endpoint named " + constantName + " already exists on this proxy.", nameField);
        }

        String path = pathField.getText().trim();
        if (path.isEmpty() || !path.startsWith("/")) {
            return new ValidationInfo("Path must be non-empty and start with '/'.", pathField);
        }

        Set<String> seenQueryNames = new HashSet<>();
        for (int i = 0; i < queryModel.getRowCount(); i++) {
            String qname = String.valueOf(queryModel.getValueAt(i, 0)).trim();
            if (qname.isEmpty()) {
                return new ValidationInfo("Query parameter name cannot be blank.", queryTable);
            }
            if (!seenQueryNames.add(qname.toLowerCase(Locale.ROOT))) {
                return new ValidationInfo("Duplicate query parameter: " + qname, queryTable);
            }
        }

        Set<String> seenHeaderKeys = new HashSet<>();
        for (int i = 0; i < headerModel.getRowCount(); i++) {
            String provided = String.valueOf(headerModel.getValueAt(i, 0)).trim();
            String key = String.valueOf(headerModel.getValueAt(i, 1)).trim();
            if (provided.isEmpty() || key.isEmpty()) {
                return new ValidationInfo("Header rows need both a provided-parameter name and a header name.", headerTable);
            }
            if (!seenHeaderKeys.add(key.toUpperCase(Locale.ROOT))) {
                return new ValidationInfo("Duplicate header: " + key, headerTable);
            }
        }

        String bodyJson = bodyField.getText();
        if (bodyJson != null && !bodyJson.isBlank()) {
            String camelName = NameUtils.toCamelCase(name);
            String apiNotation = ApiEndpointResolver.apiNotationOf(proxy);
            String apiNameKebab = NameUtils.toKebabCase(apiNotation != null ? apiNotation : proxy.getName());
            if (templateFileAlreadyExists(proxy, apiNameKebab, camelName)) {
                return new ValidationInfo(
                        "A template file named " + camelName + ".json already exists for " + apiNameKebab + ".", bodyField);
            }
        }

        return null;
    }

    private static boolean templateFileAlreadyExists(@NotNull PsiClass proxy, @NotNull String apiNameKebab,
                                                      @NotNull String camelName) {
        Module module = ModuleUtilCore.findModuleForPsiElement(proxy);
        if (module == null) return false;
        String relative = "api/templates/" + apiNameKebab + "/" + camelName + ".json";
        for (VirtualFile root : ModuleRootManager.getInstance(module).getSourceRoots()) {
            if (root.findFileByRelativePath(relative) != null) return true;
        }
        return false;
    }

    /**
     * Generation happens here, on OK, rather than in the caller after {@code showAndGet()} (see
     * the constructor's doc comment for why a modeless dialog rules that pattern out). On failure
     * the dialog stays open (no {@code super.doOKAction()}) so the user can fix the problem and
     * retry without re-entering everything.
     */
    @Override
    protected void doOKAction() {
        NewEndpointSpec spec = buildSpec();
        try {
            EndpointCodeGenerator.generate(project, spec);
            Messages.showInfoMessage(project,
                    "Added " + spec.endpointName() + " to " + spec.proxyClass().getName() + ".",
                    "Add API Endpoint");
            super.doOKAction();
        } catch (RuntimeException ex) {
            Messages.showErrorDialog(project, String.valueOf(ex.getMessage()), "Add API Endpoint Failed");
        }
    }

    /** Assembles the spec from the current field values. */
    private @NotNull NewEndpointSpec buildSpec() {
        PsiClass proxy = selectedProxy;
        String name = nameField.getText().trim();
        String method = (String) methodCombo.getSelectedItem();
        String path = pathField.getText().trim();

        List<QueryParamRow> queryRows = new ArrayList<>();
        for (int i = 0; i < queryModel.getRowCount(); i++) {
            queryRows.add(new QueryParamRow(
                    String.valueOf(queryModel.getValueAt(i, 0)).trim(),
                    Boolean.TRUE.equals(queryModel.getValueAt(i, 1))));
        }

        List<HeaderRow> headerRows = new ArrayList<>();
        for (int i = 0; i < headerModel.getRowCount(); i++) {
            headerRows.add(new HeaderRow(
                    String.valueOf(headerModel.getValueAt(i, 0)).trim(),
                    String.valueOf(headerModel.getValueAt(i, 1)).trim()));
        }

        String bodyJson = bodyField.getText();
        HttpStatusOption status = (HttpStatusOption) statusCombo.getSelectedItem();

        return new NewEndpointSpec(proxy, name, method, path, queryRows, headerRows,
                (bodyJson == null || bodyJson.isBlank()) ? null : bodyJson, status);
    }
}
