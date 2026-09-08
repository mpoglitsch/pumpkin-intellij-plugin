package com.pumpkin.intellij.api;

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "{@code api:}" typing shortcut: typing {@code api:} on its own (optionally after a step
 * keyword, e.g. {@code Then api:}) opens a filterable popup of every known {@code ApiNotation}
 * proxy - see {@link ApiStepPopups}. Picking one inserts its name plus a second {@code :}, which
 * immediately opens a second filterable popup of that proxy's endpoints; picking an endpoint
 * generates the whole send-request step, with a data table pre-filled with required parameter
 * names if the endpoint has any (see {@link ApiStepGenerator}).
 *
 * <p>Typing the full {@code api:<API_NAME>:} by hand (without ever using the first popup) opens
 * the endpoint popup directly too, once the typed name resolves to a known API.
 */
public class ApiStepTriggerHandler extends TypedHandlerDelegate {

    private static final Pattern API_TRIGGER = Pattern.compile(
            "(?:(Given|When|Then|And|But|\\*)\\s+)?api:", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENDPOINT_TRIGGER = Pattern.compile(
            "(?:(Given|When|Then|And|But|\\*)\\s+)?api:([A-Za-z_][A-Za-z0-9_]*):", Pattern.CASE_INSENSITIVE);

    @Override
    public @NotNull Result charTyped(char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        if (c != ':' || !(file instanceof GherkinFile)) return Result.CONTINUE;

        Document document = editor.getDocument();
        int caretOffset = editor.getCaretModel().getOffset();
        int lineNumber = document.getLineNumber(caretOffset);
        int lineStart = document.getLineStartOffset(lineNumber);
        String linePrefix = document.getText(new TextRange(lineStart, caretOffset)).strip();

        Matcher endpointMatcher = ENDPOINT_TRIGGER.matcher(linePrefix);
        if (endpointMatcher.matches()) {
            resolveAndOpenEndpointPopup(project, editor, lineStart, caretOffset,
                    endpointMatcher.group(1), endpointMatcher.group(2));
            return Result.CONTINUE;
        }

        Matcher apiMatcher = API_TRIGGER.matcher(linePrefix);
        if (apiMatcher.matches()) {
            ApiStepPopups.openApiPopup(editor, lineStart, caretOffset, apiMatcher.group(1));
        }
        return Result.CONTINUE;
    }

    private void resolveAndOpenEndpointPopup(@NotNull Project project, @NotNull Editor editor, int lineStart,
                                             int caretOffset, @Nullable String keyword, @NotNull String typedApiName) {
        for (PsiClass proxy : ApiEndpointResolver.findAllProxyClasses(project)) {
            String notation = ApiEndpointResolver.apiNotationOf(proxy);
            if (notation != null && notation.equalsIgnoreCase(typedApiName)) {
                ApiStepPopups.openEndpointPopup(editor, lineStart, caretOffset, keyword, proxy, notation);
                return;
            }
        }
        // Not a recognised API name - leave the text as-is, no popup.
    }
}
