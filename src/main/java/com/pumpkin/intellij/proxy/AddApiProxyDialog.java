package com.pumpkin.intellij.proxy;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
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
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.util.LinkedHashMap;
import java.util.Map;

/** Popover for entering a new API proxy's name and authentication method; see {@link ApiProxyCodeGenerator} for what happens on OK. */
public class AddApiProxyDialog extends DialogWrapper {

    private final Project project;

    private final JBTextField nameField = new JBTextField();
    private final JComboBox<AuthenticationMethod> authCombo = new JComboBox<>(AuthenticationMethod.values());
    private final JPanel authFieldsPanel = new JPanel(new CardLayout());
    private final Map<AuthenticationMethod, Map<String, JBTextField>> fieldsByMethod = new LinkedHashMap<>();

    public AddApiProxyDialog(@NotNull Project project) {
        super(project, true);
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
            Map<String, JBTextField> fields = new LinkedHashMap<>();
            for (AuthenticationMethod.Field field : method.fields()) {
                JBTextField textField = new JBTextField();
                fields.put(field.key(), textField);
                builder.addLabeledComponent(field.label() + ":", textField);
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
        panel.setPreferredSize(new Dimension(480, panel.getPreferredSize().height));
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
            JBTextField textField = fieldsByMethod.get(method).get(field.key());
            if (textField.getText().trim().isEmpty()) {
                return new ValidationInfo(field.label() + " is required.", textField);
            }
        }

        return null;
    }

    /** Assembles the spec from the current field values. Call only after {@link #showAndGet()} returns true. */
    public @NotNull NewApiProxySpec buildSpec() {
        String name = nameField.getText().trim();
        AuthenticationMethod method = selectedMethod();

        Map<String, String> values = new LinkedHashMap<>();
        for (AuthenticationMethod.Field field : method.fields()) {
            values.put(field.key(), fieldsByMethod.get(method).get(field.key()).getText().trim());
        }

        return new NewApiProxySpec(name, method, values);
    }
}
