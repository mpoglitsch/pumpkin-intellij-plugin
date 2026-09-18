package com.pumpkin.intellij.highlighting;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;

/**
 * Text attribute keys for Pumpkin Process highlighting.
 * Configurable via Settings → Editor → Color Scheme → Pumpkin.
 * Explicit default colors for {@link #PROCESS_KEYWORD} and {@link #PROCESS_VARIABLE} live in
 * {@code colorSchemes/PumpkinDefault.xml} and {@code colorSchemes/PumpkinDarcula.xml}.
 */
public final class PumpkinTextAttributeKeys {

    /** Applied to the {@code Process:} keyword and the trailing "with/without data" marker. */
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

    /**
     * Applied to the literal (non-variable) portions of a Process invocation, forcing the
     * editor's plain default text color instead of Gherkin's own step-text color. No explicit
     * override is defined in the color scheme XML files – it is meant to always fall back to
     * {@link HighlighterColors#TEXT}, whatever that is in the active scheme.
     */
    public static final TextAttributesKey PROCESS_TEXT =
            TextAttributesKey.createTextAttributesKey(
                    "PUMPKIN_PROCESS_TEXT",
                    HighlighterColors.TEXT
            );

    /**
     * Applied to a Process context marker: the {@code @ProcessContext(...)} tag on a Process's
     * own definition, and the trailing {@code | ContextName} suffix on a call-site invocation.
     * Deliberately its own distinct color (not reusing {@link #PROCESS_KEYWORD}/{@link
     * #PROCESS_VARIABLE}) so a context is immediately recognizable wherever it appears.
     */
    public static final TextAttributesKey PROCESS_CONTEXT =
            TextAttributesKey.createTextAttributesKey(
                    "PUMPKIN_PROCESS_CONTEXT",
                    DefaultLanguageHighlighterColors.KEYWORD
            );

    /**
     * Applied to the external table/column reference actually being tested inside a DB-step
     * header cell's {@code join(...)}/{@code rejoin(...)} spec: the whole {@code table.column}
     * argument for {@code join(column, table.column)}, and both {@code table1} (not {@code
     * column1}, the purely structural FK-linking column) and {@code column2} for {@code
     * rejoin(table1.column1, column2)}.
     */
    public static final TextAttributesKey DB_REFERENCE =
            TextAttributesKey.createTextAttributesKey(
                    "PUMPKIN_DB_REFERENCE",
                    DefaultLanguageHighlighterColors.STRING
            );

    private PumpkinTextAttributeKeys() {}
}
