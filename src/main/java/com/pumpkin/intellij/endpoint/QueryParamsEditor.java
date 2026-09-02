package com.pumpkin.intellij.endpoint;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Step 3: {@code collectQueryParams} — a {@code copyRequiredParamToMap}/{@code
 * copyParamToMapIfPresent} call per query-parameter row, depending on its Required checkbox.
 */
final class QueryParamsEditor {

    private static final String METHOD_NAME = "collectQueryParams";
    private static final String DEFAULT_MAP_VAR = "queryParams";

    private QueryParamsEditor() {}

    static void generate(@NotNull Project project, @NotNull SmartPsiElementPointer<PsiClass> proxyPtr,
                         @NotNull String enumSimpleName, @NotNull String constantName,
                         @NotNull List<QueryParamRow> rows) {
        PsiClass proxyClass = SwitchMethodEditor.requireValid(proxyPtr);
        PsiMethod existing = SwitchMethodEditor.findMethod(proxyClass, METHOD_NAME);
        String mapVar = existing != null ? SwitchMethodEditor.findMapVariableName(existing) : null;
        if (mapVar == null) mapVar = DEFAULT_MAP_VAR;

        StringBuilder caseBody = new StringBuilder();
        for (QueryParamRow row : rows) {
            if (row.required()) {
                caseBody.append("copyRequiredParamToMap(\"").append(row.name())
                        .append("\", providedParameters, ").append(mapVar).append(", apiEndpoint);\n  ");
            } else {
                caseBody.append("copyParamToMapIfPresent(\"").append(row.name())
                        .append("\", providedParameters, ").append(mapVar).append(");\n  ");
            }
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
