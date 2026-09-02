package com.pumpkin.intellij.settings;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.pumpkin.intellij.repository.PumpkinProcessService;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings UI registered under Settings → Tools → Pumpkin.
 */
public class PumpkinSettingsConfigurable implements Configurable {

    private final @NotNull Project project;

    private JPanel mainPanel;
    private DefaultListModel<String> dirListModel;
    private JBList<String> dirList;
    private JCheckBox collapseSectionsCheckbox;

    public PumpkinSettingsConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Pumpkin";
    }

    @Override
    public @Nullable JComponent createComponent() {
        dirListModel = new DefaultListModel<>();
        dirList = new JBList<>(dirListModel);
        dirList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(dirList)
                .setAddAction(button -> addDirectory())
                .setRemoveAction(button -> removeSelected());

        JPanel listPanel = decorator.createPanel();
        listPanel.setPreferredSize(new Dimension(-1, 150));

        JBLabel dirLabel = new JBLabel("Process directories:");
        collapseSectionsCheckbox = new JCheckBox("Collapse sections by default when opening a feature file");

        JBLabel colorNote = new JBLabel(
                "<html>Highlight colors are configured under " +
                "<b>Settings → Editor → Color Scheme → Pumpkin</b>.</html>"
        );
        colorNote.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        JPanel southPanel = new JPanel(new GridLayout(0, 1, 0, 4));
        southPanel.add(collapseSectionsCheckbox);
        southPanel.add(colorNote);

        mainPanel = new JPanel(new BorderLayout(0, 6));
        mainPanel.add(dirLabel, BorderLayout.NORTH);
        mainPanel.add(listPanel, BorderLayout.CENTER);
        mainPanel.add(southPanel, BorderLayout.SOUTH);

        reset();
        return mainPanel;
    }

    private void addDirectory() {
        TextFieldWithBrowseButton field = new TextFieldWithBrowseButton();
        field.addBrowseFolderListener(
                "Select Process Directory", null, project,
                FileChooserDescriptorFactory.createSingleFolderDescriptor()
        );

        int result = JOptionPane.showConfirmDialog(
                mainPanel, field, "Add Process Directory",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        );

        if (result == JOptionPane.OK_OPTION) {
            String path = field.getText().trim();
            if (!path.isEmpty() && !dirListModel.contains(path)) {
                dirListModel.addElement(path);
            }
        }
    }

    private void removeSelected() {
        int idx = dirList.getSelectedIndex();
        if (idx >= 0) {
            dirListModel.remove(idx);
        }
    }

    @Override
    public boolean isModified() {
        PumpkinSettingsState state = PumpkinSettingsState.getInstance(project);
        return !getDirectoriesFromUI().equals(state.getProcessDirectories())
                || collapseSectionsCheckbox.isSelected() != state.collapseSectionsByDefault;
    }

    @Override
    public void apply() {
        PumpkinSettingsState state = PumpkinSettingsState.getInstance(project);
        state.setProcessDirectories(getDirectoriesFromUI());
        state.collapseSectionsByDefault = collapseSectionsCheckbox.isSelected();
        PumpkinProcessService.getInstance(project).invalidate();
    }

    @Override
    public void reset() {
        if (dirListModel == null) return;
        dirListModel.clear();
        PumpkinSettingsState state = PumpkinSettingsState.getInstance(project);
        for (String dir : state.getProcessDirectories()) {
            dirListModel.addElement(dir);
        }
        collapseSectionsCheckbox.setSelected(state.collapseSectionsByDefault);
    }

    private @NotNull List<String> getDirectoriesFromUI() {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < dirListModel.getSize(); i++) {
            result.add(dirListModel.getElementAt(i));
        }
        return result;
    }
}
