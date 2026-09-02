package com.pumpkin.intellij.model;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinScenario;

import java.util.List;

/**
 * Represents a Pumpkin Process definition: a {@code @pumpkin}-tagged Gherkin Scenario
 * located in one of the configured process directories.
 */
public final class PumpkinProcessDefinition {

    private final @NotNull VirtualFile featureFile;
    private final @Nullable String featureName;
    private final @NotNull String scenarioName;
    private final int scenarioLine;
    private final @NotNull SmartPsiElementPointer<GherkinScenario> scenarioPointer;
    private final @NotNull List<PumpkinProcessVariable> variables;
    private final @NotNull List<String> requiredParameters;

    public PumpkinProcessDefinition(
            @NotNull VirtualFile featureFile,
            @Nullable String featureName,
            @NotNull String scenarioName,
            int scenarioLine,
            @NotNull SmartPsiElementPointer<GherkinScenario> scenarioPointer,
            @NotNull List<PumpkinProcessVariable> variables,
            @NotNull List<String> requiredParameters) {
        this.featureFile = featureFile;
        this.featureName = featureName;
        this.scenarioName = scenarioName;
        this.scenarioLine = scenarioLine;
        this.scenarioPointer = scenarioPointer;
        this.variables = variables;
        this.requiredParameters = requiredParameters;
    }

    /** The process name – the scenario title, which may contain {@code {variable}} placeholders. */
    public @NotNull String getProcessName() {
        return scenarioName;
    }

    public @NotNull VirtualFile getFeatureFile() { return featureFile; }
    public @Nullable String getFeatureName() { return featureName; }
    public @NotNull String getScenarioName() { return scenarioName; }
    public int getScenarioLine() { return scenarioLine; }
    public @NotNull List<PumpkinProcessVariable> getVariables() { return variables; }
    public @NotNull List<String> getRequiredParameters() { return requiredParameters; }

    public @NotNull String getFeatureFileName() { return featureFile.getName(); }

    /**
     * Returns the live PSI element for the scenario, or {@code null} if it has been invalidated
     * (e.g. the file was deleted after this definition was cached).
     */
    public @Nullable GherkinScenario getScenarioPsiElement() {
        return scenarioPointer.getElement();
    }

    @Override
    public String toString() {
        return "PumpkinProcessDefinition{" +
                "scenarioName='" + scenarioName + '\'' +
                ", file=" + featureFile.getName() +
                ", line=" + scenarioLine +
                '}';
    }
}
