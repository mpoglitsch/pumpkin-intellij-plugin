package com.pumpkin.intellij.util;

import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Shared conventions for locating directories relative to a Gherkin feature file's own path. */
public final class FeatureFilePaths {

    private FeatureFilePaths() {}

    /**
     * Returns the directory exactly two levels below the nearest ancestor directory literally
     * named {@code features} (case-insensitive) on the path to {@code file} - or {@code null} if
     * no such ancestor exists, or the file isn't nested at least two levels below it.
     *
     * <p>Confirmed with the user (originally for {@code ExtractProcessGenerator}'s own
     * {@code _processes} folder placement, now reused for the {@code dwf:} outgoing-payload
     * template folder too): regardless of how many more subdirectories exist between that point
     * and the file itself, the meaningful anchor always sits at this exact depth - e.g. for
     * {@code features/aax/XXX/YYY/some.feature}, this resolves to {@code features/aax/XXX} (not
     * {@code YYY}, the file's own immediate parent).
     */
    public static @Nullable VirtualFile resolveDirTwoLevelsBelowFeatures(@NotNull VirtualFile file) {
        List<VirtualFile> chain = new ArrayList<>(); // deepest (file's own parent) first
        for (VirtualFile dir = file.getParent(); dir != null; dir = dir.getParent()) {
            chain.add(dir);
            if ("features".equalsIgnoreCase(dir.getName())) break;
        }
        if (chain.isEmpty() || !"features".equalsIgnoreCase(chain.get(chain.size() - 1).getName())) {
            return null;
        }

        int levelTwoIndex = chain.size() - 3; // chain.size()-1 is "features" itself, -2 is level 1
        return levelTwoIndex >= 0 ? chain.get(levelTwoIndex) : null;
    }
}
