package com.pumpkin.intellij.repository;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.SmartPointerManager;
import com.pumpkin.intellij.model.PumpkinProcessDefinition;
import com.pumpkin.intellij.model.PumpkinProcessVariable;
import com.pumpkin.intellij.util.GherkinPsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.cucumber.psi.GherkinFeature;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;
import org.jetbrains.plugins.cucumber.psi.GherkinScenario;
import org.jetbrains.plugins.cucumber.psi.GherkinStepsHolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a {@link GherkinFile} and extracts all {@link PumpkinProcessDefinition}s.
 * Only {@code @pumpkin}-tagged regular Scenarios (not Scenario Outlines) are returned.
 */
public final class PumpkinProcessParser {

    private static final Pattern VARIABLE_SYNTAX = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)}");

    /** Parse a single Gherkin file and return all Pumpkin Process definitions found in it. */
    public @NotNull List<PumpkinProcessDefinition> parse(@NotNull GherkinFile gherkinFile) {
        List<PumpkinProcessDefinition> result = new ArrayList<>();

        GherkinFeature[] features = gherkinFile.getFeatures();
        if (features == null) return result;

        for (GherkinFeature feature : features) {
            String featureName = feature.getFeatureName();

            // GherkinFeature.getScenarios() returns GherkinStepsHolder[] (scenarios + outlines).
            // GherkinScenario and GherkinScenarioOutline are separate interfaces; casting to
            // GherkinScenario filters to regular scenarios only (outlines are excluded by type).
            // Backgrounds carry isBackground()=true and are skipped by isPumpkinScenario().
            for (GherkinStepsHolder holder : feature.getScenarios()) {
                if (!(holder instanceof GherkinScenario scenario)) continue;
                if (!GherkinPsiUtil.isPumpkinScenario(scenario)) continue;

                PumpkinProcessDefinition def = buildDefinition(gherkinFile, featureName, scenario);
                if (def != null) {
                    result.add(def);
                }
            }
        }

        return result;
    }

    private PumpkinProcessDefinition buildDefinition(
            @NotNull GherkinFile gherkinFile,
            String featureName,
            @NotNull GherkinScenario scenario) {

        VirtualFile vf = gherkinFile.getVirtualFile();
        if (vf == null) return null;

        String scenarioName = scenario.getScenarioName();
        if (scenarioName == null || scenarioName.isBlank()) return null;

        int lineNumber = computeLineNumber(vf, scenario.getTextOffset());
        List<PumpkinProcessVariable> variables = extractVariables(scenarioName);
        List<String> requiredParams = GherkinPsiUtil.parseRequiredParameters(scenario);

        var pointer = SmartPointerManager.getInstance(scenario.getProject())
                .createSmartPsiElementPointer(scenario, gherkinFile);

        return new PumpkinProcessDefinition(
                vf,
                featureName,
                scenarioName,
                lineNumber,
                pointer,
                variables,
                requiredParams
        );
    }

    private static int computeLineNumber(@NotNull VirtualFile vf, int textOffset) {
        try {
            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            if (doc != null) {
                return doc.getLineNumber(textOffset) + 1;
            }
        } catch (ProcessCanceledException e) {
            throw e; // must always propagate - see PumpkinProcessService.loadProcesses's doc comment
        } catch (Exception ignored) {}
        return 0;
    }

    static @NotNull List<PumpkinProcessVariable> extractVariables(@NotNull String scenarioName) {
        List<PumpkinProcessVariable> vars = new ArrayList<>();
        Matcher m = VARIABLE_SYNTAX.matcher(scenarioName);
        while (m.find()) {
            vars.add(new PumpkinProcessVariable(m.group(1)));
        }
        return Collections.unmodifiableList(vars);
    }
}
