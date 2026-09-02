package com.pumpkin.intellij.api;

import com.intellij.openapi.project.Project;
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
     * Extracts the name of the {@code ApiNotation} constant returned by the {@code getApi()}
     * method declared directly on {@code proxy}, e.g. returns {@code "BACKEND_API"} for
     * {@code return ApiNotation.BACKEND_API;}.
     */
    @Nullable
    private static String extractGetApiNotation(@NotNull PsiClass proxy) {
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
        return null;
    }

    /**
     * Returns the proxy class if {@code ref} appears inside a {@code return} statement
     * of a method named {@code getApi}, otherwise {@code null}.
     */
    @Nullable
    private static PsiClass proxyContaining(@NotNull PsiReference ref) {
        PsiReturnStatement ret =
                PsiTreeUtil.getParentOfType(ref.getElement(), PsiReturnStatement.class);
        if (ret == null) return null;
        PsiMethod method = PsiTreeUtil.getParentOfType(ret, PsiMethod.class);
        if (method == null || !"getApi".equals(method.getName())) return null;
        return method.getContainingClass();
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
}
