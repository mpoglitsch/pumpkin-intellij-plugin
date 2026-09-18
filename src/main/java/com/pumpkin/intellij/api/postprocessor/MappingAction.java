package com.pumpkin.intellij.api.postprocessor;

/**
 * What a {@link FieldMapping} does with its selected response field - offered per-row in {@link
 * AddPostProcessorDialog}'s field-mappings table.
 */
public enum MappingAction {

    SAVE_TO_CONTEXT("Save to Context"),
    EQUALS("Equals");

    private final String displayName;

    MappingAction(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
