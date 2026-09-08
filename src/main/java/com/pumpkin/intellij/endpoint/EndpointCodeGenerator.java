package com.pumpkin.intellij.endpoint;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.pumpkin.intellij.api.ApiEndpointResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orchestrates the six code-generation steps for one "Add API Endpoint" operation, all inside a
 * single {@link WriteCommandAction} so the whole thing is one undo step.
 *
 * <p>{@code Optional}, {@code Map}/{@code HashMap}, {@code ApiEndpoint} and
 * {@code ApiRequestDefinition} are assumed to already be visible in the proxy file — every
 * proxy overrides other {@code AbstractApiProxy} methods that already require them, so a brand
 * new proxy (the only case this feature doesn't support — see {@link #findEndpointEnum}) would
 * be the only way to lack them.
 */
public final class EndpointCodeGenerator {

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)}");

    private EndpointCodeGenerator() {}

    public static void generate(@NotNull Project project, @NotNull NewEndpointSpec spec) {
        PsiClass proxyClass = spec.proxyClass();
        PsiClass endpointEnum = findEndpointEnum(proxyClass);
        if (endpointEnum == null) {
            throw new IllegalStateException(
                    "Selected proxy has no endpoint enum yet — this action only adds to an existing one.");
        }

        String enumSimpleName = endpointEnum.getName();
        String constantName = NameUtils.toUpperSnakeCase(spec.endpointName());
        String camelName = NameUtils.toCamelCase(spec.endpointName());
        String apiNotation = ApiEndpointResolver.apiNotationOf(proxyClass);
        String apiNameKebab = NameUtils.toKebabCase(apiNotation != null ? apiNotation : proxyClass.getName());
        List<String> pathVariables = extractPathVariables(spec.path());

        WriteCommandAction.runWriteCommandAction(project, "Add API Endpoint", null, () -> {
            SmartPsiElementPointer<PsiClass> proxyPtr = SmartPointerManager.createPointer(proxyClass);

            EnumConstantEditor.appendConstant(project, endpointEnum, constantName, spec.httpMethod(), spec.path());

            if (!pathVariables.isEmpty()) {
                PathParamsEditor.generate(project, proxyPtr, enumSimpleName, constantName, pathVariables);
            }
            if (!spec.queryParams().isEmpty()) {
                QueryParamsEditor.generate(project, proxyPtr, enumSimpleName, constantName, spec.queryParams());
            }
            String bodyJson = spec.bodyJson();
            if (bodyJson != null && !bodyJson.isBlank()) {
                BodyTemplateEditor.generate(project, proxyPtr, enumSimpleName, constantName, camelName, apiNameKebab, bodyJson);
            }
            if (!spec.headers().isEmpty()) {
                HeadersEditor.generate(project, proxyPtr, spec.headers());
            }
            if (spec.status().code() != HttpStatusOption.OK.code()) {
                ResponseCodeEditor.generate(project, proxyPtr, enumSimpleName, constantName, spec.status());
            }
        }, proxyClass.getContainingFile());
    }

    /** The proxy's first direct inner enum — the assumed shape of its endpoint enum. */
    static @Nullable PsiClass findEndpointEnum(@NotNull PsiClass proxyClass) {
        for (PsiClass inner : proxyClass.getInnerClasses()) {
            if (inner.isEnum()) return inner;
        }
        return null;
    }

    /** Also used by {@code com.pumpkin.intellij.api.ApiEndpointParameters} to read path variables back. */
    public static @NotNull List<String> extractPathVariables(@NotNull String path) {
        List<String> variables = new ArrayList<>();
        Matcher m = PATH_VARIABLE.matcher(path);
        while (m.find()) {
            variables.add(m.group(1));
        }
        return variables;
    }
}
