package com.pumpkin.intellij.api;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * Shared resolution logic for generic API send steps.
 *
 * <p>Given an {@code apiNotation} name (e.g. {@code "BACKEND_API"}) and an
 * {@code apiRequestDefinition} text (e.g. {@code "redis job start request"}), finds the
 * matching enum constant in an API proxy's endpoint enum using two complementary strategies:
 *
 * <ol>
 *   <li><b>Proxy extends search</b> — searches for classes that directly {@code extends
 *       AbstractApiProxy}, extracts the {@code getApi()} return constant name, and matches it
 *       against {@code apiNotation}.</li>
 *   <li><b>Notation constant search</b> — searches for all references to the
 *       {@code ApiNotation.{apiNotation}} constant inside {@code getApi()} methods and uses the
 *       containing class as the proxy.</li>
 * </ol>
 *
 * <p>Both strategies then look in the proxy's direct inner enums for a constant whose normalised
 * name matches {@code apiRequestDefinition}:
 * {@code value.trim().replace("_","").replaceAll("\\s+","")}, case-insensitively.
 */
public final class ApiEndpointResolver {

    static final String ABSTRACT_PROXY_FQN =
            "at.compax.rp.test.services.api.proxy.AbstractApiProxy";
    static final String API_NOTATION_FQN =
            "at.compax.rp.test.model.api.ApiNotation";

    private ApiEndpointResolver() {}

    /**
     * Returns the matching endpoint enum constant, or {@code null} if none is found.
     */
    @Nullable
    public static PsiEnumConstant resolve(@NotNull Project project,
                                          @NotNull String apiNotation,
                                          @NotNull String apiRequestDefinition) {
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);

        // Strategy 1: find proxy classes by searching for classes that extend AbstractApiProxy.
        PsiClass abstractProxy = facade.findClass(ABSTRACT_PROXY_FQN, scope);
        if (abstractProxy != null) {
            Collection<PsiReference> refs = ReferencesSearch.search(abstractProxy, scope).findAll();
            for (PsiReference ref : refs) {
                PsiClass proxy = proxyClassFromExtends(ref);
                if (proxy == null) continue;
                String returnedNotation = extractGetApiNotation(proxy);
                if (!apiNotation.equalsIgnoreCase(returnedNotation)) continue;
                PsiEnumConstant endpoint = findEndpointConstant(proxy, apiRequestDefinition);
                if (endpoint != null) return endpoint;
            }
        }

        // Strategy 2 (fallback): search all references to the specific ApiNotation constant
        // inside getApi() return statements.
        PsiClass apiNotationClass = facade.findClass(API_NOTATION_FQN, scope);
        if (apiNotationClass != null) {
            PsiEnumConstant targetNotation = findEnumConstant(apiNotationClass, apiNotation);
            if (targetNotation != null) {
                Collection<PsiReference> refs =
                        ReferencesSearch.search(targetNotation, scope).findAll();
                for (PsiReference ref : refs) {
                    PsiClass proxy = proxyContaining(ref);
                    if (proxy == null) continue;
                    PsiEnumConstant endpoint = findEndpointConstant(proxy, apiRequestDefinition);
                    if (endpoint != null) return endpoint;
                }
            }
        }

