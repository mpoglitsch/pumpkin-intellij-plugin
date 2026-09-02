package com.pumpkin.intellij.completion;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.completion.impl.CamelHumpMatcher;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import com.pumpkin.intellij.model.PumpkinProcessDefinition;
import com.pumpkin.intellij.repository.PumpkinProcessService;
import com.pumpkin.intellij.util.GherkinPsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;

import java.util.List;

/**
 * Provides completion for Process invocations inside Gherkin feature files.
 *
 * <p>When the user types {@code * Process: Cr}, this contributor suggests all known
 * {@code @pumpkin} processes whose name starts with {@code Cr} (case-insensitive).
 *
 * <p>Normal Gherkin step completion is not affected because this contributor only activates
 * when the step text starts with {@code "Process: "}.
 */
public class PumpkinProcessCompletionContributor extends CompletionContributor {

    private static final String PROCESS_PREFIX = "Process: ";

    public PumpkinProcessCompletionContributor() {
        extend(CompletionType.BASIC,
               PlatformPatterns.psiElement().inside(GherkinStep.class),
               new ProcessCompletionProvider());
    }

    // -------------------------------------------------------------------------

    private static final class ProcessCompletionProvider
            extends CompletionProvider<CompletionParameters> {

        @Override
        protected void addCompletions(@NotNull CompletionParameters parameters,
                                      @NotNull ProcessingContext context,
                                      @NotNull CompletionResultSet result) {

            PsiElement position = parameters.getPosition();
            GherkinStep step = PsiTreeUtil.getParentOfType(position, GherkinStep.class, false);
            if (step == null) return;

            // Extract the step name, removing the dummy identifier IntelliJ injects.
            String stepName = step.getName();
            if (stepName == null) return;
            stepName = stepName.replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "").trim();

            if (!stepName.startsWith(PROCESS_PREFIX)) return;

            String typedProcessText = stepName.substring(PROCESS_PREFIX.length());

            PumpkinProcessService service =
                    PumpkinProcessService.getInstance(position.getProject());
            List<PumpkinProcessDefinition> processes = service.getProcesses();

            // Build a case-insensitive prefix matcher on what was typed after "Process: ".
            CompletionResultSet prefixed = result.withPrefixMatcher(
                    new CamelHumpMatcher(typedProcessText, false));

            PumpkinProcessInsertHandler insertHandler = new PumpkinProcessInsertHandler();

            for (PumpkinProcessDefinition def : processes) {
                String processName = def.getProcessName();
                String typeText = def.getFeatureFileName() + ":" + def.getScenarioLine();

                prefixed.addElement(
                        LookupElementBuilder.create(def, processName)
                                .withPresentableText(processName)
                                .withTypeText(typeText)
                                .withIcon(AllIcons.Nodes.Method)
                                .withInsertHandler(insertHandler)
                                .bold()
                );
            }

            // Suppress normal Gherkin step completions so the list isn't cluttered.
            result.stopHere();
        }
    }
}
