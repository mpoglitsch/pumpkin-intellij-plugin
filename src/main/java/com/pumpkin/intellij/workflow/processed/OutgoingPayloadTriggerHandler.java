package com.pumpkin.intellij.workflow.processed;

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.ui.SimpleListCellRenderer;
import com.pumpkin.intellij.endpoint.SwitchMethodEditor;
import com.pumpkin.intellij.util.FeatureFilePaths;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "op:" shortcut: typing this (optionally after a step keyword, e.g. {@code Then op:}) opens a
 * filterable popup of every outgoing-payload template file under the current feature file's own
 * {@code XX} folder (same scope/search as {@link OutgoingPayloadTemplateCompletionContributor} -
 * {@link OutgoingPayloadTemplates#findTemplateFiles}) - selecting one generates the whole {@code
 * <keyword> outgoing payload is compliant with template <path>} step, plus its required-fields
 * table (same {@link OutgoingPayloadTemplates#insertFieldsTable} both consumers share). Mirrors
 * {@code com.pumpkin.intellij.dbstep.DbStepTriggerHandler}'s own shape for the {@code db:}
 * shortcut.
 */
public class OutgoingPayloadTriggerHandler extends TypedHandlerDelegate {

    private static final Pattern OP_TRIGGER = Pattern.compile(
            "(?:(Given|When|Then|And|But|\\*)\\s+)?op:", Pattern.CASE_INSENSITIVE);

    private record TemplateChoice(@NotNull VirtualFile file, @NotNull String path) {
        @Override public String toString() { return path; }
    }

    @Override
    public @NotNull Result charTyped(char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        if (c != ':' || !(file instanceof GherkinFile)) return Result.CONTINUE;

        Document document = editor.getDocument();
        int caretOffset = editor.getCaretModel().getOffset();
        int lineNumber = document.getLineNumber(caretOffset);
        int lineStart = document.getLineStartOffset(lineNumber);
        String linePrefix = document.getText(new TextRange(lineStart, caretOffset)).strip();

        Matcher m = OP_TRIGGER.matcher(linePrefix);
        if (m.matches()) {
            openTemplatePopup(project, editor, file, lineStart, caretOffset, m.group(1));
        }
        return Result.CONTINUE;
    }

    private void openTemplatePopup(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file,
                                   int lineStart, int caretOffset, @Nullable String keyword) {
        if (DumbService.isDumb(project)) {
            HintManager.getInstance().showInformationHint(editor, "Still indexing - try again in a moment.");
            return;
        }

        VirtualFile featureFile = file.getVirtualFile();
        if (featureFile == null) return;
        VirtualFile xxDir = FeatureFilePaths.resolveDirTwoLevelsBelowFeatures(featureFile);
        if (xxDir == null) {
            HintManager.getInstance().showErrorHint(editor,
                    "This feature file isn't nested under a recognizable features/.../.../... structure.");
            return;
        }
        Module module = ModuleUtilCore.findModuleForFile(featureFile, project);
        if (module == null) {
            HintManager.getInstance().showErrorHint(editor, "Could not determine the module for this feature file.");
            return;
        }
        String xxName = xxDir.getName();
        VirtualFile templatesDir = OutgoingPayloadTemplates.findTemplatesDir(module, xxName);
        if (templatesDir == null) {
            HintManager.getInstance().showErrorHint(editor, "No \"" + xxName + "/templates\" folder found in any source root.");
            return;
        }

        List<TemplateChoice> choices = new ArrayList<>();
        for (VirtualFile candidate : OutgoingPayloadTemplates.findTemplateFiles(templatesDir)) {
            String relative = VfsUtilCore.getRelativePath(candidate, templatesDir);
            if (relative != null) {
                choices.add(new TemplateChoice(candidate, "/" + xxName + "/templates/" + relative));
            }
        }
        if (choices.isEmpty()) {
            HintManager.getInstance().showErrorHint(editor,
                    "No outgoing-payload template files found under " + xxName + "/templates.");
            return;
        }

        JBPopupFactory.getInstance()
                .createPopupChooserBuilder(choices)
                .setTitle("Choose Outgoing Payload Template")
                .setRenderer(SimpleListCellRenderer.create("", TemplateChoice::path))
                .setNamerForFiltering(TemplateChoice::path)
                .setItemChosenCallback(choice -> insertStep(project, editor, lineStart, caretOffset, keyword, choice))
                .createPopup()
                .showInBestPositionFor(editor);
    }

    private void insertStep(@NotNull Project project, @NotNull Editor editor, int lineStart, int caretOffset,
                            @Nullable String keyword, @NotNull TemplateChoice choice) {
        Document document = editor.getDocument();
        String resolvedKeyword = keyword != null ? keyword : "*";
        String stepText = resolvedKeyword + " outgoing payload is compliant with template " + choice.path();
        List<String> fields = OutgoingPayloadTemplates.readRequiredFields(choice.file());

        WriteCommandAction.runWriteCommandAction(project, "Insert Outgoing Payload Step", null, () -> {
            document.replaceString(lineStart, caretOffset, stepText);
            int stepEnd = lineStart + stepText.length();
            if (fields.isEmpty()) {
                editor.getCaretModel().moveToOffset(stepEnd);
                return;
            }
            String indent = SwitchMethodEditor.indentOf(document.getText(), lineStart);
            OutgoingPayloadTemplates.insertFieldsTable(document, editor, stepEnd, indent, fields);
        });
    }
}
