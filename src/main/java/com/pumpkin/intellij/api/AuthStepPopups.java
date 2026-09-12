package com.pumpkin.intellij.api;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.psi.PsiClass;
import com.intellij.ui.SimpleListCellRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Two chained filterable popups backing the {@code auth:} typing shortcut (see
 * {@link AuthStepTriggerHandler}): first every known {@code ApiNotation}, then - once one is
 * picked - every known authentication provider. Selecting a provider hands off to
 * {@link AuthStepGenerator}. Mirrors {@link ApiStepPopups} (see its class doc for why these are
 * manually-triggered list popups rather than a real {@code CompletionContributor}).
 */
final class AuthStepPopups {

    private AuthStepPopups() {}

    /**
     * {@code toString()} is overridden (rather than left to the default record-generated one,
     * which would include {@code proxy}) for the same reason {@code ApiStepPopups.ApiChoice}
     * does - see its doc comment: Swing's {@code JList} "type ahead to select" calls
     * {@code toString()} on list model items outside any read action, and {@code PsiClass}'s own
     * {@code toString()} needs one.
     */
    private record ApiChoice(@NotNull PsiClass proxy, @NotNull String notation) {
        @Override
        public String toString() { return notation; }
    }

    static void openApiPopup(@NotNull Editor editor, int lineStart, int caretOffset, @Nullable String keyword) {
        Project project = editor.getProject();
        if (project == null) return;
        if (DumbService.isDumb(project)) {
            HintManager.getInstance().showInformationHint(editor, "Still indexing - try again in a moment.");
            return;
        }

        List<ApiChoice> choices = new ArrayList<>();
        for (PsiClass proxy : ApiEndpointResolver.findAllProxyClasses(project)) {
            String notation = ApiEndpointResolver.apiNotationOf(proxy);
            if (notation != null && choices.stream().noneMatch(c -> c.notation().equals(notation))) {
                choices.add(new ApiChoice(proxy, notation));
            }
        }
        choices.sort(Comparator.comparing(ApiChoice::notation));

        if (choices.isEmpty()) {
            HintManager.getInstance().showInformationHint(editor, "No API proxies found in this project.");
            return;
        }

        JBPopupFactory.getInstance()
                .createPopupChooserBuilder(choices)
                .setTitle("Choose API")
                .setRenderer(SimpleListCellRenderer.create("", ApiChoice::notation))
                .setNamerForFiltering(ApiChoice::notation)
                .setItemChosenCallback(choice -> onApiChosen(editor, lineStart, caretOffset, keyword, choice))
                .createPopup()
                .showInBestPositionFor(editor);
    }

    private static void onApiChosen(@NotNull Editor editor, int lineStart, int caretOffset,
                                    @Nullable String keyword, @NotNull ApiChoice choice) {
        Project project = editor.getProject();
        if (project == null) return;
        Document document = editor.getDocument();

        int authTokenStart = caretOffset - "auth:".length();
        String newText = "auth:" + choice.notation() + ":";

        WriteCommandAction.runWriteCommandAction(project, "Insert Auth", null, () ->
                document.replaceString(authTokenStart, caretOffset, newText));

        int newCaret = authTokenStart + newText.length();
        editor.getCaretModel().moveToOffset(newCaret);

        openAuthProviderPopup(editor, lineStart, newCaret, keyword, choice.notation());
    }

    static void openAuthProviderPopup(@NotNull Editor editor, int lineStart, int caretOffset,
                                      @Nullable String keyword, @NotNull String apiNotation) {
        Project project = editor.getProject();
        if (project == null) return;

        List<AuthProviderResolver.AuthProviderChoice> choices =
                new ArrayList<>(AuthProviderResolver.findAllAuthProviders(project));
        choices.sort(Comparator.comparing(AuthProviderResolver.AuthProviderChoice::name));

        if (choices.isEmpty()) {
            HintManager.getInstance().showInformationHint(editor, "No authentication providers found in this project.");
            return;
        }

        int replaceEnd = caretOffset;
        JBPopupFactory.getInstance()
                .createPopupChooserBuilder(choices)
                .setTitle("Choose Authentication Provider")
                .setRenderer(SimpleListCellRenderer.create("", AuthProviderResolver.AuthProviderChoice::name))
                .setNamerForFiltering(AuthProviderResolver.AuthProviderChoice::name)
                .setItemChosenCallback(choice ->
                        AuthStepGenerator.generate(project, editor.getDocument(), lineStart, replaceEnd,
                                keyword, apiNotation, choice.name()))
                .createPopup()
                .showInBestPositionFor(editor);
    }
}
