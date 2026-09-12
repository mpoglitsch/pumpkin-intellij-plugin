package com.pumpkin.intellij.context;

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.pumpkin.intellij.endpoint.SwitchMethodEditor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pressing Enter right after typing the step {@code store these values in context} or
 * {@code store this value in context} (optionally after a step keyword and/or a leading
 * {@code I}, e.g. {@code And I store these values in context} or {@code And store this value in
 * context}) inserts a two-column, two-row data table below it - a header row ({@code
 * fieldName}/{@code value}) plus one blank row for the user to fill in - with the caret placed in
 * the blank row's first column, ready to type. Similar in spirit to {@code
 * WorkflowShortcutEnterHandler}'s {@code wf:<id>} shortcut, but triggered by a fixed step phrase
 * rather than a typed marker syntax.
 *
 * <p>Skips generation if the very next line already looks like the {@code fieldName}/{@code
 * value} header row - e.g. the user pressed Enter again on this same step line after the table
 * was already generated once - so it never duplicates the table underneath itself.
 */
public class StoreContextValuesEnterHandler implements EnterHandlerDelegate {

    private static final Pattern TRIGGER = Pattern.compile(
            "(?:(?:Given|When|Then|And|But|\\*)\\s+)?(?:I\\s+)?store (?:these values|this value) in context",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern EXISTING_HEADER_ROW = Pattern.compile(
            "\\|\\s*fieldName\\s*\\|\\s*value\\s*\\|", Pattern.CASE_INSENSITIVE);

    @Override
    public Result preprocessEnter(@NotNull PsiFile file, @NotNull Editor editor,
            @NotNull Ref<Integer> caretOffsetRef, @NotNull Ref<Integer> caretAdvance,
            @NotNull DataContext dataContext, @Nullable EditorActionHandler originalHandler) {

        if (!(file instanceof GherkinFile)) return Result.Continue;

        Document document = editor.getDocument();
        int caretOffset = editor.getCaretModel().getOffset();
        int lineNumber = document.getLineNumber(caretOffset);
        int lineStart = document.getLineStartOffset(lineNumber);
        String linePrefix = document.getText(new TextRange(lineStart, caretOffset)).strip();

        if (!TRIGGER.matcher(linePrefix).matches()) return Result.Continue;
        if (alreadyHasHeaderRowBelow(document, lineNumber)) return Result.Continue;

        String indent = SwitchMethodEditor.indentOf(document.getText(), lineStart);
        String header = indent + "  | fieldName | value |";
        // Blank cells padded to the same width as their header column, matching the established
        // table style elsewhere in this plugin (see ApiStepGenerator.buildTable).
        String blankRow = indent + "  |           |       |";
        String insertion = "\n" + header + "\n" + blankRow;

        document.insertString(caretOffset, insertion);

        // Caret lands in the blank row's first column, right after its leading "| ".
        int blankRowStart = caretOffset + 1 + header.length() + 1;
        int firstColumnOffset = blankRowStart + indent.length() + "  | ".length();
        editor.getCaretModel().moveToOffset(firstColumnOffset);

        return Result.Stop;
    }

    /** Whether the line right after {@code stepLineNumber} is already the generated header row. */
    private static boolean alreadyHasHeaderRowBelow(@NotNull Document document, int stepLineNumber) {
        int nextLineNumber = stepLineNumber + 1;
        if (nextLineNumber >= document.getLineCount()) return false;

        String nextLineText = document.getText(TextRange.create(
                document.getLineStartOffset(nextLineNumber), document.getLineEndOffset(nextLineNumber))).strip();
        return EXISTING_HEADER_ROW.matcher(nextLineText).matches();
    }

    /**
     * Explicitly implemented rather than left to {@code EnterHandlerDelegate}'s own default - see
     * {@code WorkflowShortcutEnterHandler.postProcessEnter}'s doc comment for the Windows
     * {@code AbstractMethodError} this avoids. This handler has no post-processing to do - the
     * whole generation happens in {@link #preprocessEnter} - so this is a plain no-op.
     */
    @Override
    public Result postProcessEnter(@NotNull PsiFile file, @NotNull Editor editor, @NotNull DataContext dataContext) {
        return Result.Continue;
    }
}
