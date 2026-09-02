package com.pumpkin.intellij.endpoint;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 5: {@code collectHeaders}. Unlike the other switch-based methods, this one is flat and
 * shared across every endpoint of the proxy (per the user's own example) — each header row
 * becomes an unconditional {@code getOptionalParameter(...).ifPresent(...)} statement that
 * fires whenever that provided-parameter name is present on any request to this proxy, not just
 * the endpoint being added. Existing headers (matched by their outgoing header key) are never
 * duplicated.
 */
final class HeadersEditor {

    private static final String METHOD_NAME = "collectHeaders";
    private static final String DEFAULT_MAP_VAR = "params";

    private HeadersEditor() {}

    static void generate(@NotNull Project project, @NotNull SmartPsiElementPointer<PsiClass> proxyPtr,
                         @NotNull List<HeaderRow> headers) {
        PsiClass proxyClass = SwitchMethodEditor.requireValid(proxyPtr);
        PsiMethod existing = SwitchMethodEditor.findMethod(proxyClass, METHOD_NAME);

        if (existing == null) {
            StringBuilder body = new StringBuilder();
            for (HeaderRow row : headers) {
                body.append("  ").append(headerLine(row, DEFAULT_MAP_VAR)).append("\n");
            }
            String fullMethod =
                    "@Override\n" +
                    "protected Optional<Map<String, Object>> " + METHOD_NAME + "(ApiEndpoint apiEndpoint,\n" +
                    "    Map<String, String> providedParameters) {\n" +
                    "  Map<String, Object> " + DEFAULT_MAP_VAR + " = new HashMap<>();\n" +
                    body +
                    "  return Optional.of(" + DEFAULT_MAP_VAR + ");\n" +
                    "}";
            SwitchMethodEditor.appendMethod(project, proxyClass, fullMethod);
            return;
        }

        String mapVar = SwitchMethodEditor.findMapVariableName(existing);
        if (mapVar == null) mapVar = DEFAULT_MAP_VAR;

        PsiCodeBlock body = existing.getBody();
        if (body == null) return;
        String bodyText = body.getText();

        List<HeaderRow> toAdd = new ArrayList<>();
        for (HeaderRow row : headers) {
            if (!bodyText.contains("\"" + row.headerKey() + "\"")) {
                toAdd.add(row);
            }
        }
        if (toAdd.isEmpty()) return;

        PsiStatement[] statements = body.getStatements();
        if (statements.length == 0) return;
        PsiStatement lastStatement = statements[statements.length - 1];

        PsiFile file = existing.getContainingFile();
        Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
        if (doc == null) return;

        int insertOffset = lastStatement.getTextRange().getStartOffset();
        String indent = SwitchMethodEditor.indentOf(doc.getText(), insertOffset);
        StringBuilder toInsert = new StringBuilder();
        for (HeaderRow row : toAdd) {
            toInsert.append(headerLine(row, mapVar)).append("\n").append(indent);
        }

        doc.insertString(insertOffset, toInsert.toString());
        PsiDocumentManager.getInstance(project).commitDocument(doc);
        CodeStyleManager.getInstance(project)
                .reformatText(file, insertOffset, insertOffset + toInsert.length());
    }

    private static String headerLine(@NotNull HeaderRow row, @NotNull String mapVar) {
        return "getOptionalParameter(\"" + row.providedName() + "\", providedParameters).ifPresent(c -> "
                + mapVar + ".put(\"" + row.headerKey() + "\", c));";
    }
}
