package com.pumpkin.intellij.api;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.pumpkin.intellij.endpoint.EndpointCodeGenerator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads back which parameters an endpoint requires, from three sources:
 *
 * <ul>
 *   <li><b>Path variables</b> - always required, read directly from the endpoint enum constant's
 *       own path argument (e.g. {@code CONSTANT(Method.GET, "/orders/{orderId}", null)} - see
 *       {@code EnumConstantEditor}), the same way {@code EndpointCodeGenerator} itself derives
 *       them at generation time. Not read from {@code collectPathParams}'s generated body - that
 *       would just be reverse-engineering the very code generated from this same path string.</li>
 *   <li><b>Required query parameters</b> - marked required in the "Add API Endpoint" dialog, the
 *       only place this is recorded; read back from {@code collectQueryParams}'s switch-case body,
 *       encoded there as a {@code copyRequiredParamToMap(...)} call (vs. {@code
 *       copyParamToMapIfPresent(...)} for optional ones, which are deliberately ignored here).</li>
 *   <li><b>Body template variables without a default</b> - every {@code %name%} placeholder in the
 *       endpoint's JSON body template (see {@link ApiEndpointResolver#findTemplateVirtualFile})
 *       that has no {@code %name:default%} (or {@code %name::default%}) suffix.</li>
 * </ul>
 */
final class ApiEndpointParameters {

    /** A placeholder with no colon suffix at all (e.g. {@code %houseNumber%}) has no default. */
    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("%([a-zA-Z_][a-zA-Z0-9_]*)(:[^%]*)?%");

    private ApiEndpointParameters() {}

    /** Path variables first (in path order), then required query params, then template variables. */
    static @NotNull List<String> findRequired(@NotNull Project project, @NotNull PsiClass proxy,
                                              @NotNull PsiEnumConstant endpoint) {
        List<String> required = new ArrayList<>();
        required.addAll(EndpointCodeGenerator.extractPathVariables(pathArgumentOf(endpoint)));
        required.addAll(findCallArguments(proxy, "collectQueryParams", endpoint.getName(), "copyRequiredParamToMap"));
        required.addAll(findBodyTemplateVariablesWithoutDefault(project, proxy, endpoint));
        // De-dup while preserving order, in case the same name appears via more than one source.
        return new ArrayList<>(new LinkedHashSet<>(required));
    }

    /** The path string from {@code CONSTANT(Method.X, "<path>", ...)}, or {@code ""} if it can't be resolved. */
    private static @NotNull String pathArgumentOf(@NotNull PsiEnumConstant endpoint) {
        PsiExpressionList args = endpoint.getArgumentList();
        if (args == null) return "";
        PsiExpression[] expressions = args.getExpressions();
        if (expressions.length < 2) return "";
        String path = resolveStringConstant(expressions[1]);
        return path != null ? path : "";
    }

    private static @NotNull List<String> findBodyTemplateVariablesWithoutDefault(
            @NotNull Project project, @NotNull PsiClass proxy, @NotNull PsiEnumConstant endpoint) {
        VirtualFile templateFile = ApiEndpointResolver.findTemplateVirtualFile(proxy, endpoint, project);
        if (templateFile == null) return List.of();

        String content;
        try {
            content = VfsUtil.loadText(templateFile);
        } catch (IOException e) {
            return List.of();
        }

        List<String> names = new ArrayList<>();
        Matcher m = TEMPLATE_VARIABLE.matcher(content);
        while (m.find()) {
            if (m.group(2) == null) { // no ":default" suffix - no default value, so it's required
                names.add(m.group(1));
            }
        }
        return names;
    }

    private static @NotNull List<String> findCallArguments(@NotNull PsiClass proxy, @NotNull String methodName,
                                                            @NotNull String endpointConstantName,
                                                            @NotNull String calledMethodName) {
        List<String> names = new ArrayList<>();
        PsiMethod[] methods = proxy.findMethodsByName(methodName, false);
        if (methods.length == 0) return names;

        PsiCodeBlock body = methods[0].getBody();
        if (body == null) return names;
        PsiSwitchBlock switchBlock = PsiTreeUtil.findChildOfType(body, PsiSwitchBlock.class);
        if (switchBlock == null) return names;
        PsiCodeBlock switchBody = switchBlock.getBody();
        if (switchBody == null) return names;

        for (PsiElement caseElement : statementsForCase(switchBody, endpointConstantName)) {
            for (PsiMethodCallExpression call :
                    PsiTreeUtil.findChildrenOfType(caseElement, PsiMethodCallExpression.class)) {
                if (!calledMethodName.equals(call.getMethodExpression().getReferenceName())) continue;
                PsiExpression[] args = call.getArgumentList().getExpressions();
                if (args.length == 0) continue;
                String name = resolveStringConstant(args[0]);
                if (name != null) names.add(name);
            }
        }
        return names;
    }

    /**
     * Resolves {@code expr} to a compile-time constant {@code String}, whether it's written as a
     * plain literal ({@code "name"}) or as a reference to a {@code public static final String}
     * constant declared elsewhere (e.g. {@code SomeConstants.MY_NAME}, in any class) - proxies
     * commonly pull parameter/path names from a shared constants class rather than repeating
     * string literals. Returns {@code null} if {@code expr} isn't a resolvable String constant.
     */
    private static @Nullable String resolveStringConstant(@NotNull PsiExpression expr) {
        Object value = JavaPsiFacade.getInstance(expr.getProject())
                .getConstantEvaluationHelper()
                .computeConstantExpression(expr);
        return value instanceof String s ? s : null;
    }

    /**
     * Returns every statement/rule-body belonging to the {@code case <constantName>:}/{@code
     * case <constantName> ->} label in {@code switchBody} - handling both traditional
     * (fall-through, collecting statements up to the next label) and enhanced switch styles,
     * mirroring {@code ApiEndpointResolver.findTemplateVirtualFile}'s own case-scanning.
     */
    private static @NotNull List<PsiElement> statementsForCase(@NotNull PsiCodeBlock switchBody,
                                                                @NotNull String constantName) {
        List<PsiElement> result = new ArrayList<>();
        boolean inTargetCase = false;

        for (PsiStatement stmt : switchBody.getStatements()) {
            if (stmt instanceof PsiSwitchLabeledRuleStatement rule) {
                inTargetCase = false;
                if (isCaseLabelForConstant(rule, constantName)) {
                    PsiStatement ruleBody = rule.getBody();
                    if (ruleBody != null) result.add(ruleBody);
                }
            } else if (stmt instanceof PsiSwitchLabelStatement label) {
                inTargetCase = !label.isDefaultCase() && isCaseLabelForConstant(label, constantName);
            } else if (inTargetCase) {
                result.add(stmt);
            }
        }
        return result;
    }

    private static boolean isCaseLabelForConstant(@NotNull PsiStatement stmt, @NotNull String constantName) {
        for (PsiReferenceExpression ref : PsiTreeUtil.findChildrenOfType(stmt, PsiReferenceExpression.class)) {
            if (constantName.equals(ref.getReferenceName())) return true;
        }
        return false;
    }
}
