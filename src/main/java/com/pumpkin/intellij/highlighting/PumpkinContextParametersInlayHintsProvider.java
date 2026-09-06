package com.pumpkin.intellij.highlighting;

import com.intellij.codeInsight.hints.*;
import com.intellij.codeInsight.hints.presentation.InlayPresentation;
import com.intellij.codeInsight.hints.presentation.PresentationFactory;
import com.intellij.codeInsight.hints.presentation.StaticDelegatePresentation;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.pumpkin.intellij.model.PumpkinProcessDefinition;
import com.pumpkin.intellij.repository.PumpkinProcessService;
import com.pumpkin.intellij.util.GherkinPsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;
import org.jetbrains.plugins.cucumber.psi.GherkinScenario;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Shows a small tag above each {@code Process: ...} step listing the context parameters the
 * matched Process sets, declared via a {@code @setsContextParameters(name, other)} tag on its
 * {@code @pumpkin} Scenario. The parameter names themselves are bold.
 *
 * <pre>
 *   [🔧 Sets Context Parameters: <b>orderId, customerId</b>]
 *   When I execute * Process: Create order
 * </pre>
 */
public class PumpkinContextParametersInlayHintsProvider implements InlayHintsProvider<NoSettings> {

    private static final SettingsKey<NoSettings> KEY =
            new SettingsKey<>("pumpkin.process.context.parameters.hints");

    @NotNull
    @Override
    public SettingsKey<NoSettings> getKey() { return KEY; }

    @NotNull
    @Override
    public String getName() { return "Pumpkin Process context parameters"; }

    @NotNull
    @Override
    public String getPreviewText() {
        return "* Process: Create customer {name}";
    }

    @NotNull
    @Override
    public ImmediateConfigurable createConfigurable(@NotNull NoSettings settings) {
        return listener -> new JPanel();
    }

    @NotNull
    @Override
    public NoSettings createSettings() { return new NoSettings(); }

    @Override
    public boolean isVisibleInSettings() { return true; }

    @Nullable
    @Override
    public InlayHintsCollector getCollectorFor(@NotNull PsiFile file,
                                               @NotNull Editor editor,
                                               @NotNull NoSettings settings,
                                               @NotNull InlayHintsSink sink) {
        if (!(file instanceof GherkinFile)) return null;
        return new FactoryInlayHintsCollector(editor) {
            @Override
            public boolean collect(@NotNull PsiElement element,
                                   @NotNull Editor editor,
                                   @NotNull InlayHintsSink sink) {
                if (!(element instanceof GherkinStep step)) return true;
                if (DumbService.isDumb(step.getProject())) return true;
                if (!GherkinPsiUtil.isProcessStep(step)) return true;

                String invocationText = GherkinPsiUtil.getProcessInvocationText(step);
                if (invocationText == null || invocationText.isBlank()) return true;

                List<PumpkinProcessDefinition> matches =
                        PumpkinProcessService.getInstance(step.getProject())
                                .findMatchingProcesses(invocationText);
                if (matches.size() != 1) return true; // ambiguous or no match — nothing to show

                GherkinScenario scenario = matches.get(0).getScenarioPsiElement();
                if (scenario == null) return true;

                List<String> params = GherkinPsiUtil.parseSetsContextParameters(scenario);
                if (params.isEmpty()) return true;

                // Derive indentation from the step's position in the document.
                int stepOffset = step.getTextOffset();
                int lineStart = editor.getDocument()
                        .getLineStartOffset(editor.getDocument().getLineNumber(stepOffset));
                String indent = editor.getDocument().getText(new TextRange(lineStart, stepOffset));

                PresentationFactory factory = getFactory();
                InlayPresentation tag = factory.roundWithBackground(
                        factory.seq(
                                factory.smallScaledIcon(AllIcons.Nodes.Parameter),
                                factory.smallText(" Sets Context Parameters: "),
                                bold(factory.smallText(String.join(", ", params)))
                        )
                );

                sink.addBlockElement(stepOffset, false, true, 0,
                        factory.seq(factory.text(indent), tag));
                return true;
            }
        };
    }

    /**
     * Wraps a presentation so its text is painted bold, regardless of which
     * {@link TextAttributes} the surrounding presentation chain supplies.
     */
    @NotNull
    private static InlayPresentation bold(@NotNull InlayPresentation base) {
        return new StaticDelegatePresentation(base) {
            @Override
            public void paint(@NotNull Graphics2D g, @NotNull TextAttributes attributes) {
                TextAttributes boldAttributes = attributes.clone();
                boldAttributes.setFontType(Font.BOLD);
                super.paint(g, boldAttributes);
            }
        };
    }
}