        return null;
    }

    /**
     * Given a reference to {@code AbstractApiProxy} (or a type that uses it), returns the
     * {@link PsiClass} that directly extends it — i.e. the reference appears in an
     * {@code extends} clause — or {@code null} otherwise.
     */
    @Nullable
    private static PsiClass proxyClassFromExtends(@NotNull PsiReference ref) {
        PsiElement elem = ref.getElement();
        PsiReferenceList refList = PsiTreeUtil.getParentOfType(elem, PsiReferenceList.class);
        if (refList == null) return null;
        if (refList.getRole() != PsiReferenceList.Role.EXTENDS_LIST) return null;
        PsiElement parent = refList.getParent();
        return parent instanceof PsiClass pc ? pc : null;
    }

    /**
     * Extracts the name of the {@code ApiNotation} constant that {@code proxy} exposes via
     * {@code getApi()}, supporting two forms:
     *
     * <ul>
     *   <li><b>Explicit method</b>: {@code public ApiNotation getApi() { return ApiNotation.X; }}</li>
     *   <li><b>Lombok {@code @Getter}</b>: {@code @Getter private final ApiNotation api = ApiNotation.X;}
     *       — Lombok generates {@code getApi()} from a field named {@code api}.</li>
     * </ul>
     */
    @Nullable
    private static String extractGetApiNotation(@NotNull PsiClass proxy) {
        // Form 1: explicit getApi() method
        for (PsiMethod method : proxy.getMethods()) {
            if (!"getApi".equals(method.getName())
                    || method.getParameterList().getParametersCount() != 0) {
                continue;
            }
            PsiCodeBlock body = method.getBody();
            if (body == null) continue;
            for (PsiStatement stmt : body.getStatements()) {
                if (!(stmt instanceof PsiReturnStatement ret)) continue;
                PsiExpression expr = ret.getReturnValue();
                if (!(expr instanceof PsiReferenceExpression ref)) continue;
                return ref.getReferenceName();
            }
        }

        // Form 2: Lombok @Getter on a field named "api" — generates getApi()
        for (PsiField field : proxy.getFields()) {
            if (!"api".equals(field.getName())) continue;
            PsiExpression initializer = field.getInitializer();
            if (!(initializer instanceof PsiReferenceExpression ref)) continue;
            return ref.getReferenceName();
        }

        return null;
    }

    /**
     * Returns the proxy class if {@code ref} appears either:
     * <ul>
     *   <li>inside a {@code return} statement of a method named {@code getApi}, or</li>
     *   <li>as the initializer of a field named {@code api} (Lombok {@code @Getter} pattern).</li>
     * </ul>
     * Returns {@code null} otherwise.
     */
    @Nullable
    private static PsiClass proxyContaining(@NotNull PsiReference ref) {
        PsiElement elem = ref.getElement();

        // Form 1: explicit getApi() method body
        PsiReturnStatement ret = PsiTreeUtil.getParentOfType(elem, PsiReturnStatement.class);
        if (ret != null) {
            PsiMethod method = PsiTreeUtil.getParentOfType(ret, PsiMethod.class);
            if (method != null && "getApi".equals(method.getName())) {
                return method.getContainingClass();
            }
        }

        // Form 2: Lombok @Getter — field named "api" used as initializer
        PsiField field = PsiTreeUtil.getParentOfType(elem, PsiField.class);
        if (field != null && "api".equals(field.getName())) {
            return field.getContainingClass();
        }

        return null;
    }

    /** Finds a named enum constant in the given enum class. */
    @Nullable
    private static PsiEnumConstant findEnumConstant(@NotNull PsiClass enumClass,
                                                     @NotNull String name) {
        for (PsiField f : enumClass.getFields()) {
            if (f instanceof PsiEnumConstant ec && name.equals(ec.getName())) return ec;
        }
        return null;
    }

    /**
     * Searches the direct inner enums of {@code proxy} for a constant that matches
     * {@code apiRequestDefinition} after normalisation.
     */
    @Nullable
    public static PsiEnumConstant findEndpointConstant(@NotNull PsiClass proxy,
                                                       @NotNull String apiRequestDefinition) {
        String normalised = normalise(apiRequestDefinition);
        for (PsiClass inner : proxy.getInnerClasses()) {
            if (!inner.isEnum()) continue;
            for (PsiField field : inner.getFields()) {
                if (!(field instanceof PsiEnumConstant ec)) continue;
                if (normalise(ec.getName()).equalsIgnoreCase(normalised)) return ec;
            }
        }
        return null;
    }

    /** Canonical normalisation: trim, remove underscores, collapse whitespace. */
    public static @NotNull String normalise(@NotNull String value) {
        return value.trim().replace("_", "").replaceAll("\\s+", "");
    }

    // ── Template resolution ──────────────────────────────────────────────────────────────────

    /**
     * Finds the body-template {@link VirtualFile} for a given endpoint by inspecting the proxy's
     * {@code getBodyTemplate} method.
     *
     * <p>The proxy is expected to have a {@code getBodyTemplate} method with a switch on the
     * endpoint enum, where each case calls {@code loadPayloadTemplate(field)} and the
     * {@code field} is a Spring {@code @Value("classpath:…")} resource.
     *
     * @return the template file, or {@code null} if none can be found for this endpoint
     */
    @Nullable
    public static VirtualFile findTemplateVirtualFile(@NotNull PsiClass proxy,
                                                      @NotNull PsiEnumConstant endpoint,
                                                      @NotNull Project project) {
        PsiMethod[] methods = proxy.findMethodsByName("getBodyTemplate", false);
        if (methods.length == 0) return null;

        PsiCodeBlock methodBody = methods[0].getBody();
        if (methodBody == null) return null;

        PsiSwitchBlock switchBlock = PsiTreeUtil.findChildOfType(methodBody, PsiSwitchBlock.class);
        if (switchBlock == null) return null;
        PsiCodeBlock switchBody = switchBlock.getBody();
        if (switchBody == null) return null;

        String endpointName = endpoint.getName();
        boolean inTargetCase = false;

        for (PsiStatement stmt : switchBody.getStatements()) {
            if (stmt instanceof PsiSwitchLabeledRuleStatement rule) {
                // Enhanced switch: case CONSTANT -> body
                inTargetCase = false;
                if (!isCaseLabelForConstant(rule, endpointName)) continue;
                PsiStatement ruleBody = rule.getBody();
                if (ruleBody == null) continue;
                String path = extractLoadPayloadPath(ruleBody);
                return path != null ? resolveClasspathResource(path, project) : null;

            } else if (stmt instanceof PsiSwitchLabelStatement label) {
                // Traditional switch: case CONSTANT:
                inTargetCase = !label.isDefaultCase() && isCaseLabelForConstant(label, endpointName);

            } else if (inTargetCase) {
                // Statement following the matching traditional case label
                String path = extractLoadPayloadPath(stmt);
                if (path != null) return resolveClasspathResource(path, project);
            }
        }
        return null;
    }

    /**
     * Returns true if {@code stmt} is a switch label statement (case or rule) whose label
     * references the given constant name.
     *
     * <p>Checks all {@link PsiReferenceExpression} children of {@code stmt}. For enhanced
     * switch rules the body is part of the rule element, but body expressions (like
     * {@code Optional.of(...)}) will never contain the enum constant name, so false
     * positives are not a concern in practice.
     */
    private static boolean isCaseLabelForConstant(@NotNull PsiStatement stmt,
                                                   @NotNull String constantName) {
        for (PsiReferenceExpression ref :
                PsiTreeUtil.findChildrenOfType(stmt, PsiReferenceExpression.class)) {
            if (constantName.equals(ref.getReferenceName())) return true;
        }
        return false;
    }

    /**
     * Finds a {@code loadPayloadTemplate(field)} call anywhere inside {@code element},
     * resolves the field argument, reads its {@code @Value} annotation, and returns the
     * classpath-relative path (with the {@code classpath:} prefix stripped).
     */
    @Nullable
    private static String extractLoadPayloadPath(@NotNull PsiElement element) {
        for (PsiMethodCallExpression call :
                PsiTreeUtil.findChildrenOfType(element, PsiMethodCallExpression.class)) {
            if (!"loadPayloadTemplate".equals(call.getMethodExpression().getReferenceName())) {
                continue;
            }
            PsiExpression[] args = call.getArgumentList().getExpressions();
            if (args.length < 1 || !(args[0] instanceof PsiReferenceExpression ref)) continue;
            PsiElement resolved = ref.resolve();
            if (!(resolved instanceof PsiField field)) continue;
            return extractValueAnnotationPath(field);
        }
        return null;
    }

    /**
     * Reads the {@code @Value("classpath:…")} annotation on {@code field} and returns the
     * path with the {@code classpath:} (or {@code classpath*:}) prefix stripped.
     */
    @Nullable
    private static String extractValueAnnotationPath(@NotNull PsiField field) {
        PsiAnnotation ann = field.getAnnotation(
                "org.springframework.beans.factory.annotation.Value");
        if (ann == null) return null;
        PsiAnnotationMemberValue attrValue = ann.findAttributeValue("value");
        if (!(attrValue instanceof PsiLiteralExpression lit)) return null;
        Object val = lit.getValue();
        if (!(val instanceof String s)) return null;
        if (s.startsWith("classpath*:")) return s.substring("classpath*:".length());
        if (s.startsWith("classpath:")) return s.substring("classpath:".length());
        return s;
    }

    /**
     * Searches all module source roots (including resource directories) for a file at the
     * given classpath-relative path.
     */
    @Nullable
    private static VirtualFile resolveClasspathResource(@NotNull String path,
                                                        @NotNull Project project) {
        for (Module module : ModuleManager.getInstance(project).getModules()) {
            for (VirtualFile root : ModuleRootManager.getInstance(module).getSourceRoots()) {
                VirtualFile file = root.findFileByRelativePath(path);
                if (file != null && file.isValid()) return file;
            }
        }
        return null;
    }
}
