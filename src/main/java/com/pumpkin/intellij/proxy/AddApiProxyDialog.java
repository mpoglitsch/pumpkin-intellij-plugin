package com.pumpkin.intellij.proxy;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.pumpkin.intellij.api.ApiEndpointResolver;
import com.pumpkin.intellij.endpoint.NameUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.util.LinkedHashMap;
import java.util.Map;

/** Popover for entering a new API proxy's name and authentication method; see {@link ApiProxyCodeGenerator} for what happens on OK. */
public class AddApiProxyDialog extends DialogWrapper {

    /** One field's value entry: the text field for the raw name, plus the combo picking its {@link FieldValueSource}. */
    private record FieldRow(@NotNull JBTextField valueField, @NotNull JComboBox<FieldValueSource> sourceCombo) {
    }

    private final Project project;

    private final JBTextField nameField = new JBTextField();
    private final JComboBox<AuthenticationMethod> authCombo = new JComboBox<>(AuthenticationMethod.values());
    private final JPanel authFieldsPanel = new JPanel(new CardLayout());
    private final Map<AuthenticationMethod, Map<String, FieldRow>> fieldsByMethod = new LinkedHashMap<>();

    public AddApiProxyDialog(@NotNull Project project) {
        // MODELESS so the user can still click into the editor (e.g. to copy a context-parameter
        // or environment-variable name) while this dialog stays open, instead of having to close
        // it first - see the doOKAction() override below for why generation moves here instead of
        // staying in the caller (a modeless dialog's show() doesn't block, so showAndGet() can no
        // longer be used to gate "generate after close").
        super(project, true, IdeModalityType.MODELESS);
        this.project = project;

        nameField.getEmptyText().setText("This Is the API");
        buildAuthFieldsPanel();
        authCombo.addActionListener(e -> showCardFor(selectedMethod()));
        showCardFor(selectedMethod());

        setTitle("Add API Proxy");
        init();
    }

    private void buildAuthFieldsPanel() {
        for (AuthenticationMethod method : AuthenticationMethod.values()) {
            FormBuilder builder = FormBuilder.createFormBuilder();
            Map<String, FieldRow> fields = new LinkedHashMap<>();
            for (AuthenticationMethod.Field field : method.fields()) {
                JBTextField textField = new JBTextField();
                JComboBox<FieldValueSource> sourceCombo = new JComboBox<>(FieldValueSource.values());

                JPanel row = new JPanel(new BorderLayout(4, 0));
                row.add(textField, BorderLayout.CENTER);
                row.add(sourceCombo, BorderLayout.EAST);

                fields.put(field.key(), new FieldRow(textField, sourceCombo));
                builder.addLabeledComponent(field.label() + ":", row);
            }
            fieldsByMethod.put(method, fields);
            authFieldsPanel.add(builder.getPanel(), method.name());
        }
    }

    private @NotNull AuthenticationMethod selectedMethod() {
        AuthenticationMethod selected = (AuthenticationMethod) authCombo.getSelectedItem();
        return selected != null ? selected : AuthenticationMethod.NONE;
    }

    private void showCardFor(@NotNull AuthenticationMethod method) {
        ((CardLayout) authFieldsPanel.getLayout()).show(authFieldsPanel, method.name());
    }

    /** Validate as soon as the dialog opens (and continuously after), instead of only on first OK click. */
    @Override
    protected boolean postponeValidation() {
        return false;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        FormBuilder builder = new FormBuilder() {
            @Override
            protected int getFill(JComponent component) {
                return component instanceof JComboBox ? GridBagConstraints.HORIZONTAL : super.getFill(component);
            }
        };
        builder.addLabeledComponent("Proxy name:", nameField)
                .addTooltip("e.g. My Api -> ApiNotation.MY_API/MyApiProxy)")
                .addLabeledComponent("Authentication:", authCombo)
                .addComponent(authFieldsPanel);

        JPanel panel = builder.getPanel();
        // Wider than a plain text-only dialog needs - each auth field's row is a text field plus a
        // source combo (see buildAuthFieldsPanel), and the combo's own fixed width was eating into
        // the text field's share of a narrower panel.
        panel.setPreferredSize(new Dimension(620, panel.getPreferredSize().height));
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
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            return new ValidationInfo("Enter a proxy name.", nameField);
        }

        String constantName = NameUtils.toUpperSnakeCase(name);
        String className = NameUtils.toPascalCase(name) + "Proxy";

        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        PsiClass apiNotationClass = JavaPsiFacade.getInstance(project)
                .findClass(ApiEndpointResolver.API_NOTATION_FQN, scope);
        if (apiNotationClass == null) {
            return new ValidationInfo("Could not find " + ApiEndpointResolver.API_NOTATION_FQN + " in this project.");
        }
        for (PsiField field : apiNotationClass.getFields()) {
            if (constantName.equals(field.getName())) {
                return new ValidationInfo("ApiNotation." + constantName + " already exists.", nameField);
            }
        }
        for (PsiClass proxy : ApiEndpointResolver.findAllProxyClasses(project)) {
            if (className.equals(proxy.getName())) {
                return new ValidationInfo("A proxy class named " + className + " already exists.", nameField);
            }
        }

        AuthenticationMethod method = selectedMethod();
        for (AuthenticationMethod.Field field : method.fields()) {
            if (field.optional()) continue;
            JBTextField textField = fieldsByMethod.get(method).get(field.key()).valueField();
            if (textField.getText().trim().isEmpty()) {
                return new ValidationInfo(field.label() + " is required.", textField);
            }
        }

        return null;
    }

    /** Assembles the spec from the current field values. */
    private @NotNull NewApiProxySpec buildSpec() {
        String name = nameField.getText().trim();
        AuthenticationMethod method = selectedMethod();

        Map<String, AuthFieldValue> values = new LinkedHashMap<>();
        for (AuthenticationMethod.Field field : method.fields()) {
            FieldRow row = fieldsByMethod.get(method).get(field.key());
            FieldValueSource source = (FieldValueSource) row.sourceCombo().getSelectedItem();
            values.put(field.key(), new AuthFieldValue(
                    row.valueField().getText().trim(),
                    source != null ? source : FieldValueSource.CONTEXT_PARAMETER));
        }

        return new NewApiProxySpec(name, method, values);
    }

    /**
     * Generation happens here, on OK, rather than in the caller after {@code showAndGet()}
     * (see the constructor's doc comment for why a modeless dialog rules that pattern out). On
     * failure the dialog stays open (no {@code super.doOKAction()}) so the user can fix the
     * problem and retry without re-entering everything.
     */
    @Override
    protected void doOKAction() {
        try {
            ApiProxyCodeGenerator.generate(project, buildSpec());
            super.doOKAction();
        } catch (RuntimeException ex) {
            Messages.showErrorDialog(project, String.valueOf(ex.getMessage()), "Add API Proxy Failed");
        }
    }
}
