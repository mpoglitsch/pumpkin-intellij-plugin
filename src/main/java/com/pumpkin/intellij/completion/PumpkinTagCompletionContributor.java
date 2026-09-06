package com.pumpkin.intellij.completion;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.cucumber.psi.GherkinTag;

import java.util.List;

/**
 * Offers the Pumpkin tags as completions on a Scenario's tag line(s):
 * <ul>
 *   <li>{@code @pumpkin} / {@code @Pumpkin} – marks a Scenario as a Process definition.</li>
 *   <li>{@code @processRequired()} / {@code @requiredParameters()} – required parameter names,
 *       caret left between the parentheses.</li>
 *   <li>{@code @setsContextParameters()} / {@code @setsParameters()} – context parameters the
 *       Process sets, caret left between the parentheses.</li>
 * </ul>
 * Old and new tag names are equivalent aliases (see {@link com.pumpkin.intellij.util.GherkinPsiUtil})
 * and are both offered here so either style can be typed.
 *
 * <p>Does not suppress normal Gherkin tag completion — this just adds extra suggestions.
 */
public class PumpkinTagCompletionContributor extends CompletionContributor {

    private static final List<String> PLAIN_SUGGESTIONS = List.of("@pumpkin", "@Pumpkin");

    private static final List<String> PAREN_SUGGESTIONS = List.of(
            "@processRequired()", "@requiredParameters()",
            "@setsContextParameters()", "@setsParameters()"
    );

    public PumpkinTagCompletionContributor() {
        extend(CompletionType.BASIC,
               PlatformPatterns.psiElement().inside(GherkinTag.class),
               new PumpkinTagCompletionProvider());
    }

    // -------------------------------------------------------------------------

    private static final class PumpkinTagCompletionProvider
            extends CompletionProvider<CompletionParameters> {

        @Override
        protected void addCompletions(@NotNull CompletionParameters parameters,
                                      @NotNull ProcessingContext context,
                                      @NotNull CompletionResultSet result) {

            PsiElement position = parameters.getPosition();
            GherkinTag tag = PsiTreeUtil.getParentOfType(position, GherkinTag.class);
            if (tag == null) return;

            String typed = tag.getText().replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "").trim();
            if (!typed.startsWith("@")) return;

            CompletionResultSet prefixed = result.withPrefixMatcher(new PlainPrefixMatcher(typed));

            for (String suggestion : PLAIN_SUGGESTIONS) {
                prefixed.addElement(
                        LookupElementBuilder.create(suggestion)
                                .withPresentableText(suggestion)
                                .withTypeText("Pumpkin")
                                .withIcon(AllIcons.Nodes.Tag)
                );
            }

            for (String suggestion : PAREN_SUGGESTIONS) {
                prefixed.addElement(
                        LookupElementBuilder.create(suggestion)
                                .withPresentableText(suggestion)
                                .withTypeText("Pumpkin")
                                .withIcon(AllIcons.Nodes.Tag)
                                .withInsertHandler((insertionContext, item) ->
                                        insertionContext.getEditor().getCaretModel()
                                                .moveToOffset(insertionContext.getTailOffset() - 1))
                );
            }
        }
    }
}
