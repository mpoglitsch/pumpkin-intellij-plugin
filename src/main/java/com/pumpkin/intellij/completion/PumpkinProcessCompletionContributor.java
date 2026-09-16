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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

            // Truncated at the dummy identifier IntelliJ injects at the caret, not just stripped -
            // this must be "typed so far", not the whole step text with the marker deleted, since
            // real invocations often have content after the caret too (e.g. editing an existing
            // "Process: X with data" call site) that must NOT leak into the prefix match below.
            String stepName = step.getName();
            if (stepName == null) return;
            int dummyIndex = stepName.indexOf(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED);
            String beforeCursor = dummyIndex >= 0 ? stepName.substring(0, dummyIndex) : stepName;

            if (!beforeCursor.startsWith(PROCESS_PREFIX)) return;

            String typedProcessText = beforeCursor.substring(PROCESS_PREFIX.length());
            // Once the caret has moved past the process name itself - into a "%variable%"
            // placeholder or the "with/without data" marker - this isn't process-name-typing
            // anymore, so this contributor must back off entirely (no stopHere() below) rather
            // than swallow completion for the rest of the line, e.g.
            // ContextParameterCompletionContributor's %contextParameter% suggestions.
            if (typedProcessText.contains("%") || typedProcessText.contains(" with data")
                    || typedProcessText.contains(" without data")) {
                return;
            }

            PumpkinProcessService service =
                    PumpkinProcessService.getInstance(position.getProject());
            List<PumpkinProcessDefinition> processes = service.getProcesses();

            // Build a case-insensitive prefix matcher on what was typed after "Process: ".
            CompletionResultSet prefixed = result.withPrefixMatcher(
                    new CamelHumpMatcher(typedProcessText, false));

            PumpkinProcessInsertHandler insertHandler = new PumpkinProcessInsertHandler();

            // Grouped by name rather than one entry per definition: a process with more than one
            // @ProcessContext(...) variant would otherwise show as several visually-identical
            // completion entries. The insert handler decides whether to offer a context choice
            // once the whole group of variants is known.
            Map<String, List<PumpkinProcessDefinition>> byName = new LinkedHashMap<>();
            for (PumpkinProcessDefinition def : processes) {
                byName.computeIfAbsent(def.getProcessName(), k -> new ArrayList<>()).add(def);
            }

            for (Map.Entry<String, List<PumpkinProcessDefinition>> entry : byName.entrySet()) {
                String processName = entry.getKey();
                List<PumpkinProcessDefinition> variants = entry.getValue();
                PumpkinProcessDefinition representative = variants.get(0);
                String typeText = representative.getFeatureFileName() + ":" + representative.getScenarioLine();

                prefixed.addElement(
                        LookupElementBuilder.create(variants, processName)
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
