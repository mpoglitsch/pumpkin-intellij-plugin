package com.pumpkin.intellij.completion;

import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.pumpkin.intellij.model.PumpkinProcessDefinition;
import com.intellij.ui.SimpleListCellRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Handles completion insertion for a Pumpkin Process.
 *
 * <p>After selection:
 * <ol>
 *   <li>Replaces the typed text (from the start of the process name) with the resolved
 *       process invocation – variable placeholders are stripped, leaving the user to type values.</li>
 *   <li>Appends a Gherkin data-table with one row per required parameter.</li>
 *   <li>Positions the caret after the first variable placeholder so the user can type immediately.</li>
 *   <li>If the chosen name has more than one {@code @ProcessContext(...)} variant, follows up with
 *       a "Choose Context" popup; picking a non-default one appends the {@code | ContextName}
 *       suffix right after the with/without-data marker (see {@code GherkinPsiUtil}'s doc on that
 *       suffix). One variant (or "Default") means no suffix is added - identical to today.</li>
 * </ol>
 */
public class PumpkinProcessInsertHandler implements InsertHandler<LookupElement> {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{[a-zA-Z_][a-zA-Z0-9_]*}");

    @Override
    public void handleInsert(@NotNull InsertionContext context, @NotNull LookupElement item) {
        Object obj = item.getObject();
        if (!(obj instanceof List<?> rawVariants) || rawVariants.isEmpty()) return;
        @SuppressWarnings("unchecked")
        List<PumpkinProcessDefinition> variants = (List<PumpkinProcessDefinition>) rawVariants;
        PumpkinProcessDefinition def = variants.get(0);

        Document document = context.getDocument();
        Editor editor = context.getEditor();

        // Find the GherkinStep at the insertion point.
        context.commitDocument();
        PsiElement pos = context.getFile().findElementAt(context.getStartOffset());
        GherkinStep step = PsiTreeUtil.getParentOfType(pos, GherkinStep.class, false);

        // Compute the range to replace: from the start of the process name to the tail offset.
        int replaceStart = locateProcessNameStart(step, document, context.getStartOffset());
        int replaceEnd   = context.getTailOffset();

        // Build the text to insert (invocation line + optional table).
        String processText = buildProcessText(def);
        boolean hasTable    = !def.getRequiredParameters().isEmpty();
        String dataSuffix   = hasTable ? " with data" : " without data";
        String tableText    = buildTableText(def, step);
        String insertText   = processText + dataSuffix + tableText;

        document.replaceString(replaceStart, replaceEnd, insertText);

        // Position caret: after the process name, ready to type the first variable value.
        int caretOffset = replaceStart + processText.length();
        editor.getCaretModel().moveToOffset(caretOffset);

        if (variants.size() > 1) {
            // Anchored to the with/without-data marker's own (already-inserted) span, not a raw
            // offset at the caret's position above: the user may well start typing a variable
            // value right at that position before ever acting on the popup below, and a
            // RangeMarker over real, stable text correctly shifts to stay attached to that text
            // when something is inserted before it - a zero-width marker at the caret's own
            // position would have no well-defined side to stick to in that same situation.
            int dataSuffixStart = replaceStart + processText.length();
            RangeMarker dataSuffixMarker = document.createRangeMarker(dataSuffixStart, dataSuffixStart + dataSuffix.length());
            offerContextChoice(context.getProject(), editor, variants, dataSuffixMarker);
        }
    }

    // -------------------------------------------------------------------------

    /**
     * {@code toString()} is overridden (not left to the default record-generated one) for the
     * same reason the {@code api:}/{@code auth:} shortcut popups' own choice records do - see
     * {@code ApiStepPopups.ApiChoice}'s doc comment: Swing's {@code JList} "type ahead to select"
     * calls {@code toString()} on list model items outside any read action. This record's field
     * is a plain String, so there's no PSI-read risk here, but the default record toString() would
     * still produce an unhelpful "ContextChoice[...]" string for that raw Swing lookup.
     */
    private record ContextChoice(@Nullable String contextName, @NotNull String displayText) {
        @Override
        public String toString() { return displayText; }
    }

