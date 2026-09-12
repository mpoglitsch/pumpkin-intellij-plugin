package com.pumpkin.intellij.api;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Warns on a generated {@code auth:} step (see {@link AuthStepGenerator}) whenever its API's
 * proxy class overrides {@code getApiAuthenticationProvider()} itself.
 *
 * <p>{@code AbstractApiProxy.getApiAuthenticationProvider()} is what actually picks the provider
 * at runtime: it normally reads the one selected by an {@code I use authentication ... for ...}
 * step and only falls back to {@code getDefaultApiAuthenticationProvider()} (the override the
 * "Add API Proxy" generator emits, see {@code ApiProxyCodeGenerator}) if the scenario didn't pick
 * one. A proxy that overrides {@code getApiAuthenticationProvider()} itself replaces that whole
 * mechanism, so any {@code auth:} step naming a provider for that API has no effect - silently,
 * since nothing about the step or the proxy looks wrong on its own. That's the failure mode this
 * annotator surfaces as a warning directly on the step.
 */
public class AuthStepAnnotator implements Annotator {

    private static final Logger LOG = Logger.getInstance(AuthStepAnnotator.class);

    private static final String OVERRIDING_METHOD_NAME = "getApiAuthenticationProvider";

    // Matches the step AuthStepGenerator produces: "I use authentication <name> for <API> in
    // this scenario". Dot does not match \n (no DOTALL), so it stays on the step's own line.
    private static final Pattern AUTH_STEP_PATTERN = Pattern.compile(
            "use\\s+authentication\\s+(.+?)\\s+for\\s+([A-Z_][A-Z0-9_]*)\\s+in\\s+this\\s+scenario",
            Pattern.CASE_INSENSITIVE);

    /**
     * Catches broadly (except {@link ProcessCanceledException}, which must always propagate) for
     * the same reason {@code PumpkinProcessAnnotator} does: an {@link Annotator} that lets an
     * exception escape gets silently disabled by the platform for the rest of the session.
     */
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        try {
            doAnnotate(element, holder);
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (Throwable t) {
            LOG.warn("Pumpkin auth-step annotation failed for " + element, t);
        }
    }

    private void doAnnotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof GherkinStep step)) return;

        Matcher m = AUTH_STEP_PATTERN.matcher(step.getText());
        if (!m.find()) return;
        String apiNotation = m.group(2);

        PsiClass proxy = findProxyForNotation(step.getProject(), apiNotation);
        if (proxy == null) return;
        if (proxy.findMethodsByName(OVERRIDING_METHOD_NAME, false).length == 0) return;

        holder.newAnnotation(HighlightSeverity.WARNING,
                        "Authentication provider selection has no effect: " + proxy.getName()
                                + " overrides " + OVERRIDING_METHOD_NAME + "(), so this step's "
                                + "provider will never be used")
                .create();
    }

    @Nullable
    private static PsiClass findProxyForNotation(@NotNull Project project, @NotNull String apiNotation) {
        for (PsiClass proxy : ApiEndpointResolver.findAllProxyClasses(project)) {
            String notation = ApiEndpointResolver.apiNotationOf(proxy);
            if (apiNotation.equalsIgnoreCase(notation)) return proxy;
        }
        return null;
    }
}
