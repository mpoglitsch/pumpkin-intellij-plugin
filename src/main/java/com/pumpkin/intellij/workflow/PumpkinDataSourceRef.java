package com.pumpkin.intellij.workflow;

import org.jetbrains.annotations.NotNull;

/**
 * Plugin-owned stand-in for a Database-plugin {@code LocalDataSource}, so that type (which only
 * exists when the Database Tools plugin is installed) never has to appear in the signature of
 * {@link WorkflowDataSourceBridge}, the always-loaded seam between Pumpkin and that optional
 * plugin. {@link #id()} is the {@code LocalDataSource}'s own unique ID, stable across IDE
 * restarts - used to persist and re-resolve the user's chosen datasource
 * ({@code PumpkinSettingsState.workflowAssertionDataSourceId}).
 */
public record PumpkinDataSourceRef(@NotNull String id, @NotNull String displayName) {

    @Override
    public String toString() {
        return displayName;
    }
}
