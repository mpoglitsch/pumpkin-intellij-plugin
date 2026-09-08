package com.pumpkin.intellij.actions;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.toolbar.floating.AbstractFloatingToolbarProvider;
import com.intellij.openapi.editor.toolbar.floating.FloatingToolbarComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;

/**
 * Shows the {@code Pumpkin.FloatingToolbar} action group (a single pumpkin-icon button with a
 * popup menu, currently just "Add API Endpoint...") docked over the top-right of the editor
 * while a Gherkin feature file is open.
 *
 * <p>The platform's {@code EditorFloatingToolbar} only ever calls {@code scheduleShow()} from a
 * mouse-move listener, and only when {@code autoHideable} is {@code true} — so this must NOT
 * override {@code autoHideable} to {@code false} (that would disable the only built-in trigger
 * and the button would never appear at all). Instead {@link #register} calls
 * {@code scheduleShow()} once up front so the button is visible immediately on opening a
 * feature file, without requiring the user to move the mouse first; a longer
 * {@link #getRetentionTime()} keeps it up long enough to be noticed before the normal
 * mouse-move-driven show/hide behavior takes over.
 */
public class PumpkinFloatingToolbarProvider extends AbstractFloatingToolbarProvider {

    public PumpkinFloatingToolbarProvider() {
        super("Pumpkin.FloatingToolbar");
    }

    @Override
    public boolean isApplicable(@NotNull DataContext dataContext) {
        return CommonDataKeys.PSI_FILE.getData(dataContext) instanceof GherkinFile;
    }

    @Override
    public int getRetentionTime() {
        return 5000;
    }

    /**
     * Explicitly implemented rather than left to {@code AbstractFloatingToolbarProvider}'s own
     * default: on at least one real IntelliJ build (reported on Windows against a build within
     * the plugin's declared {@code pluginSinceBuild}-{@code pluginUntilBuild} range), that base
     * class does not supply an implementation of this method at all, and the platform fails to
     * even instantiate the extension point with {@code PluginException: ... does not define or
     * inherit an implementation of ... getAutoHideable()}. Implementing it here removes any
     * dependency on what a given platform build's base class happens to provide. Must be {@code
     * true} - see the class doc above for why {@code false} would hide the button entirely.
     */
    @Override
    public boolean getAutoHideable() {
        return true;
    }

    @Override
    public void register(@NotNull DataContext dataContext, @NotNull FloatingToolbarComponent component,
                         @NotNull Disposable parentDisposable) {
        component.scheduleShow();
    }
}
