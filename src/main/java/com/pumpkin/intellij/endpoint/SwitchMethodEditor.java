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
    public static void upsertCase(@NotNull Project project, @NotNull PsiClass proxyClass,
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

    /**
     * Appends arbitrary member text (a whole new method, or - reused by {@code
     * PostProcessorCodeGenerator} - a single field declaration) right before {@code proxyClass}'s
     * closing brace.
     */
    public static void appendMethod(@NotNull Project project, @NotNull PsiClass proxyClass,
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
     * If the case immediately before {@code method}'s switch's {@code default:} label doesn't
     * already end in a control-flow-terminating statement (return/break/throw/continue), appends
     * {@code terminatorText} (e.g. {@code "return;"}) right after its last statement. Without
     * this, a new case spliced in afterward via {@link #upsertCase}/{@link
     * #insertCaseBeforeDefault} - which always inserts right before {@code default:} - would let
     * whatever case used to be last silently fall through into it, since that case previously had
     * no need to terminate (there was nothing after it but {@code default:}).
     *
     * <p>Returns {@code true} if a terminator was actually inserted - meaning any {@link
     * PsiMethod}/{@link PsiClass} handle for this file obtained before this call is now stale and
     * must be re-fetched, per this class's own doc. Callers are responsible for picking a {@code
     * terminatorText} valid for the enclosing method's return type (a bare {@code "return;"} for
     * a {@code void} method) - this makes no attempt to synthesize a meaningful non-void return
     * value, so it's only safe to use for {@code void} switch methods like {@code postProcess}.
     */
    public static boolean ensurePreviousCaseTerminates(@NotNull Project project, @NotNull PsiMethod method,
                                                       @NotNull String terminatorText) {
        PsiCodeBlock body = method.getBody();
        if (body == null) return false;
        PsiSwitchBlock switchBlock = PsiTreeUtil.findChildOfType(body, PsiSwitchBlock.class);
        if (switchBlock == null) return false;
        PsiCodeBlock switchBody = switchBlock.getBody();
        if (switchBody == null) return false;

        PsiStatement[] statements = switchBody.getStatements();
        int defaultIndex = -1;
        for (int i = 0; i < statements.length; i++) {
            if (isDefaultLabel(statements[i])) {
                defaultIndex = i;
                break;
            }
        }
        if (defaultIndex <= 0) return false; // no default label, or nothing precedes it to check

        PsiStatement lastBeforeDefault = statements[defaultIndex - 1];
        // An empty "case FOO:" label with no statements of its own directly before default (an
        // intentional or accidental fallthrough-to-default) has nothing to append a terminator
        // after in a meaningful place - leave it alone rather than guessing.
        if (lastBeforeDefault instanceof PsiSwitchLabelStatement || lastBeforeDefault instanceof PsiSwitchLabeledRuleStatement) {
            return false;
        }
        if (isTerminating(lastBeforeDefault)) return false;

        PsiFile file = method.getContainingFile();
        Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
        if (doc == null) return false;

        int insertOffset = lastBeforeDefault.getTextRange().getEndOffset();
        String indent = indentOf(doc.getText(), lastBeforeDefault.getTextRange().getStartOffset());
        String textToInsert = "\n" + indent + terminatorText;

        doc.insertString(insertOffset, textToInsert);
        commitAndReformat(project, doc, file, insertOffset, insertOffset + textToInsert.length());
        return true;
    }

    private static boolean isDefaultLabel(@NotNull PsiStatement stmt) {
        if (stmt instanceof PsiSwitchLabeledRuleStatement rule) return rule.isDefaultCase();
        if (stmt instanceof PsiSwitchLabelStatement label) return label.isDefaultCase();
        return false;
    }

    /** Whether {@code stmt} unconditionally transfers control away, so nothing after it in the same case can run. */
    private static boolean isTerminating(@NotNull PsiStatement stmt) {
        if (stmt instanceof PsiReturnStatement || stmt instanceof PsiBreakStatement
                || stmt instanceof PsiThrowStatement || stmt instanceof PsiContinueStatement) {
            return true;
        }
        if (stmt instanceof PsiBlockStatement block) {
            PsiStatement[] inner = block.getCodeBlock().getStatements();
            return inner.length > 0 && isTerminating(inner[inner.length - 1]);
        }
        return false;
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
    public static void ensureImport(@NotNull Project project, @NotNull PsiJavaFile file, @NotNull String fqn) {
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
