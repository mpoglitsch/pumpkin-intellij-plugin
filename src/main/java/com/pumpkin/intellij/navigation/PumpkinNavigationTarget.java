package com.pumpkin.intellij.navigation;

import com.intellij.lang.Language;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.impl.FakePsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/**
 * Wraps a PSI element purely so IntelliJ's "Choose Declaration" popup - shown when a
 * {@code GotoDeclarationHandler} returns more than one target, e.g.
 * {@link PumpkinProcessUsagesGotoDeclarationHandler} jumping from a Process definition out to all
 * of its usages - can show something actually useful, instead of the bare module name every
 * candidate otherwise shares. The caller supplies the exact presentable text (e.g. a {@code
 * path:line} via {@link PumpkinGherkinStepItemPresentationProvider#locationOf}), since every
 * candidate in that specific popup is otherwise textually identical (they're all the same
 * "Process: ..." step) and showing that redundant text instead of the location would defeat the
 * point.
 *
 * <p>Gherkin's own PSI base class ({@code GherkinPsiElementBase.getPresentation()}) hardcodes its
 * own {@link ItemPresentation} directly rather than going through the pluggable
 * {@code itemPresentationProvider} extension point, and doesn't override {@link
 * ItemPresentation#getLocationString()} at all - so there is no supported hook to change what a
 * real Gherkin element shows there (see {@link PumpkinGherkinStepItemPresentationProvider}'s doc,
 * confirmed independently here too). This sidesteps that: {@link FakePsiElement} implements
 * {@link ItemPresentation} itself, so this class supplies its own presentation from scratch.
 *
 * <p>Every other method delegates straight through to the wrapped element - deliberately NOT
 * relying on {@link FakePsiElement}'s own stub defaults (non-physical, disconnected from the real
 * PSI tree) for anything other than presentation: an earlier version of this idea used those
 * defaults as-is and broke actual navigation, because the platform's navigation pipeline
 * apparently treats a non-physical/non-delegating element as an invalid target and silently does
 * nothing. Behaviorally this must be indistinguishable from returning the wrapped element
 * directly - the only difference is what the popup displays.
 */
public class PumpkinNavigationTarget extends FakePsiElement {

    private final @NotNull PsiElement target;
    private final @NotNull String presentableText;

    public PumpkinNavigationTarget(@NotNull PsiElement target, @NotNull String presentableText) {
        this.target = target;
        this.presentableText = presentableText;
    }

    @Override
    public PsiElement getParent() {
        return target;
    }

    @Override
    public PsiElement getNavigationElement() {
        return target;
    }

    @Override
    public PsiFile getContainingFile() {
        return target.getContainingFile();
    }

    @Override
    public Project getProject() {
        return target.getProject();
    }

    @Override
    public PsiManager getManager() {
        return target.getManager();
    }

    @Override
    public Language getLanguage() {
        return target.getLanguage();
    }

    @Override
    public TextRange getTextRange() {
        return target.getTextRange();
    }

    @Override
    public int getTextOffset() {
        return target.getTextOffset();
    }

    @Override
    public boolean isValid() {
        return target.isValid();
    }

    @Override
    public boolean isPhysical() {
        return target.isPhysical();
    }

    @Override
    public boolean isWritable() {
        return target.isWritable();
    }

    @Override
    public boolean isEquivalentTo(PsiElement another) {
        return target.isEquivalentTo(another)
                || (another instanceof PumpkinNavigationTarget other && target.isEquivalentTo(other.target));
    }

    @Override
    public void navigate(boolean requestFocus) {
        if (target instanceof Navigatable navigatable) {
            navigatable.navigate(requestFocus);
        }
    }

    @Override
    public boolean canNavigate() {
        return target instanceof Navigatable navigatable && navigatable.canNavigate();
    }

    @Override
    public boolean canNavigateToSource() {
        return target instanceof Navigatable navigatable && navigatable.canNavigateToSource();
    }

    @Override
    public @Nullable String getPresentableText() {
        return presentableText;
    }

    @Override
    public @Nullable String getLocationString() {
        // No secondary text: callers are expected to fold whatever location info they want
        // (see PumpkinGherkinStepItemPresentationProvider.locationOf) directly into
        // presentableText instead, since every candidate here is otherwise indistinguishable
        // (e.g. usages of the same Process all show the identical step text) and a separate
        // "location" element would just repeat it.
        return null;
    }

    @Override
    public @Nullable Icon getIcon(boolean unused) {
        PsiFile file = target.getContainingFile();
        return file != null ? file.getIcon(0) : null;
    }
}
