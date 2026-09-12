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
 * "{@code auth:}" typing shortcut: typing {@code auth:} on its own (optionally after a step
 * keyword, e.g. {@code And auth:}) opens a filterable popup of every known {@code ApiNotation}
 * proxy - see {@link AuthStepPopups}. Picking one inserts its name plus a second {@code :}, which
 * immediately opens a second filterable popup of every known authentication provider; picking one
 * generates the {@code I use authentication <name> for <API> in this scenario} step (see
 * {@link AuthStepGenerator}). Mirrors {@link ApiStepTriggerHandler} exactly, kept as its own
 * class/registration so the existing {@code api:} shortcut is never touched by this one.
 *
 * <p>Typing the full {@code auth:<API_NAME>:} by hand (without ever using the first popup) opens
 * the provider popup directly too, once the typed name resolves to a known API.
 */
public class AuthStepTriggerHandler extends TypedHandlerDelegate {

    private static final Pattern AUTH_TRIGGER = Pattern.compile(
            "(?:(Given|When|Then|And|But|\\*)\\s+)?auth:", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROVIDER_TRIGGER = Pattern.compile(
            "(?:(Given|When|Then|And|But|\\*)\\s+)?auth:([A-Za-z_][A-Za-z0-9_]*):", Pattern.CASE_INSENSITIVE);

    @Override
    public @NotNull Result charTyped(char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        if (c != ':' || !(file instanceof GherkinFile)) return Result.CONTINUE;

        Document document = editor.getDocument();
        int caretOffset = editor.getCaretModel().getOffset();
        int lineNumber = document.getLineNumber(caretOffset);
        int lineStart = document.getLineStartOffset(lineNumber);
        String linePrefix = document.getText(new TextRange(lineStart, caretOffset)).strip();

        Matcher providerMatcher = PROVIDER_TRIGGER.matcher(linePrefix);
        if (providerMatcher.matches()) {
            resolveAndOpenProviderPopup(project, editor, lineStart, caretOffset,
                    providerMatcher.group(1), providerMatcher.group(2));
            return Result.CONTINUE;
        }

        Matcher authMatcher = AUTH_TRIGGER.matcher(linePrefix);
        if (authMatcher.matches()) {
            AuthStepPopups.openApiPopup(editor, lineStart, caretOffset, authMatcher.group(1));
        }
        return Result.CONTINUE;
    }

    private void resolveAndOpenProviderPopup(@NotNull Project project, @NotNull Editor editor, int lineStart,
                                             int caretOffset, @Nullable String keyword, @NotNull String typedApiName) {
        for (PsiClass proxy : ApiEndpointResolver.findAllProxyClasses(project)) {
            String notation = ApiEndpointResolver.apiNotationOf(proxy);
            if (notation != null && notation.equalsIgnoreCase(typedApiName)) {
                AuthStepPopups.openAuthProviderPopup(editor, lineStart, caretOffset, keyword, notation);
                return;
            }
        }
        // Not a recognised API name - leave the text as-is, no popup.
    }
}
