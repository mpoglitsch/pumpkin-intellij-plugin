package com.pumpkin.intellij.ui;

import com.intellij.lang.Language;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.project.Project;
import com.intellij.ui.LanguageTextField;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A multi-line {@link LanguageTextField} that actually supports mouse-wheel scrolling.
 *
 * <p>{@code EditorTextField.createEditor()} unconditionally runs {@code setupTextFieldEditor(...)}
 * on every embedded editor it creates - single-line <em>or</em> multi-line - which force-hides
 * both scrollbars via {@code editor.setVerticalScrollbarVisible(false)}/{@code
 * setHorizontalScrollbarVisible(false)} (confirmed by reading {@code EditorTextField.java} from
 * the {@code 262.10315.125} SDK sources - it's not conditional on one-line mode). With the
 * vertical scrollbar hidden, the embedded editor treats itself as "nothing to scroll" and mouse
 * wheel events over it go nowhere - only the caret-driven arrow keys still move the view. The only
 * place left to intervene is right after the base class's {@code createEditor()} returns, by
 * re-enabling both scrollbars - this restores real scroll-wheel support for any field with more
 * content than fits its visible area.
 */
public class ScrollableLanguageTextField extends LanguageTextField {

    public ScrollableLanguageTextField(@Nullable Language language, @Nullable Project project, @NotNull String value) {
        super(language, project, value, false);
    }

    @Override
    protected @NotNull EditorEx createEditor() {
        EditorEx editor = super.createEditor();
        editor.setVerticalScrollbarVisible(true);
        editor.setHorizontalScrollbarVisible(true);
        return editor;
    }
}
