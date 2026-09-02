package com.pumpkin.intellij.repository;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.pumpkin.intellij.settings.PumpkinSettingsState;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Discovers {@code .feature} files inside the configured process directories using
 * IntelliJ's Virtual File System (no {@code java.nio.file.Files.walk}).
 */
public final class PumpkinProcessRepository {

    private final @NotNull Project project;

    public PumpkinProcessRepository(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Recursively collects all {@code .feature} files beneath every configured process directory.
     * Missing or inaccessible directories are silently skipped.
     */
    public @NotNull List<VirtualFile> findProcessFeatureFiles() {
        List<VirtualFile> result = new ArrayList<>();
        PumpkinSettingsState settings = PumpkinSettingsState.getInstance(project);

        for (String dirPath : settings.getProcessDirectories()) {
            VirtualFile dir = resolveDirectory(dirPath);
            if (dir == null || !dir.isDirectory()) continue;

            VfsUtilCore.visitChildrenRecursively(dir, new VirtualFileVisitor<Void>() {
                @Override
                public boolean visitFile(@NotNull VirtualFile file) {
                    if (!file.isDirectory() && "feature".equalsIgnoreCase(file.getExtension())) {
                        result.add(file);
                    }
                    return true;
                }
            });
        }

        return result;
    }

    /**
     * Returns {@code true} when the given file is inside any of the configured process directories.
     * Used to decide whether a VFS change should trigger cache invalidation.
     */
    public boolean isInsideProcessDirectory(@NotNull VirtualFile file) {
        PumpkinSettingsState settings = PumpkinSettingsState.getInstance(project);
        for (String dirPath : settings.getProcessDirectories()) {
            VirtualFile dir = resolveDirectory(dirPath);
            if (dir != null && VfsUtilCore.isAncestor(dir, file, false)) {
                return true;
            }
        }
        return false;
    }

    private @NotNull VirtualFile projectBase() {
        // project.getBaseDir() is deprecated; use basePath instead.
        String basePath = project.getBasePath();
        if (basePath != null) {
            VirtualFile base = LocalFileSystem.getInstance().findFileByPath(basePath);
            if (base != null) return base;
        }
        // Fallback: derive from project file location.
        if (project.getProjectFile() != null && project.getProjectFile().getParent() != null) {
            return project.getProjectFile().getParent();
        }
        return LocalFileSystem.getInstance().findFileByPath("/");
    }

    private VirtualFile resolveDirectory(@NotNull String path) {
        if (path.isBlank()) return null;
        // Absolute path
        if (path.startsWith("/") || (path.length() > 2 && path.charAt(1) == ':')) {
            return LocalFileSystem.getInstance().findFileByPath(path);
        }
        // Project-relative
        VirtualFile base = projectBase();
        return base != null ? base.findFileByRelativePath(path) : null;
    }
}
