package com.pumpkin.intellij.settings;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent per-project settings for the Pumpkin plugin.
 * Stored in {@code .idea/pumpkin.xml}.
 */
@State(
        name = "PumpkinSettings",
        storages = @Storage("pumpkin.xml")
)
@Service(Service.Level.PROJECT)
public final class PumpkinSettingsState implements PersistentStateComponent<PumpkinSettingsState> {

    /** Paths to the process directories. May be absolute or relative to the project base. */
    public List<String> processDirectories = new ArrayList<>(
            List.of("src/test/resources/features")
    );

    /** Whether {@code # Section: …} blocks are collapsed when a feature file is first opened. */
    public boolean collapseSectionsByDefault = true;

    public static @NotNull PumpkinSettingsState getInstance(@NotNull Project project) {
        return project.getService(PumpkinSettingsState.class);
    }

    @Override
    public @Nullable PumpkinSettingsState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull PumpkinSettingsState state) {
        XmlSerializerUtil.copyBean(state, this);
    }

    public @NotNull List<String> getProcessDirectories() {
        return processDirectories;
    }

    public void setProcessDirectories(@NotNull List<String> directories) {
        this.processDirectories = new ArrayList<>(directories);
    }
}
