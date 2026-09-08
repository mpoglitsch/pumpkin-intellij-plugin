package com.pumpkin.intellij.api;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiEnumConstant;
import com.pumpkin.intellij.endpoint.SwitchMethodEditor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Builds and inserts the final {@code I send a/an <endpoint> to <API> including these
 * parameters/without parameters} step - plus a data table pre-filled with required parameter
 * names as its header row, if the endpoint has any - once an API and endpoint have been chosen
 * via {@link ApiStepPopups}. Mirrors the step grammar {@code ApiStepPattern} already recognises
 * elsewhere in the plugin (navigation, inlay hints), so generated steps work with those too.
 */
final class ApiStepGenerator {

    private ApiStepGenerator() {}

    /**
     * @param lineStart   start offset of the line containing the trigger (before any indent)
     * @param replaceEnd  end offset of the text to replace - right after the final {@code :} of
     *                    {@code api:<API>:} (with an optional leading keyword and indent before it,
     *                    both replaced too - the generated line supplies its own keyword)
     * @param keyword     the Gherkin keyword the user had already typed before {@code api:}
     *                    (e.g. {@code "Then"}), or {@code null} if none - defaults to {@code Then}
     */
    static void generate(@NotNull Project project, @NotNull Document document, int lineStart, int replaceEnd,
                         @Nullable String keyword, @NotNull PsiClass proxy, @NotNull String apiNotation,
                         @NotNull PsiEnumConstant endpoint) {

        List<String> required = ApiEndpointParameters.findRequired(project, proxy, endpoint);
        String displayText = endpoint.getName().toLowerCase().replace('_', ' ');
        String article = startsWithVowelSound(displayText) ? "an" : "a";

        String phrase = required.isEmpty()
                ? "I send " + article + " " + displayText + " to " + apiNotation + " without parameters"
                : "I send " + article + " " + displayText + " to " + apiNotation + " including "
                        + (required.size() == 1 ? "this parameter" : "these parameters");

        String line = (keyword != null ? keyword : "Then") + " " + phrase;

        String indent = SwitchMethodEditor.indentOf(document.getText(), lineStart);
        String text = required.isEmpty() ? line : line + "\n" + buildTable(indent, required);

        int replaceStart = lineStart + indent.length();
        WriteCommandAction.runWriteCommandAction(project, "Generate API Step", null, () ->
                document.replaceString(replaceStart, replaceEnd, text));
    }

    /** A two-row table: parameter names as the header, one blank row for the user to fill in. */
    private static @NotNull String buildTable(@NotNull String indent, @NotNull List<String> params) {
        StringBuilder header = new StringBuilder(indent).append("  |");
        StringBuilder blankRow = new StringBuilder(indent).append("  |");
        for (String param : params) {
            header.append(" ").append(param).append(" |");
            blankRow.append(" ".repeat(param.length() + 2)).append("|");
        }
        return header + "\n" + blankRow;
    }

    private static boolean startsWithVowelSound(@NotNull String text) {
        return !text.isEmpty() && "aeiou".indexOf(Character.toLowerCase(text.charAt(0))) >= 0;
    }
}
