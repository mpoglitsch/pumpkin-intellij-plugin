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
 * Intercepts "Go to Declaration" on any part of a generic API send step and navigates
 * to the matching endpoint enum constant in the proxy class.
 *
 * <p>Registered with {@code order="first"} so it runs before Cucumber's handler.
 * Returns {@code null} for steps that do not match the API send pattern so Cucumber's
 * handler still works normally for regular steps.
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

        // Step matches the API pattern — try to navigate to the endpoint enum constant.
        // Return null (not EMPTY_ARRAY) when unresolved so Cucumber can still navigate
        // to the step-definition method as a fallback.
        PsiEnumConstant target = ApiEndpointResolver.resolve(
                sourceElement.getProject(),
                parsed.getApiNotation(),
                parsed.getApiRequestDefinition());

        if (target == null || !target.isValid()) return null;
        return new PsiElement[]{target};
    }
}
