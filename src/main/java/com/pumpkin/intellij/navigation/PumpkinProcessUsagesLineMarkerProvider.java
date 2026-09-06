package com.pumpkin.intellij.navigation;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.codeInsight.navigation.PsiTargetNavigator;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.DumbService;
import com.intellij.platform.backend.presentation.TargetPresentation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.pumpkin.intellij.util.GherkinPsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinScenario;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;
import org.jetbrains.plugins.cucumber.psi.GherkinTag;

import java.util.List;

/**
 * Adds a clickable gutter icon on a {@code @pumpkin} Scenario's own {@code @pumpkin} tag that
 * lists every {@code Process: ...} usage with a proper "file:line" popup.
 *
 * <p>This exists alongside {@link PumpkinProcessUsagesGotoDeclarationHandler} (Ctrl/Cmd+click)
 * rather than replacing it: that handler has to rely on the platform's default multi-target
 * chooser popup, whose presentation is computed by calling the target element's own {@code
 * getPresentation()} — and Gherkin's PSI implementation doesn't delegate that to the {@code
 * itemPresentationProvider} extension point meant for exactly this kind of customization
 * (confirmed by testing: registering a provider for it had no effect), so there's no supported
 * way to add a location string to that specific popup.
 *
 * <p>A gutter icon's {@code GutterIconNavigationHandler}, by contrast, only ever fires on an
 * actual click — never on hover, unlike {@code GotoDeclarationHandler} — so it's safe to build
 * and show a fully custom popup directly here (via {@link PsiTargetNavigator}'s own {@code
 * presentationProvider}), bypassing Gherkin's presentation entirely.
 */
public class PumpkinProcessUsagesLineMarkerProvider implements LineMarkerProvider {

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        if (!(element instanceof GherkinTag tag)) return null;
        if (!"@pumpkin".equals(tag.getName())) return null;
        if (DumbService.isDumb(element.getProject())) return null;

        GherkinScenario scenario = PsiTreeUtil.getParentOfType(tag, GherkinScenario.class);
        if (scenario == null || !GherkinPsiUtil.isPumpkinScenario(scenario)) return null;

        List<GherkinStep> usages = PumpkinProcessUsagesFinder.findUsages(element.getProject(), scenario);
        if (usages.isEmpty()) return null;

        String tooltip = usages.size() == 1 ? "1 Process usage" : usages.size() + " Process usages";

        return new LineMarkerInfo<>(
                tag,
                tag.getTextRange(),
                AllIcons.Gutter.OverridenMethod,
                psi -> tooltip,
                (event, elt) -> new PsiTargetNavigator<>(usages)
                        .presentationProvider(step -> TargetPresentation
                                .builder(step.getText().strip())
                                .containerText(PumpkinGherkinStepItemPresentationProvider.locationOf(step))
                                .presentation())
                        .navigate(event, "Process Usages", element.getProject()),
                GutterIconRenderer.Alignment.LEFT,
                () -> tooltip
        );
    }
}
