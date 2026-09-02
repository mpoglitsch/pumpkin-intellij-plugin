package com.pumpkin.intellij.api;

import com.intellij.codeInsight.hints.*;
import com.intellij.codeInsight.hints.presentation.InlayPresentation;
import com.intellij.codeInsight.hints.presentation.PresentationFactory;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;

import javax.swing.*;

/**
 * Shows a small rounded tag above each Gherkin step that matches the API send pattern.
 *
 * <p>The tag line contains:
 * <ul>
 *   <li>An API tag with the HTTP method and path resolved from the proxy's endpoint enum.</li>
 *   <li>Optionally, a clickable template-file tag when the endpoint has a body template
 *       defined via {@code @Value("classpath:…")} in the proxy's {@code getBodyTemplate}
 *       switch. Clicking the tag opens the template file in the editor.</li>
 * </ul>
 *
 * <pre>
 *   [🏷 POST /redisJobs/{jobId}/start]  [📄 startJob.json]
 *   When I send a redis job start request to BACKEND_API including these parameters
 * </pre>
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
        return listener -> new JPanel();
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

                // Derive indentation from the step's position in the document.
                int stepOffset = step.getTextOffset();
                int lineStart = editor.getDocument()
                        .getLineStartOffset(editor.getDocument().getLineNumber(stepOffset));
                String indent = editor.getDocument().getText(new TextRange(lineStart, stepOffset));

                PresentationFactory factory = getFactory();

                // Primary tag: [🏷 METHOD /path]
                InlayPresentation apiTag = factory.roundWithBackground(
                        factory.seq(
                                factory.smallScaledIcon(AllIcons.Nodes.Tag),
                                factory.smallText(" " + methodAndPath[0] + " " + methodAndPath[1])
                        )
                );

                // Optional secondary tag: clickable template filename
                InlayPresentation hint = buildHint(factory, indent, apiTag, endpoint, step.getProject());
                sink.addBlockElement(stepOffset, false, true, 0, hint);
                return true;
            }
        };
    }

    /**
     * Assembles the full hint presentation. When a body template is found for the endpoint,
     * a second clickable tag with the filename is appended.
     */
    private static InlayPresentation buildHint(@NotNull PresentationFactory factory,
                                               @NotNull String indent,
                                               @NotNull InlayPresentation apiTag,
                                               @NotNull PsiEnumConstant endpoint,
                                               @NotNull Project project) {
        // Resolve the proxy class: endpoint lives inside an inner enum of the proxy.
        PsiClass innerEnum = endpoint.getContainingClass();
        PsiClass proxyClass = innerEnum != null ? innerEnum.getContainingClass() : null;

        VirtualFile templateFile = proxyClass != null
                ? ApiEndpointResolver.findTemplateVirtualFile(proxyClass, endpoint, project)
                : null;

        if (templateFile == null) {
            return factory.seq(factory.text(indent), apiTag);
        }

        // Template tag: [📄 filename.json] — clicking opens the file.
        String filename = templateFile.getName();
        InlayPresentation fileLabel = factory.roundWithBackground(
                factory.seq(
                        factory.smallScaledIcon(AllIcons.FileTypes.Text),
                        factory.smallText(" " + filename)
                )
        );
        InlayPresentation fileTag = factory.referenceOnHover(
                fileLabel,
                (event, point) -> FileEditorManager.getInstance(project).openFile(templateFile, true)
        );

        return factory.seq(
                factory.text(indent),
                apiTag,
                factory.smallText("   "),
                fileTag
        );
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

        String method = null;
        if (args[0] instanceof PsiReferenceExpression ref) {
            method = ref.getReferenceName();
        }

        String path = null;
        if (args[1] instanceof PsiLiteralExpression lit) {
            Object value = lit.getValue();
            if (value instanceof String s) path = s;
        }

        return (method != null && path != null) ? new String[]{method, path} : null;
    }
}
