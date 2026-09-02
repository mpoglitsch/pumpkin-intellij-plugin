package com.pumpkin.intellij.api;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;

/**
 * PSI reference on the {@code {apiRequestDefinition}} fragment of a generic API send step.
 * Navigates to the matching enum constant in the API proxy's endpoint enum.
 */
public class ApiEndpointReference extends PsiPolyVariantReferenceBase<GherkinStep> {

    private final @NotNull String apiRequestDefinition;
    private final @NotNull String apiNotation;

    public ApiEndpointReference(@NotNull GherkinStep step,
                                @NotNull TextRange rangeInElement,
                                @NotNull String apiRequestDefinition,
                                @NotNull String apiNotation) {
        super(step, rangeInElement, true /* soft – no error if unresolved */);
        this.apiRequestDefinition = apiRequestDefinition;
        this.apiNotation = apiNotation;
    }

    @Override
    public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
        if (DumbService.isDumb(myElement.getProject())) return ResolveResult.EMPTY_ARRAY;
        PsiEnumConstant target = ApiEndpointResolver.resolve(
                myElement.getProject(), apiNotation, apiRequestDefinition);
        if (target == null) return ResolveResult.EMPTY_ARRAY;
        return new ResolveResult[]{new PsiElementResolveResult(target, true)};
    }

    @Override
    public Object @NotNull [] getVariants() {
        return EMPTY_ARRAY;
    }
}
