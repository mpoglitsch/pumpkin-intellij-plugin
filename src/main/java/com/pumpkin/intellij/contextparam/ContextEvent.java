package com.pumpkin.intellij.contextparam;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;

import java.util.List;

/**
 * One step's contribution to the context-parameter set available to later steps in the same
 * scenario - see {@link ContextParameterStepAnalyzer}. A {@link ProcessInvocationEvent} is kept
 * unresolved (just the raw invocation text) rather than eagerly recursed into, so a file's own
 * cached event list never depends on any *other* file's content - see {@link
 * PumpkinContextParameterService} for where the recursive expansion actually happens.
 */
sealed interface ContextEvent permits ContextEvent.DirectContextEvent, ContextEvent.ProcessInvocationEvent {

    @NotNull GherkinStep step();

    record DirectContextEvent(@NotNull GherkinStep step, @NotNull List<String> paramNames) implements ContextEvent {
    }

    record ProcessInvocationEvent(@NotNull GherkinStep step, @NotNull List<String> callSiteFieldNames,
                                  @NotNull String invocationText,
                                  @Nullable String requestedContextName) implements ContextEvent {
    }
}
