package com.pumpkin.intellij.workflow.processed;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds (or creates) the {@code <xxFolder>/templates/<objectSnake>/<objectTypeCamel>.json}
 * template file for a {@code dwf:} outgoing-payload step, reusing an existing file when its own
 * fields are a subset of the newly-required ones, or creating a disambiguated {@code _2}/{@code
 * _3}/... file on a genuine conflict (the existing file needs a field the new payload doesn't
 * have) rather than overwriting it.
 *
 * <p>"Fields" for this subset comparison means each placeholder's STRUCTURAL position in the JSON
 * tree ({@link OutgoingPayloadJson#extractStructuralFields}, e.g. {@code body.Order.ChannelId}) -
 * <em>not</em> whatever it's literally named. A hand-edited template commonly renames a
 * placeholder to something short (e.g. {@code %channelId%} sitting at that very path) - comparing
 * literal placeholder text would then never match the newly-generated payload's own {@code
 * body.Order.ChannelId}-style names, permanently defeating reuse. Array indices are also ignored
 * for this comparison (see {@link OutgoingPayloadJson#normalizeForComparison}). The table header
 * shown to the user, however, must stay the existing file's own literal placeholder names ({@link
 * #readLiteralFields}) - that's the actual token a real step implementation substitutes a table
 * value into at runtime, so a recomputed structural name there would never be found in the file.
 */
final class OutgoingPayloadTemplateResolver {

    /**
     * Existing templates already have bare {@code %name%} tokens in value position - no longer
     * valid JSON - so their own literal names (for the table header - see the class doc) are read
     * back via plain regex, not JSON parsing. A field may have been hand-edited into {@code
     * %name:contextParamName%} to bind it to a named context parameter instead of requiring a
     * table value - that suffix is part of the *value*, not the field's identity, so it's
     * deliberately excluded from the captured group (matches {@code OutgoingPayloadTemplates}'s
     * own {@code REQUIRED_PLACEHOLDER} shape).
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("%([^%:]+)(?::[^%]*)?%");

    record Resolved(@NotNull String relativePath, @NotNull List<String> fields) {}

    private OutgoingPayloadTemplateResolver() {}

    static @NotNull Resolved resolveOrCreate(@NotNull Project project, @NotNull Module module, @NotNull String xxFolder,
                                             @NotNull String objectSnake, @NotNull String objectTypeCamel,
                                             @NotNull String rewrittenJson, @NotNull List<String> newFields) {
        VirtualFile dir = findOrCreateDir(module, xxFolder, objectSnake);
        if (dir == null) {
            throw new IllegalStateException(
                    "Could not find or create a source directory for " + xxFolder + "/templates/" + objectSnake + ".");
        }

        Set<String> newNormalized = normalize(newFields);
        String relativeDir = xxFolder + "/templates/" + objectSnake + "/";

        for (int suffix = 0; ; suffix++) {
            String fileName = (suffix == 0 ? objectTypeCamel : objectTypeCamel + "_" + (suffix + 1)) + ".json";
            VirtualFile existing = dir.findChild(fileName);
            if (existing == null) {
                createFile(dir, fileName, rewrittenJson);
                return new Resolved(relativeDir + fileName, newFields);
            }
            String existingContent = loadContent(existing);
            List<String> existingStructuralFields = OutgoingPayloadJson.extractStructuralFields(project, existingContent);
            if (existingStructuralFields != null && newNormalized.containsAll(normalize(existingStructuralFields))) {
                return new Resolved(relativeDir + fileName, readLiteralFields(existingContent));
            }
            // Existing has a field the new payload doesn't need (or isn't valid JSON at all, so
            // containment can't be established) - a genuine conflict, not just a narrower request
            // - leave it untouched and try the next disambiguated name.
        }
    }

    private static @Nullable VirtualFile findOrCreateDir(@NotNull Module module, @NotNull String xxFolder,
                                                         @NotNull String objectSnake) {
        String relativePath = xxFolder + "/templates/" + objectSnake;
        VirtualFile[] roots = ModuleRootManager.getInstance(module).getSourceRoots();

        for (VirtualFile root : roots) {
            VirtualFile existing = root.findFileByRelativePath(relativePath);
            if (existing != null) return existing;
        }

        // No existing folder anywhere yet - create it under whichever source root already has a
        // top-level "XX" folder (where this project's real templates live), falling back to the
        // first source root at all if XX itself doesn't exist anywhere yet either.
        VirtualFile bestRoot = null;
        for (VirtualFile root : roots) {
            if (root.findChild(xxFolder) != null) {
                bestRoot = root;
                break;
            }
        }
        if (bestRoot == null && roots.length > 0) bestRoot = roots[0];
        if (bestRoot == null) return null;

        try {
            return VfsUtil.createDirectoryIfMissing(bestRoot, relativePath);
        } catch (IOException e) {
            return null;
        }
    }

    private static void createFile(@NotNull VirtualFile dir, @NotNull String fileName, @NotNull String content) {
        try {
            VirtualFile file = dir.createChildData(OutgoingPayloadTemplateResolver.class, fileName);
            VfsUtil.saveText(file, content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create " + fileName, e);
        }
    }

    private static @NotNull String loadContent(@NotNull VirtualFile file) {
        try {
            return VfsUtil.loadText(file);
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + file.getName() + ": " + e.getMessage(), e);
        }
    }

    /** The existing file's own literal {@code %name%} tokens, colon-suffix stripped - see the class doc for why these (not structural paths) are what the table header needs. */
    private static @NotNull List<String> readLiteralFields(@NotNull String content) {
        List<String> fields = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(content);
        while (m.find()) {
            fields.add(m.group(1));
        }
        return fields;
    }

    private static @NotNull Set<String> normalize(@NotNull List<String> fields) {
        Set<String> result = new HashSet<>();
        for (String field : fields) {
            result.add(OutgoingPayloadJson.normalizeForComparison(field));
        }
        return result;
    }
}
