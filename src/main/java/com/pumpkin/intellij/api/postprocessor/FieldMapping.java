package com.pumpkin.intellij.api.postprocessor;

import org.jetbrains.annotations.NotNull;

/**
 * One row of {@link AddPostProcessorDialog}'s field-mappings table: what to do with {@code
 * fieldPath} (a dotted leaf path into the expected-response JSON, e.g. {@code "car.carname"},
 * with its {@code leafType} captured at selection time so {@code PostProcessorCodeGenerator}
 * doesn't need the original JSON sample to know whether to generate {@code .getString(...)} or a
 * {@code String.valueOf(...)}-wrapped {@code .get(...)}) and how to interpret {@code value} - a
 * context-parameter name to save into for {@link MappingAction#SAVE_TO_CONTEXT}, or a comparison
 * target for {@link MappingAction#EQUALS} (either a literal string, or a
 * {@code %contextParamName%}-shaped reference to an existing context parameter's current value -
 * see {@code PostProcessorCodeGenerator} for how that's detected).
 */
public record FieldMapping(@NotNull String fieldPath, @NotNull JsonSampleFields.LeafType leafType,
                           @NotNull MappingAction action, @NotNull String value) {
}
