package com.pumpkin.intellij.api;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiField;
import com.intellij.ui.SimpleListCellRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Two chained filterable popups backing the {@code api:} typing shortcut (see
 * {@link ApiStepTriggerHandler}): first every known {@code ApiNotation}, then - once one is
 * picked - that proxy's own endpoints. Selecting an endpoint hands off to {@link ApiStepGenerator}.
 *
 * <p>Built as manually-triggered list popups (not a real {@code CompletionContributor}) for the
 * same reason {@code wf:<id>} isn't completion-based either (see {@code
 * WorkflowShortcutEnterHandler}'s class doc): IntelliJ's completion auto-popup only reliably
 * triggers after identifier-like characters, and a bare {@code :} doesn't qualify. Triggering the
 * popup ourselves from every keystroke (see {@link ApiStepTriggerHandler}) sidesteps that
 * entirely - filtering-as-you-type is provided by the popup's own built-in speed search rather
 * than the platform's completion machinery.
 */
final class ApiStepPopups {

    private ApiStepPopups() {}

    private record ApiChoice(@NotNull PsiClass proxy, @NotNull String notation) {}

    private record EndpointChoice(@NotNull PsiEnumConstant constant, @NotNull String displayText) {}

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

        int apiTokenStart = caretOffset - "api:".length();
        String newText = "api:" + choice.notation() + ":";

        WriteCommandAction.runWriteCommandAction(project, "Insert API", null, () ->
                document.replaceString(apiTokenStart, caretOffset, newText));

        int newCaret = apiTokenStart + newText.length();
        editor.getCaretModel().moveToOffset(newCaret);

        openEndpointPopup(editor, lineStart, newCaret, keyword, choice.proxy(), choice.notation());
    }

    static void openEndpointPopup(@NotNull Editor editor, int lineStart, int caretOffset,
                                  @Nullable String keyword, @NotNull PsiClass proxy, @NotNull String apiNotation) {
        List<EndpointChoice> choices = new ArrayList<>();
        for (PsiClass inner : proxy.getInnerClasses()) {
            if (!inner.isEnum()) continue;
            for (PsiField field : inner.getFields()) {
                if (field instanceof PsiEnumConstant ec) {
                    choices.add(new EndpointChoice(ec, ec.getName().toLowerCase().replace('_', ' ')));
                }
            }
        }
        choices.sort(Comparator.comparing(EndpointChoice::displayText));

        if (choices.isEmpty()) {
            HintManager.getInstance().showInformationHint(editor, "No endpoints found on " + proxy.getName() + ".");
            return;
        }

        int replaceEnd = caretOffset;
        JBPopupFactory.getInstance()
                .createPopupChooserBuilder(choices)
                .setTitle("Choose Endpoint")
                .setRenderer(SimpleListCellRenderer.create("", EndpointChoice::displayText))
                .setNamerForFiltering(EndpointChoice::displayText)
                .setItemChosenCallback(choice -> {
                    Project project = editor.getProject();
                    if (project == null) return;
                    ApiStepGenerator.generate(project, editor.getDocument(), lineStart, replaceEnd,
                            keyword, proxy, apiNotation, choice.constant());
                })
                .createPopup()
                .showInBestPositionFor(editor);
    }
}
