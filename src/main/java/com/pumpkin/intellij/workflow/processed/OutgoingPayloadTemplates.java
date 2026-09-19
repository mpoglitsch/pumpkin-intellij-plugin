package com.pumpkin.intellij.workflow.processed;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared lookup/table-building helpers for outgoing-payload template files - used by both {@link
 * OutgoingPayloadTemplateCompletionContributor} (autocomplete while typing the step's own path)
 * and {@link OutgoingPayloadTriggerHandler} (the {@code op:} shortcut that builds the whole step
 * in one go), so the field-extraction regex and table-insertion math exist in exactly one place.
 */
final class OutgoingPayloadTemplates {

    // A placeholder with no colon suffix at all has no default - see this class's callers' own
    // docs for why this can't just reuse ApiEndpointParameters.TEMPLATE_VARIABLE's narrower
    // identifier-only version: these placeholders can be dotted/indexed paths like
    // %car.carname%/%arr[0].field%, not just plain identifiers.
    private static final Pattern REQUIRED_PLACEHOLDER = Pattern.compile("%([^%:]+)(:[^%]*)?%");

    private OutgoingPayloadTemplates() {}

    /**
     * Finds the {@code <xxFolder>/templates} directory across every source root of {@code
     * module}, or {@code null} if none exists yet - mirrors {@code OutgoingPayloadTemplateResolver
     * .findOrCreateDir}'s own search exactly, since a template lives wherever {@code dwf:} put it
     * (e.g. {@code src/main/resources/<xxFolder>/templates/...}), which is a completely different
     * source root than the feature files themselves live under - it is NOT necessarily a child of
     * the {@code features}-tree folder {@link com.pumpkin.intellij.util.FeatureFilePaths
     * #resolveDirTwoLevelsBelowFeatures} finds (that folder is only used to read off the {@code
     * XX} *name*, passed in here as {@code xxFolder}).
     */
    static @Nullable VirtualFile findTemplatesDir(@NotNull Module module, @NotNull String xxFolder) {
        String relativePath = xxFolder + "/templates";
        for (VirtualFile root : ModuleRootManager.getInstance(module).getSourceRoots()) {
            VirtualFile existing = root.findFileByRelativePath(relativePath);
            if (existing != null) return existing;
        }
        return null;
    }

    /** Every {@code .json} file anywhere under {@code templatesDir}, however deeply nested. */
    static @NotNull List<VirtualFile> findTemplateFiles(@NotNull VirtualFile templatesDir) {
        List<VirtualFile> result = new ArrayList<>();
        VfsUtilCore.visitChildrenRecursively(templatesDir, new VirtualFileVisitor<Void>() {
            @Override
            public boolean visitFile(@NotNull VirtualFile file) {
                if (!file.isDirectory() && "json".equalsIgnoreCase(file.getExtension())) {
                    result.add(file);
                }
                return true;
            }
        });
        return result;
    }

    /** Every {@code %field%} in {@code templateFile} with no {@code :default} suffix. */
    static @NotNull List<String> readRequiredFields(@NotNull VirtualFile templateFile) {
        String content;
        try {
            content = VfsUtil.loadText(templateFile);
        } catch (IOException e) {
            return List.of();
        }
        List<String> fields = new ArrayList<>();
        Matcher m = REQUIRED_PLACEHOLDER.matcher(content);
        while (m.find()) {
            if (m.group(2) == null) {
                fields.add(m.group(1));
            }
        }
        return fields;
    }

    /** Header row = the required fields (pre-filled); blank data row; caret one char into its first cell. */
    static void insertFieldsTable(@NotNull Document document, @NotNull Editor editor, int insertOffset,
                                  @NotNull String indent, @NotNull List<String> fields) {
        String header = indent + "| " + String.join(" | ", fields) + " |";
        String dataRow = indent + "|" + "  |".repeat(fields.size());
        document.insertString(insertOffset, "\n" + header + "\n" + dataRow);

        int dataRowStart = insertOffset + 1 + header.length() + 1;
        int firstCellOffset = dataRowStart + indent.length() + 2; // past "indent" + "|", one char into "  "
        editor.getCaretModel().moveToOffset(firstCellOffset);
    }
}
