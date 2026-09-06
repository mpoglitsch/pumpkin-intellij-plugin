package com.pumpkin.intellij.navigation;

import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.presentation.java.SymbolPresentationUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Builds a "file:line" location string for a Gherkin PSI element, used by
 * {@link PumpkinProcessUsagesLineMarkerProvider}'s custom popup and by
 * {@link PumpkinNavigationTarget}'s {@code getLocationString()}.
 *
 * <p>This used to also be an {@code ItemPresentationProvider}, registering a location string for
 * {@code GherkinStep}/{@code GherkinScenario} in the platform's default multi-target navigation
 * popup. That didn't work: confirmed by testing that Gherkin's own {@code getPresentation()}
 * override doesn't delegate to {@code ItemPresentationProviders}, so the extension point was
 * never actually consulted. Only this plain helper survives that attempt.
 */
final class PumpkinGherkinStepItemPresentationProvider {

    private PumpkinGherkinStepItemPresentationProvider() {}

    static @Nullable String locationOf(@NotNull PsiElement element) {
        PsiFile file = element.getContainingFile();
        if (file == null) return null;

        String path = SymbolPresentationUtil.getFilePathPresentation(file);
        Document doc = PsiDocumentManager.getInstance(element.getProject()).getDocument(file);
        int line = doc != null ? doc.getLineNumber(element.getTextOffset()) + 1 : -1;
        return line > 0 ? path + ":" + line : path;
    }
}
