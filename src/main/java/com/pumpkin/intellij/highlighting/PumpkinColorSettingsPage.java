package com.pumpkin.intellij.highlighting;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Map;

/**
 * Registers Pumpkin text attributes under Settings → Editor → Color Scheme → Pumpkin.
 */
public class PumpkinColorSettingsPage implements ColorSettingsPage {

    private static final AttributesDescriptor[] DESCRIPTORS = {
            new AttributesDescriptor("Process keyword//Process:", PumpkinTextAttributeKeys.PROCESS_KEYWORD),
            new AttributesDescriptor("Process variable value", PumpkinTextAttributeKeys.PROCESS_VARIABLE),
            new AttributesDescriptor("Process literal text", PumpkinTextAttributeKeys.PROCESS_TEXT),
            new AttributesDescriptor("Process context", PumpkinTextAttributeKeys.PROCESS_CONTEXT),
            new AttributesDescriptor("DB step join/rejoin reference", PumpkinTextAttributeKeys.DB_REFERENCE),
    };

    @Override
    public @Nullable Icon getIcon() {
        return null;
    }

    @Override
    public @NotNull SyntaxHighlighter getHighlighter() {
        return new PlainSyntaxHighlighter();
    }

    @Override
    public @NotNull String getDemoText() {
        return "* <keyword>Process:</keyword> <text>Create customer</text> <variable>Hans</variable> <context>| MobileApp</context> <keyword>with data</keyword>\n" +
               "    | service | <variable>25736</variable>      |\n" +
               "    | name    | <variable>Hans Peter</variable> |\n" +
               "* these values are present in customer\n" +
               "    | join(id, <dbref>order.customerId</dbref>)                        |\n" +
               "    | rejoin(<dbref>order</dbref>.customerId, <dbref>status</dbref>)    |\n";
    }

    @Override
    public @Nullable Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
        return Map.of(
                "keyword", PumpkinTextAttributeKeys.PROCESS_KEYWORD,
                "variable", PumpkinTextAttributeKeys.PROCESS_VARIABLE,
                "text", PumpkinTextAttributeKeys.PROCESS_TEXT,
                "context", PumpkinTextAttributeKeys.PROCESS_CONTEXT,
                "dbref", PumpkinTextAttributeKeys.DB_REFERENCE
        );
    }

    @Override
    public AttributesDescriptor @NotNull [] getAttributeDescriptors() {
        return DESCRIPTORS;
    }

    @Override
    public ColorDescriptor @NotNull [] getColorDescriptors() {
        return ColorDescriptor.EMPTY_ARRAY;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Pumpkin";
    }
}
