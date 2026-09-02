package com.pumpkin.intellij.api;

import com.intellij.codeInsight.hints.*;
import com.intellij.codeInsight.hints.presentation.InlayPresentation;
import com.intellij.codeInsight.hints.presentation.PresentationFactory;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;

import javax.swing.*;

/**
 * Shows a small rounded tag above each Gherkin step that matches the API send pattern,
 * displaying the HTTP method and path resolved from the proxy's endpoint enum:
 *
 * <pre>
 *   [tag icon] POST /redisJobs/{jobId}/start
 *   When I send a redis job start request to BACKEND_API including these parameters
 * </pre>
 *
 * <p>Enabled by default; can be toggled in Settings → Editor → Inlay Hints.
 */
public class ApiInlayHintsProvider implements InlayHintsProvider<NoSettings> {

    private static final SettingsKey<NoSettings> KEY =
            new SettingsKey<>("pumpkin.api.endpoint.hints");

    @NotNull
    @Override
    public SettingsKey<NoSettings> getKey() { return KEY; }

    @NotNull
    @Override
    public String getName() { return "API endpoint"; }

    @NotNull
    @Override
    public String getPreviewText() {
        return "When I send a redis job start request to BACKEND_API without parameters";
    }

    @NotNull
    @Override
    public ImmediateConfigurable createConfigurable(@NotNull NoSettings settings) {
        return listener -> new JPanel(); // no per-provider settings UI
    }

    @NotNull
    @Override
    public NoSettings createSettings() { return new NoSettings(); }

    @Override
    public boolean isVisibleInSettings() { return true; }

    @Nullable
    @Override
    public InlayHintsCollector getCollectorFor(@NotNull PsiFile file,
                                               @NotNull Editor editor,
                                               @NotNull NoSettings settings,
                                               @NotNull InlayHintsSink sink) {
        if (!(file instanceof GherkinFile)) return null;
        return new FactoryInlayHintsCollector(editor) {
            @Override
            public boolean collect(@NotNull PsiElement element,
                                   @NotNull Editor editor,
                                   @NotNull InlayHintsSink sink) {
                if (!(element instanceof GherkinStep step)) return true;
                if (DumbService.isDumb(step.getProject())) return true;

                ApiStepPattern.ParsedApiStep parsed = ApiStepPattern.parse(step);
                if (parsed == null) return true;

                PsiEnumConstant endpoint = ApiEndpointResolver.resolve(
                        step.getProject(),
                        parsed.getApiNotation(),
                        parsed.getApiRequestDefinition());
                if (endpoint == null) return true;

                String[] methodAndPath = extractMethodAndPath(endpoint);
                if (methodAndPath == null) return true;

                PresentationFactory factory = getFactory();
                InlayPresentation hint = factory.roundWithBackground(
                        factory.seq(
                                factory.smallScaledIcon(AllIcons.Nodes.Tag),
                                factory.smallText(" " + methodAndPath[0] + " " + methodAndPath[1])
                        )
                );

                sink.addBlockElement(step.getTextOffset(), false, true, 0, hint);
                return true;
            }
        };
    }

    /**
     * Extracts the HTTP method name and path from the endpoint enum constant's
     * constructor arguments: {@code CONSTANT(Method.POST, "/some/path", ...)}.
     *
     * @return {@code [method, path]} or {@code null} if the arguments cannot be read
     */
    @Nullable
    private static String[] extractMethodAndPath(@NotNull PsiEnumConstant constant) {
        PsiExpressionList argList = constant.getArgumentList();
        if (argList == null) return null;
        PsiExpression[] args = argList.getExpressions();
        if (args.length < 2) return null;

        // First arg: Method.POST or Method.GET etc. — take the reference name.
        String method = null;
        if (args[0] instanceof PsiReferenceExpression ref) {
            method = ref.getReferenceName();
        }

        // Second arg: a string literal "/some/path".
        String path = null;
        if (args[1] instanceof PsiLiteralExpression lit) {
            Object value = lit.getValue();
            if (value instanceof String s) path = s;
        }

        return (method != null && path != null) ? new String[]{method, path} : null;
    }
}
