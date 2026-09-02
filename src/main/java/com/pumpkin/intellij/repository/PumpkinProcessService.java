package com.pumpkin.intellij.repository;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.openapi.application.ReadAction;
import com.pumpkin.intellij.matching.PumpkinProcessMatcher;
import com.pumpkin.intellij.model.PumpkinProcessDefinition;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Project-scoped service that owns the cache of discovered Pumpkin Process definitions.
 *
 * <p>The cache is built lazily on first access and invalidated whenever a {@code .feature} file
 * inside a configured process directory changes on the VFS.
 */
@Service(Service.Level.PROJECT)
public final class PumpkinProcessService implements Disposable {

    private final @NotNull Project project;
    private final @NotNull PumpkinProcessRepository repository;
    private final @NotNull PumpkinProcessParser parser;

    /** Volatile so reads see the latest write without synchronisation overhead. */
    private volatile List<PumpkinProcessDefinition> cache;

    public PumpkinProcessService(@NotNull Project project) {
        this.project = project;
        this.repository = new PumpkinProcessRepository(project);
        this.parser = new PumpkinProcessParser();

        // Invalidate whenever any .feature file in a process directory changes.
        project.getMessageBus().connect(this).subscribe(
                VirtualFileManager.VFS_CHANGES,
                new BulkFileListener() {
                    @Override
                    public void after(@NotNull List<? extends VFileEvent> events) {
                        for (VFileEvent e : events) {
                            VirtualFile f = e.getFile();
                            if (f != null
                                    && "feature".equalsIgnoreCase(f.getExtension())
                                    && repository.isInsideProcessDirectory(f)) {
                                invalidate();
                                return;
                            }
                        }
                    }
                }
        );
    }

    public static @NotNull PumpkinProcessService getInstance(@NotNull Project project) {
        return project.getService(PumpkinProcessService.class);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Returns all known Pumpkin Process definitions, loading from disk if necessary. */
    public @NotNull List<PumpkinProcessDefinition> getProcesses() {
        List<PumpkinProcessDefinition> cached = cache;
        if (cached != null) return cached;
        cached = ReadAction.compute(() -> loadProcesses());
        cache = cached;
        return cached;
    }

    /**
     * Returns the single process that matches {@code invocationText}, or empty if none
     * or if multiple processes match the same text (ambiguous).
     */
    public @NotNull Optional<PumpkinProcessDefinition> findProcess(@NotNull String invocationText) {
        List<PumpkinProcessDefinition> matches = findMatchingProcesses(invocationText);
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    /** Returns all processes whose pattern matches the given invocation text. */
    public @NotNull List<PumpkinProcessDefinition> findMatchingProcesses(@NotNull String invocationText) {
        return getProcesses().stream()
                .filter(def -> PumpkinProcessMatcher.matches(def, invocationText))
                .collect(Collectors.toList());
    }

    /** Drops the cached definitions so they are reloaded on the next access. */
    public void invalidate() {
        cache = null;
    }

    @Override
    public void dispose() {
        cache = null;
    }

    // -------------------------------------------------------------------------
    // Internal loading
    // -------------------------------------------------------------------------

    private @NotNull List<PumpkinProcessDefinition> loadProcesses() {
        List<VirtualFile> featureFiles = repository.findProcessFeatureFiles();
        List<PumpkinProcessDefinition> result = new ArrayList<>();

        PsiManager psiManager = PsiManager.getInstance(project);
        for (VirtualFile vf : featureFiles) {
            try {
                PsiFile psiFile = psiManager.findFile(vf);
                if (psiFile instanceof GherkinFile) {
                    result.addAll(parser.parse((GherkinFile) psiFile));
                }
            } catch (Exception ignored) {
                // Malformed or inaccessible file – skip gracefully.
            }
        }

        return Collections.unmodifiableList(result);
    }
}
