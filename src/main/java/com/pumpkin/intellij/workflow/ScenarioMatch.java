package com.pumpkin.intellij.workflow;

import org.jetbrains.annotations.NotNull;

/** One row of the "which scenarios does this workflow ID belong to" lookup. */
public record ScenarioMatch(long scenarioId, @NotNull String scenarioName) {
}
