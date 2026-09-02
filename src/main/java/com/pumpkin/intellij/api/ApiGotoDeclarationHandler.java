package com.pumpkin.intellij.api;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiEnumConstant;
import com.pumpkin.intellij.util.GherkinPsiUtil;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;

/**
 * Intercepts "Go to Declaration" on the {@code {apiRequestDefinition}} fragment of generic
 * API send steps and navigates to the matching endpoint enum constant in the proxy class.
 *
 * <p>Runs alongside {@link com.pumpkin.intellij.navigation.PumpkinGotoDeclarationHandler};
 * returns {@code null} for steps that are not API send steps.
 */
public class ApiGotoDeclarationHandler implements GotoDeclarationHandler {

    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(
            @Nullable PsiElement sourceElement,
            int offset,
            Editor editor) {

        if (sourceElement == null) return null;
        if (DumbService.isDumb(sourceElement.getProject())) return null;

        GherkinStep step = GherkinPsiUtil.findEnclosingStep(sourceElement);
        if (step == null) return null;

        ApiStepPattern.ParsedApiStep parsed = ApiStepPattern.parse(step);
        if (parsed == null) return null;

        // Only activate when the caret is on the apiRequestDefinition part of the step.
        int relativeOffset = offset - step.getTextOffset();
        if (!parsed.getDefinitionRangeInStep().containsOffset(relativeOffset)) return null;

        PsiEnumConstant target = ApiEndpointResolver.resolve(
                sourceElement.getProject(),
                parsed.getApiNotation(),
                parsed.getApiRequestDefinition());

        if (target == null || !target.isValid()) return PsiElement.EMPTY_ARRAY;
        return new PsiElement[]{target};
    }
}
