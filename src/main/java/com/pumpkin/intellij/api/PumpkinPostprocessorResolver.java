package com.pumpkin.intellij.api;

import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiSwitchBlock;
import com.intellij.psi.PsiSwitchLabelStatement;
import com.intellij.psi.PsiSwitchLabeledRuleStatement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the context-parameter names an API call's PostProcessor writes via {@code
 * writeParamToContext(name, ...)}, for the {@code %contextParameter%} autocomplete (see {@code
 * com.pumpkin.intellij.contextparam.ContextParameterStepAnalyzer}). Chained after {@link
 * ApiEndpointResolver#resolve} has already identified which endpoint enum constant a step calls.
 *
 * <p>Real request handling never runs a PostProcessor directly - a project-wide dispatcher
 * ({@code ApiPostprocessorDispatcher}) maps each {@code ApiNotation} to one {@code @Autowired}
 * field of a concrete {@code AbstractApiPostProcessor} subclass, inside a switch over the called
 * API; that subclass then has its own switch (in {@code postProcess}) over the specific endpoint,
 * whose case body may call {@code writeParamToContext("name", value)} zero or more times. This
 * walks that exact chain via plain PSI, resolving {@code "name"} the same way {@link
 * ApiEndpointParameters#resolveStringConstant} already does - a literal or a {@code static final
 * String} field reference declared in any class.
 */
public final class PumpkinPostprocessorResolver {

    private static final String DISPATCHER_FQN = "at.compax.rp.test.services.api.ApiPostprocessorDispatcher";
    private static final String ABSTRACT_POSTPROCESSOR_FQN =
            "at.compax.rp.test.services.api.postprocess.AbstractApiPostProcessor";

    private PumpkinPostprocessorResolver() {}

    /**
     * Returns every context-parameter name written by the PostProcessor handling {@code
     * apiNotationConstantName}'s {@code endpointConstantName} case - or an empty list if any step
     * of the resolution fails (dispatcher/field/PostProcessor/case not found), matching {@code
     * AuthProviderResolver}'s own "silently skip" philosophy for this kind of speculative analysis.
     */
    @NotNull
    public static List<String> resolveWrittenParams(@NotNull Project project, @NotNull String apiNotationConstantName,
                                                     @NotNull String endpointConstantName) {
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        PsiClass dispatcher = JavaPsiFacade.getInstance(project).findClass(DISPATCHER_FQN, scope);
        if (dispatcher == null) return List.of();

        PsiClass postProcessorClass = findPostProcessorClass(dispatcher, apiNotationConstantName);
        if (postProcessorClass == null) return List.of();

        PsiMethod[] postProcessMethods = postProcessorClass.findMethodsByName("postProcess", false);
        if (postProcessMethods.length == 0) return List.of();

        PsiCodeBlock body = postProcessMethods[0].getBody();
        PsiSwitchBlock switchBlock = body == null ? null : PsiTreeUtil.findChildOfType(body, PsiSwitchBlock.class);
        PsiCodeBlock switchBody = switchBlock == null ? null : switchBlock.getBody();
        if (switchBody == null) return List.of();

        List<String> result = new ArrayList<>();
        for (PsiElement caseElement : statementsForCase(switchBody, endpointConstantName)) {
            for (PsiMethodCallExpression call :
                    PsiTreeUtil.findChildrenOfType(caseElement, PsiMethodCallExpression.class)) {
                if (!"writeParamToContext".equals(call.getMethodExpression().getReferenceName())) continue;
                PsiExpression[] args = call.getArgumentList().getExpressions();
                if (args.length == 0) continue;
                String name = ApiEndpointParameters.resolveStringConstant(args[0]);
                if (name != null) result.add(name);
            }
        }
        return result;
    }

    /**
     * Finds {@code dispatcher}'s switch over {@code ApiNotation}, isolates the case matching
     * {@code apiNotationConstantName}, and returns the declared type of the first field reference
     * in that case whose type is a subclass of {@link #ABSTRACT_POSTPROCESSOR_FQN} - sidesteps
     * needing to recognise the exact {@code Optional.of(...)} call shape (traditional vs. arrow
     * switch, block vs. expression body all just become "some case body PSI to search"). Doesn't
     * assume the dispatch method is literally named {@code getPostProcessor} - only the class
     * shape was given as ground truth, not that exact name - so every method is tried in turn.
     */
    @Nullable
    private static PsiClass findPostProcessorClass(@NotNull PsiClass dispatcher,
                                                    @NotNull String apiNotationConstantName) {
        for (PsiMethod method : dispatcher.getMethods()) {
            PsiCodeBlock body = method.getBody();
            if (body == null) continue;
            PsiSwitchBlock switchBlock = PsiTreeUtil.findChildOfType(body, PsiSwitchBlock.class);
            if (switchBlock == null) continue;
            PsiCodeBlock switchBody = switchBlock.getBody();
            if (switchBody == null) continue;

            for (PsiElement caseElement : statementsForCase(switchBody, apiNotationConstantName)) {
                for (PsiReferenceExpression ref :
                        PsiTreeUtil.findChildrenOfType(caseElement, PsiReferenceExpression.class)) {
                    if (!(ref.resolve() instanceof PsiField field)) continue;
                    if (!(field.getType() instanceof PsiClassType classType)) continue;
                    PsiClass fieldClass = classType.resolve();
                    if (fieldClass != null && InheritanceUtil.isInheritor(fieldClass, ABSTRACT_POSTPROCESSOR_FQN)) {
                        return fieldClass;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Returns every statement/rule-body belonging to the {@code case <constantName>:}/{@code
     * case <constantName> ->} label in {@code switchBody} - mirrors {@code
     * ApiEndpointParameters.statementsForCase}/{@code ApiEndpointResolver.findTemplateVirtualFile}'s
     * own case-scanning (duplicated a third time here rather than extracted to a shared utility,
     * matching this codebase's existing style for a helper this small).
     */
    @NotNull
    private static List<PsiElement> statementsForCase(@NotNull PsiCodeBlock switchBody, @NotNull String constantName) {
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
