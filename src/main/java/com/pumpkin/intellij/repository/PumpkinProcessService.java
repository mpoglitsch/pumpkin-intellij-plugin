package com.pumpkin.intellij.repository;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProcessCanceledException;
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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
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

    /**
     * Guards against a real race between {@link #getProcesses} and {@link #invalidate}: {@code
     * loadProcesses()} can take long enough (scanning every process file) that an edit - and the
     * VFS-change {@code invalidate()} it triggers - can land while a load is still in flight.
     * Without this counter, that in-flight load (built from data read *before* the edit) would
     * finish afterwards and unconditionally overwrite {@code cache} with that now-stale snapshot;
     * since it's one shared, project-wide cache, every Process lookup would then silently use
     * stale data until *another* unrelated edit happened to invalidate it again - matching
     * exactly the reported symptom of Process navigation going dead project-wide after a save,
     * fixed only by restarting. Incremented on every {@link #invalidate}; a load only gets cached
     * if the counter hasn't moved since that load started - otherwise it's simply discarded (the
     * result is still returned for this one call - it just isn't cached), leaving {@code cache
     * == null} so the next call reloads fresh instead of latching onto stale data forever.
     */
    private final AtomicLong invalidationCount = new AtomicLong();

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
                            if (f != null && "feature".equalsIgnoreCase(f.getExtension())
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

        long countBeforeLoad = invalidationCount.get();
        List<PumpkinProcessDefinition> loaded = ReadAction.compute(() -> loadProcesses());

        // Only cache this result if nothing invalidated us while we were loading - see
        // invalidationCount's doc comment for why caching it unconditionally is the actual bug.
        if (invalidationCount.get() == countBeforeLoad) {
            cache = loaded;
        }
        return loaded;
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

    /**
     * Returns the processes whose pattern matches {@code invocationText} <em>and</em> whose
     * {@code @ProcessContext(...)} tag equals {@code requestedContext} - {@code null} means "the
     * default (untagged) variant", the same convention used by the call-site {@code |
     * ContextName} suffix ({@link com.pumpkin.intellij.util.GherkinPsiUtil#getInvocationContextName})
     * and by {@code ProcessExecutor.execute}'s {@code contextName} parameter at runtime. Without
     * this filter, a process with more than one context variant would look ambiguous (multiple
     * name-matches) to every caller that actually wants the one specific variant a given
     * invocation resolves to.
     */
    public @NotNull List<PumpkinProcessDefinition> findMatchingProcesses(
            @NotNull String invocationText, @Nullable String requestedContext) {
        return findMatchingProcesses(invocationText).stream()
                .filter(def -> Objects.equals(def.getContextName(), requestedContext))
                .collect(Collectors.toList());
    }

    /** Drops the cached definitions so they are reloaded on the next access. */
    public void invalidate() {
        invalidationCount.incrementAndGet();
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
            } catch (ProcessCanceledException e) {
                // Must always propagate, never be caught-and-continued: this fires whenever a
                // pending write action cancels our read action mid-scan (normal, expected - e.g.
                // exactly the save that triggered this reload in the first place racing against
                // it). Swallowing it here previously let the loop silently continue through every
                // remaining file, each of which immediately fails the same way once the read
                // action is cancelled - so a scan interrupted partway would return a truncated,
                // near-empty result for the *rest* of the file list, which then got legitimately
                // cached by the race guard above (nothing invalidated us again during that bad
                // scan) - poisoning the cache with incomplete data until restart. Letting this
                // propagate instead unwinds out of getProcesses() entirely (so nothing gets
                // cached) and into the platform's own read-action retry loop, which transparently
                // retries the whole operation once the write action is done.
                throw e;
            } catch (Exception ignored) {
                // Malformed or inaccessible file - skip gracefully.
            }
        }

        return Collections.unmodifiableList(result);
    }
}
