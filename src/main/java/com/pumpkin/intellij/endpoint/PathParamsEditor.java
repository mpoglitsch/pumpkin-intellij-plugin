package com.pumpkin.intellij.endpoint;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Step 2: {@code collectPathParams} — one required put() per {@code {pathVariable}}. */
final class PathParamsEditor {

    private static final String METHOD_NAME = "collectPathParams";
    private static final String DEFAULT_MAP_VAR = "paramMap";

    private PathParamsEditor() {}

    static void generate(@NotNull Project project, @NotNull SmartPsiElementPointer<PsiClass> proxyPtr,
                         @NotNull String enumSimpleName, @NotNull String constantName,
                         @NotNull List<String> pathVariables) {
        PsiClass proxyClass = SwitchMethodEditor.requireValid(proxyPtr);
        PsiMethod existing = SwitchMethodEditor.findMethod(proxyClass, METHOD_NAME);
        String mapVar = existing != null ? SwitchMethodEditor.findMapVariableName(existing) : null;
        if (mapVar == null) mapVar = DEFAULT_MAP_VAR;

        StringBuilder caseBody = new StringBuilder();
        for (String var : pathVariables) {
            caseBody.append(mapVar).append(".put(\"").append(var).append("\", getRequiredParameter(\"")
                    .append(var).append("\", providedParameters, apiEndpoint));\n  ");
        }
        caseBody.append("return Optional.of(").append(mapVar).append(");");
        String caseText = "case " + constantName + ":\n  " + caseBody;

        String fullMethod =
                "@Override\n" +
                "protected Optional<Map<String, Object>> " + METHOD_NAME + "(ApiEndpoint apiEndpoint,\n" +
                "    Map<String, String> providedParameters) {\n" +
                "  Map<String, Object> " + mapVar + " = new HashMap<>();\n" +
                "  switch ((" + enumSimpleName + ") apiEndpoint) {\n" +
                "    " + caseText.replace("\n", "\n    ") + "\n" +
                "    default:\n" +
                "      return Optional.empty();\n" +
                "  }\n" +
                "}";

        SwitchMethodEditor.upsertCase(project, proxyClass, METHOD_NAME, fullMethod, caseText);
    }
}
