package com.pumpkin.intellij.api.postprocessor;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiEnumConstant;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** An {@link AddPostProcessorDialog} submission, consumed by {@code PostProcessorCodeGenerator}. */
public record NewPostProcessorSpec(@NotNull PsiClass proxyClass, @NotNull PsiEnumConstant endpoint,
                                    @NotNull String apiNotation, @NotNull List<FieldMapping> mappings) {
}
