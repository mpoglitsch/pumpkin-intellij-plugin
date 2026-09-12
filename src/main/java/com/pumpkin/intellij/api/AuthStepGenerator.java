package com.pumpkin.intellij.api;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.pumpkin.intellij.endpoint.SwitchMethodEditor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Builds and inserts the final {@code I use authentication <AuthProviderName> for <API> in this
 * scenario} step once an API and an auth provider have been chosen via {@link AuthStepPopups}.
 * No data table - unlike {@code ApiStepGenerator}'s endpoint step, this step takes no parameters.
 */
final class AuthStepGenerator {

    private AuthStepGenerator() {}

    /**
     * @param lineStart   start offset of the line containing the trigger (before any indent)
     * @param replaceEnd  end offset of the text to replace - right after the final {@code :} of
     *                    {@code auth:<API>:} (with an optional leading keyword and indent before
     *                    it, both replaced too - the generated line supplies its own keyword)
     * @param keyword     the Gherkin keyword the user had already typed before {@code auth:}
     *                    (e.g. {@code "And"}), or {@code null} if none - defaults to {@code And}
     */
    static void generate(@NotNull Project project, @NotNull Document document, int lineStart, int replaceEnd,
                         @Nullable String keyword, @NotNull String apiNotation, @NotNull String providerName) {

        String line = (keyword != null ? keyword : "And") + " I use authentication " + providerName
                + " for " + apiNotation + " in this scenario";

        String indent = SwitchMethodEditor.indentOf(document.getText(), lineStart);
        int replaceStart = lineStart + indent.length();
        WriteCommandAction.runWriteCommandAction(project, "Generate Authentication Step", null, () ->
                document.replaceString(replaceStart, replaceEnd, line));
    }
}
