package com.pumpkin.intellij.contextparam;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.pumpkin.intellij.model.PumpkinProcessDefinition;
import com.pumpkin.intellij.model.PumpkinProcessVariable;
import com.pumpkin.intellij.repository.PumpkinProcessService;
import com.pumpkin.intellij.util.GherkinPsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinFeature;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;
import org.jetbrains.plugins.cucumber.psi.GherkinScenario;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;
import org.jetbrains.plugins.cucumber.psi.GherkinStepsHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes which {@code %contextParameter%} names are already established at a given point in a
 * feature file, for {@link ContextParameterCompletionContributor}. Per-file extraction (see {@link
 * ContextParameterStepAnalyzer}) is cached via {@link CachedValuesManager}, auto-invalidated
 * whenever that file's PSI changes - the first use of {@code CachedValuesManager} in this
 * codebase; the only prior cache ({@link PumpkinProcessService}) hand-rolls a project-wide
 * volatile+listener+counter scheme that isn't the right shape for this per-file need.
 */
@Service(Service.Level.PROJECT)
public final class PumpkinContextParameterService {

    private final @NotNull Project project;

    public PumpkinContextParameterService(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull PumpkinContextParameterService getInstance(@NotNull Project project) {
        return project.getService(PumpkinContextParameterService.class);
    }

    /**
     * Every context-parameter name established strictly before {@code atStep} within its own
     * scenario (plus that file's own Background steps, plus - if {@code atStep}'s scenario is
     * itself a {@code @pumpkin} Process - its own {@code {placeholder}} names), recursively
     * expanding every {@code Process: ...} invocation encountered along the way.
     */
    @NotNull
    public List<String> collectAvailableParameters(@NotNull GherkinStep atStep) {
        GherkinStepsHolder holder = PsiTreeUtil.getParentOfType(atStep, GherkinStepsHolder.class);
        if (holder == null) return List.of();

        Set<String> result = new LinkedHashSet<>();
        Set<String> visiting = new HashSet<>();

        for (GherkinStepsHolder background : backgroundHoldersOf(holder)) {
            collectFromHolder(background, null, result, visiting);
        }
        collectFromHolder(holder, atStep, result, visiting);

        return new ArrayList<>(result);
    }

    // -------------------------------------------------------------------------
    // Per-file cache
    // -------------------------------------------------------------------------

    @NotNull
    private Map<GherkinStepsHolder, List<ContextEvent>> getFileEvents(@NotNull GherkinFile file) {
        return CachedValuesManager.getCachedValue(file, () ->
                CachedValueProvider.Result.create(analyzeFile(file), file));
    }

    @NotNull
    private static Map<GherkinStepsHolder, List<ContextEvent>> analyzeFile(@NotNull GherkinFile file) {
        Map<GherkinStepsHolder, List<ContextEvent>> result = new HashMap<>();
        GherkinFeature[] features = file.getFeatures();
        if (features == null) return result;

        for (GherkinFeature feature : features) {
            for (GherkinStepsHolder holder : feature.getScenarios()) {
                result.put(holder, ContextParameterStepAnalyzer.extractEvents(holder));
            }
        }
        return result;
    }

    @NotNull
    private static List<GherkinStepsHolder> backgroundHoldersOf(@NotNull GherkinStepsHolder holder) {
        if (holder instanceof GherkinScenario scenario && scenario.isBackground()) {
            return List.of(); // a Background never has its own preceding Background
        }
        GherkinFile file = containingFileOf(holder);
        if (file == null) return List.of();

        List<GherkinStepsHolder> backgrounds = new ArrayList<>();
        GherkinFeature[] features = file.getFeatures();
        if (features == null) return backgrounds;
        for (GherkinFeature feature : features) {
            for (GherkinStepsHolder candidate : feature.getScenarios()) {
                if (candidate instanceof GherkinScenario s && s.isBackground()) backgrounds.add(candidate);
            }
        }
        return backgrounds;
    }

    private static @Nullable GherkinFile containingFileOf(@NotNull GherkinStepsHolder holder) {
        return holder.getContainingFile() instanceof GherkinFile file ? file : null;
    }

    // -------------------------------------------------------------------------
    // Recursive expansion
    // -------------------------------------------------------------------------

    /**
     * Adds every param established by {@code holder}'s own events, stopping before {@code
     * stopBeforeStep} ({@code null} = include the whole holder), plus - if {@code holder} is a
     * {@code @pumpkin} scenario - its own scenario-name {@code {placeholder}} names from the start.
     */
    private void collectFromHolder(@NotNull GherkinStepsHolder holder, @Nullable GherkinStep stopBeforeStep,
                                   @NotNull Set<String> result, @NotNull Set<String> visiting) {
        if (holder instanceof GherkinScenario scenario && GherkinPsiUtil.isPumpkinScenario(scenario)) {
            String name = scenario.getScenarioName();
            if (name != null) result.addAll(ContextParameterStepAnalyzer.scenarioNameVariables(name));
        }

        GherkinFile file = containingFileOf(holder);
        if (file == null) return;
        List<ContextEvent> events = getFileEvents(file).getOrDefault(holder, List.of());

        // Compared by document offset, not by identity with stopBeforeStep itself: most steps
        // don't establish anything and so never appear in `events` at all (e.g. the very step the
        // user is typing %... into usually isn't a store/DB/process/API step) - an identity check
        // would then never match and fail to stop the walk before steps that come *after* the
        // cursor.
        int stopOffset = stopBeforeStep == null ? Integer.MAX_VALUE : stopBeforeStep.getTextOffset();

        for (ContextEvent event : events) {
            if (event.step().getTextOffset() >= stopOffset) break;

            if (event instanceof ContextEvent.DirectContextEvent direct) {
                result.addAll(direct.paramNames());
            } else if (event instanceof ContextEvent.ProcessInvocationEvent invocation) {
                result.addAll(invocation.callSiteFieldNames());
                expandProcessInvocation(invocation, result, visiting);
            }
        }
    }

    private void expandProcessInvocation(@NotNull ContextEvent.ProcessInvocationEvent invocation,
                                         @NotNull Set<String> result, @NotNull Set<String> visiting) {
        if (invocation.invocationText().isBlank()) return;

        List<PumpkinProcessDefinition> matches = PumpkinProcessService.getInstance(project)
                .findMatchingProcesses(invocation.invocationText(), invocation.requestedContextName());
        if (matches.size() != 1) return; // ambiguous or unresolved - nothing more we can safely add

        PumpkinProcessDefinition target = matches.get(0);
        String key = target.getFeatureFile().getPath() + "::" + target.getScenarioName();
        // Cycle guard, mirroring PumpkinProcessRecursionInspection's own call-graph DFS.
        if (!visiting.add(key)) return;

        try {
            for (PumpkinProcessVariable variable : target.getVariables()) {
                result.add(variable.getName());
            }
            GherkinScenario targetScenario = target.getScenarioPsiElement();
            if (targetScenario != null) {
                for (GherkinStepsHolder background : backgroundHoldersOf(targetScenario)) {
                    collectFromHolder(background, null, result, visiting);
                }
                collectFromHolder(targetScenario, null, result, visiting);
            }
        } finally {
            visiting.remove(key);
        }
    }
}
