package com.pumpkin.intellij.highlighting;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;

/**
 * Text attribute keys for Pumpkin Process highlighting.
 * Configurable via Settings → Editor → Color Scheme → Pumpkin.
 */
public final class PumpkinTextAttributeKeys {

    /** Applied to the {@code Process:} keyword inside a Process step. */
    public static final TextAttributesKey PROCESS_KEYWORD =
            TextAttributesKey.createTextAttributesKey(
                    "PUMPKIN_PROCESS_KEYWORD",
                    DefaultLanguageHighlighterColors.KEYWORD
            );

    /** Applied to the variable value inside a Process invocation (e.g. {@code Hans}). */
    public static final TextAttributesKey PROCESS_VARIABLE =
            TextAttributesKey.createTextAttributesKey(
                    "PUMPKIN_PROCESS_VARIABLE",
                    DefaultLanguageHighlighterColors.STRING
            );

    private PumpkinTextAttributeKeys() {}
}
