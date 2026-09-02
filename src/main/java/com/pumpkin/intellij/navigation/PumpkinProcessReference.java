package com.pumpkin.intellij.navigation;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiPolyVariantReferenceBase;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.PsiElementResolveResult;
import com.pumpkin.intellij.model.PumpkinProcessDefinition;
import com.pumpkin.intellij.repository.PumpkinProcessService;
import com.pumpkin.intellij.util.GherkinPsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.cucumber.psi.GherkinScenario;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;

import java.util.List;

/**
 * PSI reference on a Process step that resolves to the target {@link GherkinScenario}.
 *
 * <p>Using {@link PsiPolyVariantReferenceBase} means IntelliJ automatically handles
 * the "multiple targets" case (ambiguous processes) by showing a navigation popup,
 * while Option/Alt-click navigation works out of the box via standard IDE plumbing.
 */
public class PumpkinProcessReference extends PsiPolyVariantReferenceBase<GherkinStep> {

    public PumpkinProcessReference(@NotNull GherkinStep step, @NotNull TextRange rangeInElement) {
        super(step, rangeInElement, true /* soft */);
    }

    @Override
    public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
        String invocationText = GherkinPsiUtil.getProcessInvocationText(myElement);
        if (invocationText == null) return ResolveResult.EMPTY_ARRAY;

        List<PumpkinProcessDefinition> matches =
                PumpkinProcessService.getInstance(myElement.getProject())
                        .findMatchingProcesses(invocationText);

        return matches.stream()
                .map(def -> {
                    GherkinScenario scenario = def.getScenarioPsiElement();
                    if (scenario == null || !scenario.isValid()) return null;
                    return new PsiElementResolveResult(scenario, true);
                })
                .filter(r -> r != null)
                .toArray(ResolveResult[]::new);
    }

    @Override
    public Object @NotNull [] getVariants() {
        return EMPTY_ARRAY;
    }
}
