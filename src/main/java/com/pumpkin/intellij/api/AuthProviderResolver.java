package com.pumpkin.intellij.api;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiStatement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds every project class usable as an authentication provider for the {@code auth:} typing
 * shortcut (see {@link AuthStepTriggerHandler}), and its display name.
 *
 * <p>Real providers never implement {@code ApiAuthenticationProvider} directly - they extend one
 * of its three abstract base classes, {@code PasswordAuthenticationProvider}, {@code
 * BasicAuthenticationProvider}, or {@code ClientCredentialsApiAuthenticationProvider} (confirmed
 * spelling - matches what {@code ApiProxyCodeGenerator} already emits for the same class). So
 * discovery searches for direct subclasses of each of the three, via {@link
 * ApiEndpointResolver#findDirectSubclasses}, the same "extends"-search algorithm {@code
 * ApiEndpointResolver} already uses to find {@code AbstractApiProxy} subclasses.
 */
final class AuthProviderResolver {

    private static final String PASSWORD_PROVIDER_FQN =
            "at.compax.rp.test.services.api.providers.PasswordAuthenticationProvider";
    private static final String BASIC_PROVIDER_FQN =
            "at.compax.rp.test.services.api.providers.BasicAuthenticationProvider";
    private static final String CLIENT_CREDENTIALS_PROVIDER_FQN =
            "at.compax.rp.test.services.api.providers.ClientCredentialsApiAuthenticationProvider";

    private static final String[] PROVIDER_BASE_FQNS = {
            PASSWORD_PROVIDER_FQN, BASIC_PROVIDER_FQN, CLIENT_CREDENTIALS_PROVIDER_FQN
    };

    private AuthProviderResolver() {}

    /**
     * {@code toString()} is overridden (rather than left to the default record-generated one,
     * which would include {@code providerClass}) for the same reason {@code
     * ApiStepPopups.ApiChoice} does - see its doc comment: Swing's {@code JList} "type ahead to
     * select" calls {@code toString()} on list model items outside any read action, and {@code
     * PsiClass}'s own {@code toString()} needs one.
     */
    record AuthProviderChoice(@NotNull PsiClass providerClass, @NotNull String name) {
        @Override
        public String toString() { return name; }
    }

    /**
     * Returns every concrete auth-provider class found in the project, paired with its
     * {@code getName()} display name. A class whose {@code getName()} can't be statically
     * resolved (no such method declared, or a non-constant return value) is silently skipped -
     * it just won't show up as a choice, rather than surfacing a broken entry.
     */
    @NotNull
    static List<AuthProviderChoice> findAllAuthProviders(@NotNull Project project) {
        List<AuthProviderChoice> result = new ArrayList<>();
        for (String baseFqn : PROVIDER_BASE_FQNS) {
            for (PsiClass provider : ApiEndpointResolver.findDirectSubclasses(project, baseFqn)) {
                if (result.stream().anyMatch(c -> c.providerClass().equals(provider))) continue;
                String name = resolveGetName(provider);
                if (name != null) {
                    result.add(new AuthProviderChoice(provider, name));
                }
            }
        }
        return result;
    }

    /**
     * Resolves {@code provider}'s own declared {@code getName()} method (mirrors {@code
     * ApiEndpointResolver.extractGetApiNotation}'s "explicit method" form - no Lombok field
     * pattern applies here, since {@code getName()} returns a display {@code String}, not an
     * enum constant a field could be typed as).
     */
    @Nullable
    private static String resolveGetName(@NotNull PsiClass provider) {
        PsiMethod[] methods = provider.findMethodsByName("getName", false);
        if (methods.length == 0) return null;

        PsiCodeBlock body = methods[0].getBody();
        if (body == null) return null;

        for (PsiStatement stmt : body.getStatements()) {
            if (stmt instanceof PsiReturnStatement ret && ret.getReturnValue() != null) {
                return ApiEndpointParameters.resolveStringConstant(ret.getReturnValue());
            }
        }
        return null;
    }
}
