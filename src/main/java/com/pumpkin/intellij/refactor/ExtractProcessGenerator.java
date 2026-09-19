package com.pumpkin.intellij.refactor;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.pumpkin.intellij.endpoint.NameUtils;
import com.pumpkin.intellij.util.FeatureFilePaths;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Performs the actual "Extract Process" work: creates the new Process feature file under a
 * sibling {@code _processes} folder and replaces the selected steps in the original file with the
 * generated invocation - all inside one {@link WriteCommandAction}, one undo step. Mirrors
 * {@code ApiProxyCodeGenerator}'s "build a whole file from a string, write it out" approach (there
 * is no existing content to preserve in the new file) and its plain VFS-only file creation
 * ({@code BodyTemplateEditor}'s own pattern: {@code createDirectoryIfMissing} +
 * {@code createChildData} + {@code saveText}, no PSI reformat needed for hand-built,
 * already-correctly-indented Gherkin).
 */
final class ExtractProcessGenerator {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{[a-zA-Z_][a-zA-Z0-9_]*}");
    private static final Pattern KEYWORD_PATTERN =
            Pattern.compile("^(Given|When|Then|And|But|\\*)\\s+", Pattern.CASE_INSENSITIVE);

    private ExtractProcessGenerator() {}

    /**
     * @param steps the ordered, fully-selected steps to extract - all from the same
     *              GherkinStepsHolder (Scenario/Outline/Background - see
     *              {@code ExtractProcessAction.findSelectedSteps}).
     */
    static void extract(@NotNull Project project, @Nullable Editor editor,
                        @NotNull List<GherkinStep> steps, @NotNull String processName) {
        if (editor == null) throw new IllegalStateException("No active editor.");
        if (steps.isEmpty()) throw new IllegalStateException("No steps selected.");
        if (processName.isBlank()) throw new IllegalArgumentException("Process name must not be empty.");

        GherkinStep firstStep = steps.get(0);
        GherkinStep lastStep = steps.get(steps.size() - 1);

        VirtualFile currentFile = firstStep.getContainingFile().getVirtualFile();
        VirtualFile anchorDir = currentFile == null ? null : FeatureFilePaths.resolveDirTwoLevelsBelowFeatures(currentFile);
        if (anchorDir == null) {
            throw new IllegalStateException(
                    "Could not find a \"features\" ancestor directory at least two levels above this file.");
        }

        Document document = editor.getDocument();
        String fullText = document.getText();

        int firstLineStart = document.getLineStartOffset(document.getLineNumber(firstStep.getTextOffset()));
        int lastStepEnd = lastStep.getTextRange().getEndOffset();
        String indent = fullText.substring(firstLineStart, firstStep.getTextOffset());
        String extractedText = fullText.substring(firstLineStart, lastStepEnd);

        String newFileContent = "Feature: Process\n\n@Pumpkin\nScenario: " + processName + "\n" + extractedText + "\n";

        // Stripped exactly like PumpkinProcessInsertHandler.buildProcessText strips them from a
        // completion-generated invocation: the user is free to type {variable} placeholders into
        // the process name, but the auto-generated call site can't know a real value for them, so
        // it's left for the user to fill in manually, same as every other Process invocation.
        String invocationName = VARIABLE_PATTERN.matcher(processName).replaceAll("");
        String replacement = indent + keywordOf(firstStep) + " Process: " + invocationName + " without data";

        WriteCommandAction.runWriteCommandAction(project, "Extract Process", null, () -> {
            VirtualFile processesDir;
            try {
                processesDir = VfsUtil.createDirectoryIfMissing(anchorDir, "_processes");
            } catch (IOException e) {
                throw new RuntimeException("Could not create _processes directory: " + e.getMessage(), e);
            }
            if (processesDir == null) {
                throw new IllegalStateException("Could not create or find the _processes directory.");
            }

            String fileName = NameUtils.toKebabCase(processName) + ".feature";
            if (processesDir.findChild(fileName) != null) {
                throw new IllegalStateException("File already exists: " + fileName);
            }

            try {
                VirtualFile newFile = processesDir.createChildData(ExtractProcessGenerator.class, fileName);
                VfsUtil.saveText(newFile, newFileContent);

                document.replaceString(firstLineStart, lastStepEnd, replacement);

                FileEditorManager.getInstance(project).openFile(newFile, true);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create process feature file: " + e.getMessage(), e);
            }
        }, firstStep.getContainingFile());
    }

    /** The Gherkin keyword the step was actually typed with, or "Given" if it can't be determined. */
    private static @NotNull String keywordOf(@NotNull GherkinStep step) {
        Matcher m = KEYWORD_PATTERN.matcher(step.getText());
        return m.find() ? m.group(1) : "Given";
    }

}
