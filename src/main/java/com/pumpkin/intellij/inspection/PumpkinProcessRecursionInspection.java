package com.pumpkin.intellij.inspection;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.pumpkin.intellij.model.PumpkinProcessDefinition;
import com.pumpkin.intellij.repository.PumpkinProcessService;
import com.pumpkin.intellij.util.GherkinPsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinScenario;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flags a {@code Process: ...} step that would recurse forever at runtime - calling the Process
 * that is already running, either directly or through a chain of other Processes.
 *
 * <p>Mirrors the recursion guard in the real test project's own {@code ProcessExecutor}, which
 * rejects such a call at runtime with a similar chain in its error message (each Process call runs
 * as its own nested Cucumber-JVM run, so a cycle never naturally terminates - it eventually dies
 * with a {@code StackOverflowError} buried deep in framework internals). This inspection exists so
 * the cycle is visible directly in the editor, before a test run ever reaches it.
 */
public class PumpkinProcessRecursionInspection extends LocalInspectionTool {

    @Override
    public @NotNull String getGroupDisplayName() {
        return "Pumpkin";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Pumpkin Process recursion";
    }

    @Override
    public @NotNull String getShortName() {
        return "PumpkinProcessRecursion";
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new PsiElementVisitor() {
            // Built lazily on the first Process step seen, then reused for every other step in
            // this same file-analysis pass - the call graph is project-wide, so there's no reason
            // to recompute it once per step.
            private CallGraph graph;

            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (!(element instanceof GherkinStep step)) return;
                if (!GherkinPsiUtil.isProcessStep(step)) return;

                GherkinScenario enclosing = GherkinPsiUtil.findEnclosingScenario(step);
                // Recursion can only happen through a chain of Process definitions - a plain test
                // scenario invoking a (possibly cyclic) Process isn't itself part of the cycle; the
                // cycle is still reported, just on the Process steps that actually form it.
                if (enclosing == null || !GherkinPsiUtil.isPumpkinScenario(enclosing)) return;

                if (graph == null) {
                    graph = CallGraph.build(PumpkinProcessService.getInstance(step.getProject()));
                }

                List<PumpkinProcessDefinition> cycle = graph.cycleStartingAt(enclosing, step);
                if (cycle == null) return;

                holder.registerProblem(step, buildMessage(cycle), ProblemHighlightType.GENERIC_ERROR);
            }
        };
    }

    private static @NotNull String buildMessage(@NotNull List<PumpkinProcessDefinition> cycle) {
        StringBuilder sb = new StringBuilder("Pumpkin Process recursion - this call would never terminate: ");
        for (PumpkinProcessDefinition def : cycle) {
            sb.append(def.getProcessName()).append(" → ");
        }
        sb.append(cycle.get(0).getProcessName());
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Call graph
    // -------------------------------------------------------------------------

    /**
     * A directed graph over Process definitions: an edge from Process D to Process T means D has
     * a step invoking T. Built fresh from {@link PumpkinProcessService#getProcesses()} each time
     * {@link #build} is called (once per file-analysis pass, see the visitor above) - cheap enough
     * that a dedicated persistent cache isn't worth the extra invalidation surface.
     */
    private static final class CallGraph {
        private final @NotNull Map<String, PumpkinProcessDefinition> definitionsByKey;
        /** Node key -> the keys of every Process it (directly) invokes, one entry per invoking step. */
        private final @NotNull Map<String, List<String>> edges;
        /** Each individual step's own resolved target keys - a subset of its definition's edges. */
        private final @NotNull Map<GherkinStep, List<String>> targetsByStep;

        private CallGraph(@NotNull Map<String, PumpkinProcessDefinition> definitionsByKey,
                          @NotNull Map<String, List<String>> edges,
                          @NotNull Map<GherkinStep, List<String>> targetsByStep) {
            this.definitionsByKey = definitionsByKey;
            this.edges = edges;
            this.targetsByStep = targetsByStep;
        }

        static @NotNull CallGraph build(@NotNull PumpkinProcessService service) {
            List<PumpkinProcessDefinition> definitions = service.getProcesses();

            Map<String, PumpkinProcessDefinition> definitionsByKey = new HashMap<>();
            for (PumpkinProcessDefinition def : definitions) {
                definitionsByKey.put(keyOf(def), def);
            }

            Map<String, List<String>> edges = new HashMap<>();
            Map<GherkinStep, List<String>> targetsByStep = new HashMap<>();

            for (PumpkinProcessDefinition def : definitions) {
                GherkinScenario scenario = def.getScenarioPsiElement();
                if (scenario == null) continue; // stale pointer - file changed since the cache was built

                String defKey = keyOf(def);
                List<String> defTargets = new ArrayList<>();

                for (GherkinStep step : PsiTreeUtil.findChildrenOfType(scenario, GherkinStep.class)) {
                    if (!GherkinPsiUtil.isProcessStep(step)) continue;
                    String invocation = GherkinPsiUtil.getProcessInvocationText(step);
                    if (invocation == null) continue;

                    List<String> stepTargets = new ArrayList<>();
                    for (PumpkinProcessDefinition target : service.findMatchingProcesses(invocation)) {
                        stepTargets.add(keyOf(target));
                    }
                    if (!stepTargets.isEmpty()) {
                        targetsByStep.put(step, stepTargets);
                        defTargets.addAll(stepTargets);
                    }
                }
                edges.put(defKey, defTargets);
            }

            return new CallGraph(definitionsByKey, edges, targetsByStep);
        }

        /**
         * If {@code step} (declared inside {@code enclosing}) resolves to a Process that can reach
         * back to {@code enclosing} again through the call graph, returns the full cycle as an
         * ordered list of definitions starting (and, once the caller appends {@code cycle.get(0)}
         * once more, ending) with {@code enclosing}. Returns {@code null} if this step doesn't
         * create a cycle.
         */
        @Nullable List<PumpkinProcessDefinition> cycleStartingAt(@NotNull GherkinScenario enclosing,
                                                                  @NotNull GherkinStep step) {
            String sourceKey = keyOf(enclosing);
            if (sourceKey == null || !definitionsByKey.containsKey(sourceKey)) return null;

            List<String> stepTargets = targetsByStep.get(step);
            if (stepTargets == null) return null;

            for (String targetKey : stepTargets) {
                List<String> path = findPath(targetKey, sourceKey, new LinkedHashSet<>());
                if (path == null) continue;

                List<PumpkinProcessDefinition> cycle = new ArrayList<>();
                cycle.add(definitionsByKey.get(sourceKey));
                for (String key : path) {
                    PumpkinProcessDefinition def = definitionsByKey.get(key);
                    if (def != null) cycle.add(def);
                }
                return cycle;
            }
            return null;
        }

        /** Depth-first search for a path from {@code from} to {@code to}, following {@link #edges}. */
        private @Nullable List<String> findPath(@NotNull String from, @NotNull String to, @NotNull Set<String> visiting) {
            if (from.equals(to)) {
                List<String> path = new ArrayList<>();
                path.add(from);
                return path;
            }
            if (!visiting.add(from)) return null; // already exploring this node on this path - dead end

            for (String next : edges.getOrDefault(from, List.of())) {
                List<String> path = findPath(next, to, visiting);
                if (path != null) {
                    path.add(0, from);
                    return path;
                }
            }
            return null;
        }

        private static @NotNull String keyOf(@NotNull PumpkinProcessDefinition def) {
            return def.getFeatureFile().getPath() + "::" + def.getScenarioName();
        }

        /**
         * Same key convention as {@link #keyOf(PumpkinProcessDefinition)}, computed directly from
         * PSI/VFS instead of a cached definition - lets a freshly-encountered {@link GherkinScenario}
         * (from the file currently being inspected) be matched against the cached graph even if the
         * service's own cache hasn't been rebuilt since the last edit.
         */
        private static @Nullable String keyOf(@NotNull GherkinScenario scenario) {
            PsiFile file = scenario.getContainingFile();
            if (file == null) return null;
            VirtualFile vf = file.getVirtualFile();
            if (vf == null) return null;
            String name = scenario.getScenarioName();
            if (name == null) return null;
            return vf.getPath() + "::" + name;
        }
    }
}
