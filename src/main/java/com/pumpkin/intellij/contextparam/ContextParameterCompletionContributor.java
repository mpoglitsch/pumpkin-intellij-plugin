package com.pumpkin.intellij.contextparam;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.completion.PlainPrefixMatcher;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;

import java.util.List;

/**
 * Offers {@code %contextParameter%} completions: typing {@code %} inside a Gherkin step (see
 * {@link ContextParameterTypedHandler}, which forces the popup open immediately) suggests every
 * context-parameter name {@link PumpkinContextParameterService} finds already established before
 * that point - filtered live as more letters are typed, same as any other IDE autocomplete.
 */
public class ContextParameterCompletionContributor extends CompletionContributor {

    public ContextParameterCompletionContributor() {
        extend(CompletionType.BASIC,
               PlatformPatterns.psiElement().inside(GherkinStep.class),
               new ContextParameterCompletionProvider());
    }

    // -------------------------------------------------------------------------

    private static final class ContextParameterCompletionProvider
            extends CompletionProvider<CompletionParameters> {

        @Override
        protected void addCompletions(@NotNull CompletionParameters parameters,
                                      @NotNull ProcessingContext context,
                                      @NotNull CompletionResultSet result) {

            PsiElement position = parameters.getPosition();
            GherkinStep step = PsiTreeUtil.getParentOfType(position, GherkinStep.class);
            if (step == null) return;

            String prefix = openPercentPrefix(parameters.getEditor());
            if (prefix == null) return; // caret isn't inside an open %...% pair

            List<String> names = PumpkinContextParameterService.getInstance(step.getProject())
                    .collectAvailableParameters(step);
            if (names.isEmpty()) return;

            CompletionResultSet prefixed = result.withPrefixMatcher(new PlainPrefixMatcher(prefix));
            for (String name : names) {
                prefixed.addElement(
                        LookupElementBuilder.create(name)
                                .withIcon(AllIcons.Nodes.Parameter)
                                .withInsertHandler(ContextParameterCompletionProvider::closePercentPair));
            }
        }

        /** Appends a closing {@code %} after the inserted name, unless one is already right there. */
        private static void closePercentPair(@NotNull InsertionContext insertionContext, @NotNull LookupElement item) {
            Document document = insertionContext.getDocument();
            int tail = insertionContext.getTailOffset();
            if (tail >= document.getTextLength() || document.getCharsSequence().charAt(tail) != '%') {
                document.insertString(tail, "%");
            }
            insertionContext.getEditor().getCaretModel().moveToOffset(tail + 1);
        }

        /**
         * Returns the text typed since the nearest preceding {@code %} on the caret's line, or
         * {@code null} if the caret isn't inside an open {@code %...%} pair (an even number of
         * {@code %} on the line so far, including a position right after a just-closed pair).
         * {@code %} isn't an identifier character, so the dummy-identifier-based prefix derivation
         * {@code PumpkinProcessCompletionContributor}/{@code PumpkinTagCompletionContributor} use
         * doesn't apply here - this reads the real editor line text instead.
         */
        private static @Nullable String openPercentPrefix(@NotNull Editor editor) {
            Document document = editor.getDocument();
            int caretOffset = editor.getCaretModel().getOffset();
            int lineStart = document.getLineStartOffset(document.getLineNumber(caretOffset));
            String linePrefix = document.getText(TextRange.create(lineStart, caretOffset));

            int lastPercent = linePrefix.lastIndexOf('%');
            if (lastPercent < 0) return null;

            long percentCount = linePrefix.chars().filter(c -> c == '%').count();
            if (percentCount % 2 == 0) return null;

            return linePrefix.substring(lastPercent + 1);
        }
    }
}