    /**
     * Shows a "Choose Context" popup listing every context name in {@code variants} (plus
     * "Default" for an untagged one, if present). Picking "Default" inserts nothing further;
     * picking a real name inserts {@code | <Name>} right before the with/without-data marker
     * {@code dataSuffixMarker} tracks, moving it earlier in the text as needed if the user typed
     * anything before it in the meantime (see the caller's own comment on why this is anchored to
     * that marker rather than a raw offset).
     */
    private static void offerContextChoice(@Nullable Project project, @NotNull Editor editor,
                                           @NotNull List<PumpkinProcessDefinition> variants,
                                           @NotNull RangeMarker dataSuffixMarker) {
        if (project == null) return;

        List<ContextChoice> choices = new ArrayList<>();
        for (PumpkinProcessDefinition variant : variants) {
            String contextName = variant.getContextName();
            String displayText = contextName != null ? contextName : "Default";
            if (choices.stream().noneMatch(c -> displayText.equals(c.displayText()))) {
                choices.add(new ContextChoice(contextName, displayText));
            }
        }
        choices.sort(Comparator.comparing(ContextChoice::displayText));

        JBPopupFactory.getInstance()
                .createPopupChooserBuilder(choices)
                .setTitle("Choose Context")
                .setRenderer(SimpleListCellRenderer.create("", ContextChoice::displayText))
                .setNamerForFiltering(ContextChoice::displayText)
                .setItemChosenCallback(choice -> {
                    if (choice.contextName() == null || !dataSuffixMarker.isValid()) return;
                    WriteCommandAction.runWriteCommandAction(project, "Insert Process Context", null, () ->
                            dataSuffixMarker.getDocument()
                                    .insertString(dataSuffixMarker.getStartOffset(), " | " + choice.contextName()));
                })
                .createPopup()
                .showInBestPositionFor(editor);
    }

    /**
     * Locate the document offset where the process invocation text (after "Process: ") starts.
     * Falls back to {@code fallback} if the step cannot be found.
     */
    private int locateProcessNameStart(@Nullable GherkinStep step,
                                       @NotNull Document document,
                                       int fallback) {
        if (step == null) return fallback;
        String fullText = step.getText();
        int idx = fullText.indexOf("Process: ");
        if (idx < 0) return fallback;
        return step.getTextOffset() + idx + "Process: ".length();
    }

    /**
     * Strips all {@code {variable}} placeholders from the process name, producing
     * the static text the user sees in the invocation line (with trailing space before
     * each stripped placeholder preserved for readability).
     */
    @NotNull
    private String buildProcessText(@NotNull PumpkinProcessDefinition def) {
        // Replace each {variable} with empty string so the user can type the value.
        return VARIABLE_PATTERN.matcher(def.getProcessName()).replaceAll("");
    }

    /**
     * Builds the data-table rows for required parameters, indented to match the step.
     * Returns an empty string when there are no required parameters.
     */
    @NotNull
    private String buildTableText(@NotNull PumpkinProcessDefinition def,
                                  @Nullable GherkinStep step) {
        List<String> required = def.getRequiredParameters();
        if (required.isEmpty()) return "";

        String indent = computeIndent(step);

        // Align key column width.
        int maxKeyLen = required.stream().mapToInt(String::length).max().orElse(0);

        StringBuilder sb = new StringBuilder();
        for (String param : required) {
            int pad = maxKeyLen - param.length();
            sb.append("\n").append(indent).append("| ").append(param)
              .append(" ".repeat(pad)).append(" |  |");
        }
        return sb.toString();
    }

    /** Returns the whitespace prefix of the step's line, used to indent table rows. */
    @NotNull
    private String computeIndent(GherkinStep step) {
        if (step == null) return "    ";
        try {
            String text = step.getContainingFile().getText();
            int lineStart = text.lastIndexOf('\n', step.getTextOffset()) + 1;
            int contentStart = lineStart;
            while (contentStart < text.length() && text.charAt(contentStart) == ' ') contentStart++;
            return text.substring(lineStart, contentStart) + "  "; // extra indent for table
        } catch (Exception ignored) {
            return "    ";
        }
    }
}
