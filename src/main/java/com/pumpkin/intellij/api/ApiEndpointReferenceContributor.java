package com.pumpkin.intellij.api;

import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;

/**
 * Registers {@link ApiEndpointReference} on Gherkin steps that match the
 * generic API send pattern so that Ctrl/Cmd+Click and Go to Declaration
 * navigate to the matching enum constant in the API proxy class.
 */
public class ApiEndpointReferenceContributor extends PsiReferenceContributor {

    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement(GherkinStep.class),
                new PsiReferenceProvider() {
                    @Override
                    public PsiReference @NotNull [] getReferencesByElement(
                            @NotNull PsiElement element,
                            @NotNull ProcessingContext context) {

                        if (!(element instanceof GherkinStep step)) return PsiReference.EMPTY_ARRAY;

                        ApiStepPattern.ParsedApiStep parsed = ApiStepPattern.parse(step);
                        if (parsed == null) return PsiReference.EMPTY_ARRAY;

                        return new PsiReference[]{
                                new ApiEndpointReference(
                                        step,
                                        parsed.getDefinitionRangeInStep(),
                                        parsed.getApiRequestDefinition(),
                                        parsed.getApiNotation()
                                )
                        };
                    }
                }
        );
    }
}
