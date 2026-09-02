package com.pumpkin.intellij.endpoint;

import com.intellij.json.JsonLanguage;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.LanguageTextField;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.pumpkin.intellij.api.ApiEndpointResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Popover for entering a new endpoint's shape; see {@link EndpointCodeGenerator} for what happens on OK. */
public class AddEndpointDialog extends DialogWrapper {

    private final List<PsiClass> proxies;

    private final JComboBox<PsiClass> proxyCombo;
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
        super(project, true);
        this.proxies = ApiEndpointResolver.findAllProxyClasses(project);
        this.proxyCombo = new JComboBox<>(proxies.toArray(new PsiClass[0]));
        proxyCombo.setRenderer(SimpleListCellRenderer.create("(none)", proxy -> {
            String notation = ApiEndpointResolver.apiNotationOf(proxy);
            return notation != null ? proxy.getName() + " (" + notation + ")" : proxy.getName();
        }));

        this.bodyField = new LanguageTextField(JsonLanguage.INSTANCE, project, "", false);
        bodyField.setOneLineMode(false);
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
        builder.addLabeledComponent("Proxy:", proxyCombo)
                .addTooltip("Only proxies with an existing endpoint enum are supported")
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
                .createPanel();
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
            return new ValidationInfo("No classes extending AbstractApiProxy were found in this project.", proxyCombo);
        }
        PsiClass proxy = (PsiClass) proxyCombo.getSelectedItem();
        if (proxy == null) {
            return new ValidationInfo("Select a proxy.", proxyCombo);
        }
        if (EndpointCodeGenerator.findEndpointEnum(proxy) == null) {
            return new ValidationInfo(
                    "Selected proxy has no endpoint enum yet — this action only adds to an existing one.", proxyCombo);
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

    /** Assembles the spec from the current field values. Call only after {@link #showAndGet()} returns true. */
    public @NotNull NewEndpointSpec buildSpec() {
        PsiClass proxy = (PsiClass) proxyCombo.getSelectedItem();
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
