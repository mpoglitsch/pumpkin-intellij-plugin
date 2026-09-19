package com.pumpkin.intellij.workflow.processed;

import com.intellij.json.JsonFileType;
import com.intellij.json.psi.JsonArray;
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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Rewrites a sample JSON payload (a workflow item's {@code content_outgoing}) into a
 * {@code %field%} placeholder template - every leaf value swapped for {@code %dotted.path%}
 * (quoted for strings, bare for numbers/booleans), array elements addressed by concrete index
 * ({@code arr[0].field}) - and consistently pretty-printed (2-space indent) regardless of how
 * {@code content_outgoing} itself happened to be formatted in the database (often compact/
 * minified). Serializing a fresh, canonically-indented string from the parsed structure - rather
 * than splicing edits into the original text - is what makes this possible at all: a real JSON
 * pretty-printer can't run on the *final* result, since a bare {@code %name%} token in value
 * position isn't valid JSON any more.
 *
 * <p>Deliberately not built on {@code JsonSampleFields} (added earlier for the PostProcessor
 * dialog, in a different package): that utility only flattens to a read-only field list, skips
 * arrays entirely, and never touches the source text - this needs both real array traversal and
 * full re-serialization, different enough to warrant its own small utility rather than stretching
 * that one to cover both jobs.
 */
final class OutgoingPayloadJson {

    private static final String INDENT_UNIT = "  ";

    record Rewritten(@NotNull String json, @NotNull List<String> fields) {}

    private OutgoingPayloadJson() {}

    /**
     * Parses {@code json} and returns the rewritten, pretty-printed text plus every leaf field
     * path encountered (document order, concrete array indices) - or {@code null} if it isn't a
     * JSON object at all.
     */
    static @Nullable Rewritten rewrite(@NotNull Project project, @NotNull String json) {
        PsiFile file = PsiFileFactory.getInstance(project)
                .createFileFromText("pumpkin-outgoing-payload.json", JsonFileType.INSTANCE, json);
        if (!(file instanceof JsonFile jsonFile) || !(jsonFile.getTopLevelValue() instanceof JsonObject root)) {
            return null;
        }

        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        writeObject(root, "", sb, 0, fields);
        sb.append('\n');

        return new Rewritten(sb.toString(), fields);
    }

    /** Strips concrete array indices (e.g. {@code arr[0].field} -> {@code arr[].field}) for field-set comparison only. */
    static @NotNull String normalizeForComparison(@NotNull String field) {
        return field.replaceAll("\\[\\d+]", "[]");
    }

    // Every field this generator itself ever writes for a number/boolean leaf is a BARE
    // %path% token (see writeValue) - not valid JSON on its own. Replacing just the token (never
    // any adjacent quotes) with a bare "0" turns both shapes into valid JSON uniformly: a bare
    // %path% becomes the valid number literal 0, while a quoted "%path%" becomes the valid string
    // "0" (the quotes were never touched) - no need to even detect which case applies.
    private static final Pattern PLACEHOLDER_TOKEN = Pattern.compile("%[^%]+%");

    /**
     * Parses an EXISTING template file's content (already containing {@code %name%} placeholders
     * in value position) and returns the dotted/indexed STRUCTURAL path of every leaf position -
     * e.g. {@code body.Order.ChannelId} - completely ignoring what each placeholder happens to be
     * literally named. A hand-edited template may rename a placeholder to something short and
     * human-friendly (e.g. {@code %channelId%} sitting at that very path) - the placeholder's own
     * name is irrelevant to whether it structurally corresponds to a field in a newly-generated
     * payload, only its JSON *position* is. Returns {@code null} if the content isn't a JSON
     * object at all.
     *
     * <p>{@code content} is sanitized (every {@code %...%} token replaced with a bare {@code 0} -
     * see {@link #PLACEHOLDER_TOKEN}'s own doc) before parsing - a bare, unquoted placeholder in
     * number/boolean position (e.g. {@code "serviceId": %serviceId%,}, exactly what this
     * generator itself writes for a numeric field) is NOT valid JSON, and IntelliJ's parser does
     * NOT reliably recover a usable {@code JsonObject} tree around it (confirmed: it can fail the
     * whole parse rather than just localizing the error to that one leaf) - so parsing the raw,
     * unsanitized content here would make this return {@code null} for the ordinary case of any
     * template with a numeric field, defeating reuse entirely. Sanitizing first guarantees every
     * leaf is a normal, validly-typed literal, so the structural walk below never needs to guess
     * at what a malformed value might have parsed as.
     */
    static @Nullable List<String> extractStructuralFields(@NotNull Project project, @NotNull String content) {
        String sanitized = PLACEHOLDER_TOKEN.matcher(content).replaceAll("0");
        PsiFile file = PsiFileFactory.getInstance(project)
                .createFileFromText("pumpkin-outgoing-payload-existing.json", JsonFileType.INSTANCE, sanitized);
        if (!(file instanceof JsonFile jsonFile) || !(jsonFile.getTopLevelValue() instanceof JsonObject root)) {
            return null;
        }

        List<String> fields = new ArrayList<>();
        collectStructuralFields(root, "", fields);
        return fields;
    }

    private static void collectStructuralFields(@NotNull JsonObject object, @NotNull String prefix,
                                                @NotNull List<String> fields) {
        for (JsonProperty property : object.getPropertyList()) {
            String path = prefix.isEmpty() ? property.getName() : prefix + "." + property.getName();
            collectStructuralValue(property.getValue(), path, fields);
        }
    }

    private static void collectStructuralValue(@Nullable JsonValue value, @NotNull String path,
                                               @NotNull List<String> fields) {
        if (value instanceof JsonObject nested) {
            collectStructuralFields(nested, path, fields);
        } else if (value instanceof JsonArray array) {
            List<JsonValue> elements = array.getValueList();
            for (int i = 0; i < elements.size(); i++) {
                collectStructuralValue(elements.get(i), path + "[" + i + "]", fields);
            }
        } else {
            // A well-formed literal, an error element, or null (property.getValue() couldn't
            // parse one at all) - every one of these is a leaf at this position regardless.
            fields.add(path);
        }
    }

    private static void writeValue(@NotNull JsonValue value, @NotNull String path, @NotNull StringBuilder sb,
                                   int indent, @NotNull List<String> fields) {
        if (value instanceof JsonObject nested) {
            writeObject(nested, path, sb, indent, fields);
        } else if (value instanceof JsonArray array) {
            writeArray(array, path, sb, indent, fields);
        } else if (value instanceof JsonStringLiteral) {
            sb.append('"').append('%').append(path).append('%').append('"');
            fields.add(path);
        } else if (value instanceof JsonNumberLiteral || value instanceof JsonBooleanLiteral) {
            sb.append('%').append(path).append('%');
            fields.add(path);
        } else {
            // JsonNullLiteral (or anything else unrecognized): kept as its own literal text,
            // contributes no field - matches JsonSampleFields' own choice for the same shape.
            sb.append(value.getText());
        }
    }

    private static void writeObject(@NotNull JsonObject object, @NotNull String prefix, @NotNull StringBuilder sb,
                                    int indent, @NotNull List<String> fields) {
        List<JsonProperty> properties = object.getPropertyList();
        if (properties.isEmpty()) {
            sb.append("{}");
            return;
        }

        sb.append("{\n");
        for (int i = 0; i < properties.size(); i++) {
            JsonProperty property = properties.get(i);
            String path = prefix.isEmpty() ? property.getName() : prefix + "." + property.getName();

            appendIndent(sb, indent + 1);
            sb.append('"').append(property.getName()).append("\": ");

            JsonValue value = property.getValue();
            if (value != null) {
                writeValue(value, path, sb, indent + 1, fields);
            } else {
                sb.append("null");
            }

            if (i < properties.size() - 1) sb.append(',');
            sb.append('\n');
        }
        appendIndent(sb, indent);
        sb.append('}');
    }

    private static void writeArray(@NotNull JsonArray array, @NotNull String prefix, @NotNull StringBuilder sb,
                                   int indent, @NotNull List<String> fields) {
        List<JsonValue> elements = array.getValueList();
        if (elements.isEmpty()) {
            sb.append("[]");
            return;
        }

        sb.append("[\n");
        for (int i = 0; i < elements.size(); i++) {
            appendIndent(sb, indent + 1);
            writeValue(elements.get(i), prefix + "[" + i + "]", sb, indent + 1, fields);
            if (i < elements.size() - 1) sb.append(',');
            sb.append('\n');
        }
        appendIndent(sb, indent);
        sb.append(']');
    }

    private static void appendIndent(@NotNull StringBuilder sb, int level) {
        sb.append(INDENT_UNIT.repeat(level));
    }
}
