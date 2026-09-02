package com.pumpkin.intellij.endpoint;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.annotations.NotNull;

/**
 * Step 6: {@code determineExpectedResponseCode} — only generated when the endpoint's success
 * status isn't the default 200 OK. Each case ends with an explicit {@code return;} so it never
 * falls through into {@code default: super...()} right after setting the code.
 */
final class ResponseCodeEditor {

    private static final String METHOD_NAME = "determineExpectedResponseCode";
    private static final String HTTP_STATUS_FQN = "org.springframework.http.HttpStatus";

    private ResponseCodeEditor() {}

    static void generate(@NotNull Project project, @NotNull SmartPsiElementPointer<PsiClass> proxyPtr,
                         @NotNull String enumSimpleName, @NotNull String constantName,
                         @NotNull HttpStatusOption status) {
        PsiClass proxyClass = SwitchMethodEditor.requireValid(proxyPtr);
        boolean isNewMethod = SwitchMethodEditor.findMethod(proxyClass, METHOD_NAME) == null;

        if (isNewMethod && proxyClass.getContainingFile() instanceof PsiJavaFile javaFile) {
            SwitchMethodEditor.ensureImport(project, javaFile, HTTP_STATUS_FQN);
            proxyClass = SwitchMethodEditor.requireValid(proxyPtr);
        }

        String caseText = "case " + constantName + ":\n" +
                "  apiRequestDefinition.setExpectedResponseCode(getOptionalParameter(\"expectedResponseCode\",\n" +
                "      apiRequestDefinition.getProvidedParameters(), Integer.class).orElse(\n" +
                "      HttpStatus." + status.enumName() + ".value()));\n" +
                "  return;";

        String fullMethod =
                "@Override\n" +
                "protected void " + METHOD_NAME + "(ApiRequestDefinition apiRequestDefinition) {\n" +
                "  switch ((" + enumSimpleName + ") apiRequestDefinition.getApiEndpoint()) {\n" +
                "    " + caseText.replace("\n", "\n    ") + "\n" +
                "    default:\n" +
                "      super." + METHOD_NAME + "(apiRequestDefinition);\n" +
                "  }\n" +
                "}";

        SwitchMethodEditor.upsertCase(project, proxyClass, METHOD_NAME, fullMethod, caseText);
    }
}
