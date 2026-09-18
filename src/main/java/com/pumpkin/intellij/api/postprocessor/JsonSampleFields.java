package com.pumpkin.intellij.api.postprocessor;

import com.intellij.json.JsonFileType;
import com.intellij.json.psi.JsonBooleanLiteral;
import com.intellij.json.psi.JsonFile;
import com.intellij.json.psi.JsonNumberLiteral;
import com.intellij.json.psi.JsonObject;
import com.intellij.json.psi.JsonProperty;
import com.intellij.json.psi.JsonStringLiteral;
import com.intellij.json.psi.JsonValue;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Flattens a sample JSON response (as typed into {@link AddPostProcessorDialog}'s "Expected
 * response" field) into a list of dotted leaf field paths (e.g. a nested {@code {"car":
 * {"carname": "..."}}} becomes {@code "car.carname"}), for the field-mappings table's Field
 * column and for {@code PostProcessorCodeGenerator}'s JSON-navigation code generation.
 *
 * <p>Arrays and {@code null} leaves are skipped - there's no array-indexing UI in this dialog, and
 * a {@code null} sample value isn't something meaningful to save or compare against.
 */
final class JsonSampleFields {

    enum LeafType { STRING, NUMBER, BOOLEAN }

    record LeafField(@NotNull String path, @NotNull LeafType type) {}

    private JsonSampleFields() {}

    /** Parses {@code json} and returns every leaf field path, depth-first, or an empty list if it isn't a JSON object. */
    static @NotNull List<LeafField> parse(@NotNull Project project, @NotNull String json) {
        List<LeafField> result = new ArrayList<>();
        if (json.isBlank()) return result;

        PsiFile file = PsiFileFactory.getInstance(project)
                .createFileFromText("pumpkin-postprocessor-sample.json", JsonFileType.INSTANCE, json);
        if (!(file instanceof JsonFile jsonFile)) return result;
        if (jsonFile.getTopLevelValue() instanceof JsonObject root) {
            collect(root, "", result);
        }
        return result;
    }

    private static void collect(@NotNull JsonObject object, @NotNull String prefix, @NotNull List<LeafField> out) {
        for (JsonProperty property : object.getPropertyList()) {
            String path = prefix.isEmpty() ? property.getName() : prefix + "." + property.getName();
            JsonValue value = property.getValue();
            if (value instanceof JsonObject nested) {
                collect(nested, path, out);
            } else if (value instanceof JsonStringLiteral) {
                out.add(new LeafField(path, LeafType.STRING));
            } else if (value instanceof JsonNumberLiteral) {
                out.add(new LeafField(path, LeafType.NUMBER));
            } else if (value instanceof JsonBooleanLiteral) {
                out.add(new LeafField(path, LeafType.BOOLEAN));
            }
            // JsonArray / JsonNullLiteral / empty JsonObject: intentionally not offered as a field.
        }
    }
}
