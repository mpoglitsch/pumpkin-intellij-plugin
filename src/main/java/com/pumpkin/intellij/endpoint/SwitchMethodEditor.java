package com.pumpkin.intellij.endpoint;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shared text-splicing toolbox for generating/editing the switch-statement methods on an API
 * proxy class ({@code collectPathParams}, {@code collectQueryParams}, {@code getBodyTemplate},
 * {@code determineExpectedResponseCode}), following the same "compute an offset, splice text
 * into the Document, commit, reformat just that range" style already used elsewhere in this
 * plugin ({@code AddMissingProcessParameterQuickFix}, {@code PumpkinProcessInsertHandler}) —
 * deliberately not real PSI-factory tree construction (see the plan for why).
 *
 * <p>Callers are expected to re-fetch fresh {@link PsiClass}/{@link PsiMethod} handles (e.g. via
 * a {@link com.intellij.psi.SmartPsiElementPointer}) after any method here runs, since inserting
 * text triggers a PSI commit that can invalidate previously held elements in the same file.
 */
public final class SwitchMethodEditor {

    private SwitchMethodEditor() {}

    /** Finds a method by name on {@code proxyClass}, or {@code null} if none exists. */
    static @Nullable PsiMethod findMethod(@NotNull PsiClass proxyClass, @NotNull String methodName) {
        PsiMethod[] methods = proxyClass.findMethodsByName(methodName, false);
        return methods.length > 0 ? methods[0] : null;
    }

    /**
     * If {@code method} exists, splices {@code caseTextIfPresent} into its switch statement
     * right before the {@code default} label. If it doesn't exist yet, appends a whole new
     * method (built from {@code fullMethodTextIfMissing}) right before the class's closing
     * brace.
     */
    static void upsertCase(@NotNull Project project, @NotNull PsiClass proxyClass,
                           @NotNull String methodName, @NotNull String fullMethodTextIfMissing,
                           @NotNull String caseTextIfPresent) {
        PsiMethod method = findMethod(proxyClass, methodName);
        if (method == null) {
            appendMethod(project, proxyClass, fullMethodTextIfMissing);
            return;
        }
        if (!insertCaseBeforeDefault(project, method, caseTextIfPresent)) {
            throw new IllegalStateException("Could not find a 'default' case in " + methodName
                    + "() on " + proxyClass.getName() + " to insert the new case before.");
        }
    }

    /**
     * Scans the statements before {@code method}'s switch (or all of them, for switch-less
     * methods like {@code collectHeaders}) for the first local variable whose type mentions
     * "Map", returning its name — the example code reuses one such variable across every case.
     */
    static @Nullable String findMapVariableName(@NotNull PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) return null;
        for (PsiStatement stmt : body.getStatements()) {
            if (stmt instanceof PsiDeclarationStatement decl) {
                for (PsiElement el : decl.getDeclaredElements()) {
                    if (el instanceof PsiLocalVariable v
                            && v.getType().getPresentableText().contains("Map")) {
                        return v.getName();
                    }
                }
            }
        }
        return null;
    }

    /** Appends a whole new method's text right before {@code proxyClass}'s closing brace. */
    static void appendMethod(@NotNull Project project, @NotNull PsiClass proxyClass,
                             @NotNull String methodText) {
        PsiFile file = proxyClass.getContainingFile();
        Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
        if (doc == null) return;

        PsiElement rBrace = proxyClass.getRBrace();
        int insertOffset = rBrace != null
                ? rBrace.getTextOffset()
                : proxyClass.getTextRange().getEndOffset() - 1;
        String indent = indentOf(doc.getText(), proxyClass.getTextOffset()) + "    ";

        String textToInsert = "\n" + indent + methodText.replace("\n", "\n" + indent) + "\n\n";
        doc.insertString(insertOffset, textToInsert);
        commitAndReformat(project, doc, file, insertOffset, insertOffset + textToInsert.length());
    }

    /**
     * Inserts {@code caseText} right before the {@code default} label of {@code method}'s
     * switch statement. Returns {@code false} if no switch/default label could be found.
     */
    static boolean insertCaseBeforeDefault(@NotNull Project project, @NotNull PsiMethod method,
                                           @NotNull String caseText) {
        PsiCodeBlock body = method.getBody();
        if (body == null) return false;
        PsiSwitchBlock switchBlock = PsiTreeUtil.findChildOfType(body, PsiSwitchBlock.class);
        if (switchBlock == null) return false;
        PsiCodeBlock switchBody = switchBlock.getBody();
        if (switchBody == null) return false;

        PsiElement defaultLabel = null;
        for (PsiStatement stmt : switchBody.getStatements()) {
            if (stmt instanceof PsiSwitchLabeledRuleStatement rule && rule.isDefaultCase()) {
                defaultLabel = rule;
                break;
            }
            if (stmt instanceof PsiSwitchLabelStatement label && label.isDefaultCase()) {
                defaultLabel = label;
                break;
            }
        }
        if (defaultLabel == null) return false;

        PsiFile file = method.getContainingFile();
        Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
        if (doc == null) return false;

        int insertOffset = defaultLabel.getTextRange().getStartOffset();
        String indent = indentOf(doc.getText(), insertOffset);
        String textToInsert = caseText.replace("\n", "\n" + indent) + "\n" + indent;

        doc.insertString(insertOffset, textToInsert);
        commitAndReformat(project, doc, file, insertOffset, insertOffset + textToInsert.length());
        return true;
    }

    /**
     * Inserts {@code import fqn;} right after the last existing import (or the package
     * statement) if {@code file} doesn't already import it.
     */
    static void ensureImport(@NotNull Project project, @NotNull PsiJavaFile file, @NotNull String fqn) {
        PsiImportList importList = file.getImportList();
        if (importList != null && importList.findSingleClassImportStatement(fqn) != null) return;

        Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
        if (doc == null) return;

        int insertOffset;
        PsiImportStatement[] imports = importList != null ? importList.getImportStatements() : PsiImportStatement.EMPTY_ARRAY;
        if (imports.length > 0) {
            insertOffset = imports[imports.length - 1].getTextRange().getEndOffset();
        } else if (file.getPackageStatement() != null) {
            insertOffset = file.getPackageStatement().getTextRange().getEndOffset();
        } else {
            insertOffset = 0;
        }

        String textToInsert = "\nimport " + fqn + ";";
        doc.insertString(insertOffset, textToInsert);
        PsiDocumentManager.getInstance(project).commitDocument(doc);
    }

    private static void commitAndReformat(@NotNull Project project, @NotNull Document doc,
                                          @NotNull PsiFile file, int start, int end) {
        PsiDocumentManager.getInstance(project).commitDocument(doc);
        CodeStyleManager.getInstance(project).reformatText(file, start, end);
    }

    /** Returns the leading whitespace of the line containing {@code offset}. */
    public static @NotNull String indentOf(@NotNull String fileText, int offset) {
        int lineStart = fileText.lastIndexOf('\n', Math.max(offset - 1, 0)) + 1;
        int contentStart = lineStart;
        while (contentStart < fileText.length()
                && (fileText.charAt(contentStart) == ' ' || fileText.charAt(contentStart) == '\t')) {
            contentStart++;
        }
        return fileText.substring(lineStart, contentStart);
    }

    /** Re-resolves a smart pointer, throwing a clear error if the element became invalid. */
    static @NotNull PsiClass requireValid(@NotNull SmartPsiElementPointer<PsiClass> pointer) {
        PsiClass element = pointer.getElement();
        if (element == null || !element.isValid()) {
            throw new IllegalStateException("The proxy class became invalid while generating the endpoint.");
        }
        return element;
    }
}
