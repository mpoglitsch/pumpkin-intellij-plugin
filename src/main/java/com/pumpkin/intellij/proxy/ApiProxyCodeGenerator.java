package com.pumpkin.intellij.proxy;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.pumpkin.intellij.api.ApiEndpointResolver;
import com.pumpkin.intellij.endpoint.NameUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates a brand-new {@code AbstractApiProxy} subclass (+ {@code ApiNotation} constant, and -
 * if requested - a new {@code ApiAuthenticationProvider}) from an {@link AddApiProxyDialog}
 * submission, all inside one {@link WriteCommandAction} so the whole thing is one undo step.
 *
 * <p>Unlike {@code EndpointCodeGenerator} (which incrementally edits an existing proxy file), the
 * new proxy class has no existing content to preserve, so its full source is built as a plain
 * string from the exact skeleton the user specified, written out as a new file, then reformatted
 * - simpler and more reliable than incremental PSI splicing for a file that doesn't exist yet.
 */
public final class ApiProxyCodeGenerator {

    private static final String PROXY_PACKAGE = "at.compax.rp.test.services.api.proxy";
    private static final String PROVIDER_IMPL_PACKAGE = "at.compax.rp.test.services.api.providers.impl";
    private static final String GENERIC_CONFIGURATION_FQN = "at.compax.foundation.ta.util.configuration.GenericConfiguration";

    private ApiProxyCodeGenerator() {}

    public static void generate(@NotNull Project project, @NotNull NewApiProxySpec spec) {
        String constantName = NameUtils.toUpperSnakeCase(spec.proxyName());
        String base = NameUtils.toPascalCase(spec.proxyName());
        String kebabName = NameUtils.toKebabCase(spec.proxyName());
        boolean hasAuth = spec.authenticationMethod() != AuthenticationMethod.NONE;

        List<PsiClass> existingProxies = ApiEndpointResolver.findAllProxyClasses(project);
        if (existingProxies.isEmpty()) {
            throw new IllegalStateException(
                    "Could not find any existing class extending AbstractApiProxy to determine where to create the new proxy.");
        }
        PsiClass anchorProxy = existingProxies.get(0);

        PsiClass apiNotationClass = JavaPsiFacade.getInstance(project)
                .findClass(ApiEndpointResolver.API_NOTATION_FQN, GlobalSearchScope.allScope(project));
        if (apiNotationClass == null) {
            throw new IllegalStateException("Could not find " + ApiEndpointResolver.API_NOTATION_FQN + ".");
        }

        WriteCommandAction.runWriteCommandAction(project, "Add API Proxy", null, () -> {
            ApiNotationEditor.appendConstant(project, apiNotationClass, constantName);

            if (hasAuth) {
                String providerSource = buildProviderSource(base, spec.authenticationMethod(), spec.fieldValues());
                createJavaFile(project, PROVIDER_IMPL_PACKAGE, base + "AuthenticationProvider", providerSource, anchorProxy);
            }

            String proxySource = buildProxySource(constantName, base, hasAuth, kebabName);
            PsiFile proxyFile = createJavaFile(project, PROXY_PACKAGE, base + "Proxy", proxySource, anchorProxy);

            if (!spec.environmentBaseUrls().isEmpty()) {
                // Matches buildProxySource's own "baseUrlProperty" field value exactly - see its
                // "@Getter private String baseUrlProperty = ..." line below.
                writeEnvironmentBaseUrls(project, kebabName + ".url", spec.environmentBaseUrls());
            }

            if (proxyFile != null && proxyFile.getVirtualFile() != null) {
                FileEditorManager.getInstance(project).openFile(proxyFile.getVirtualFile(), true);
            }
        }, anchorProxy.getContainingFile());
    }

    // -------------------------------------------------------------------------
    // Per-environment base URL properties
    // -------------------------------------------------------------------------

    /**
     * Appends {@code <propertyKey>=<baseUrl>} to each named environment's own {@code
     * testsuite_configuration_<environment>.properties} file - re-resolved fresh here rather than
     * reusing whatever {@link EnvironmentResolver} found when the dialog opened, in case the
     * project changed in the meantime.
     */
    private static void writeEnvironmentBaseUrls(@NotNull Project project, @NotNull String propertyKey,
                                                 @NotNull List<EnvironmentBaseUrl> environmentBaseUrls) {
        Map<String, VirtualFile> environmentFiles = EnvironmentResolver.findEnvironmentFiles(project);
        for (EnvironmentBaseUrl entry : environmentBaseUrls) {
            VirtualFile file = environmentFiles.get(entry.environment());
            if (file == null) {
                throw new IllegalStateException(
                        "Could not find testsuite_configuration_" + entry.environment() + ".properties anymore.");
            }
            appendProperty(file, propertyKey, entry.baseUrl());
        }
    }

    private static void appendProperty(@NotNull VirtualFile file, @NotNull String key, @NotNull String value) {
        try {
            String content = VfsUtil.loadText(file);
            String separator = content.isEmpty() || content.endsWith("\n") ? "" : "\n";
            VfsUtil.saveText(file, content + separator + key + "=" + value + "\n");
        } catch (IOException e) {
            throw new RuntimeException("Could not update " + file.getName() + ": " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // File creation
    // -------------------------------------------------------------------------

    private static @Nullable PsiFile createJavaFile(@NotNull Project project, @NotNull String packageFqn,
                                                     @NotNull String simpleClassName, @NotNull String source,
                                                     @NotNull PsiClass anchorProxy) {
        VirtualFile dir = findOrCreatePackageDirectory(project, packageFqn, anchorProxy);
        if (dir == null) {
            throw new IllegalStateException("Could not find or create a source directory for " + packageFqn + ".");
        }
        String fileName = simpleClassName + ".java";
        if (dir.findChild(fileName) != null) {
            throw new IllegalStateException("File already exists: " + fileName);
        }
        try {
            VirtualFile file = dir.createChildData(ApiProxyCodeGenerator.class, fileName);
            VfsUtil.saveText(file, source);
            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            if (psiFile != null) {
                CodeStyleManager.getInstance(project).reformat(psiFile);
            }
            return psiFile;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create " + fileName, e);
        }
    }

    /** Prefers an existing package directory; falls back to creating one under the anchor proxy's own source root. */
    private static @Nullable VirtualFile findOrCreatePackageDirectory(@NotNull Project project,
                                                                       @NotNull String packageFqn,
                                                                       @NotNull PsiClass anchorProxy) {
        PsiPackage psiPackage = JavaPsiFacade.getInstance(project).findPackage(packageFqn);
        if (psiPackage != null) {
            PsiDirectory[] dirs = psiPackage.getDirectories();
            if (dirs.length > 0) return dirs[0].getVirtualFile();
        }

        Module module = ModuleUtilCore.findModuleForPsiElement(anchorProxy);
        if (module == null) return null;

        String relativePath = packageFqn.replace('.', '/');
        for (VirtualFile root : ModuleRootManager.getInstance(module).getSourceRoots()) {
            VirtualFile existing = root.findFileByRelativePath(relativePath);
            if (existing != null) return existing;
        }

        VirtualFile anchorFile = anchorProxy.getContainingFile().getVirtualFile();
        for (VirtualFile root : ModuleRootManager.getInstance(module).getSourceRoots()) {
            if (VfsUtilCore.isAncestor(root, anchorFile, false)) {
                try {
                    return VfsUtil.createDirectoryIfMissing(root, relativePath);
                } catch (IOException e) {
                    return null;
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Source templates
    // -------------------------------------------------------------------------

    /** Mirrors the exact proxy skeleton, with the auth-provider override included only when {@code hasAuth}. */
    static @NotNull String buildProxySource(@NotNull String constantName, @NotNull String base,
                                            boolean hasAuth, @NotNull String kebabName) {
        String className = base + "Proxy";
        String endpointEnum = base + "Endpoint";

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(PROXY_PACKAGE).append(";\n\n");
        sb.append("import at.compax.rp.test.model.api.ApiNotation;\n");
        sb.append("import at.compax.rp.test.model.api.ApiRequestDefinition;\n");
        sb.append("import at.compax.rp.test.services.api.ApiEndpoint;\n");
        if (hasAuth) {
            sb.append("import at.compax.rp.test.services.api.providers.ApiAuthenticationProvider;\n");
            sb.append("import ").append(PROVIDER_IMPL_PACKAGE).append('.').append(base).append("AuthenticationProvider;\n");
        }
        sb.append("import at.compax.rp.test.services.api.error.UnknownEndpointException;\n");
        sb.append("import io.restassured.http.Method;\n");
        sb.append("import lombok.AllArgsConstructor;\n");
        sb.append("import lombok.Getter;\n");
        sb.append("import org.springframework.stereotype.Service;\n\n");
        sb.append("import java.util.Arrays;\n");
        sb.append("import java.util.HashMap;\n");
        sb.append("import java.util.Map;\n");
        sb.append("import java.util.Optional;\n\n");
        sb.append("import static at.compax.rp.test.model.api.ApiNotation.").append(constantName).append(";\n\n");
        sb.append("@Service\n");
        sb.append("public class ").append(className).append(" extends AbstractApiProxy {\n");
        sb.append("  @Override\n");
        sb.append("  public ApiNotation getApi() {\n");
        sb.append("    return ApiNotation.").append(constantName).append(";\n");
        sb.append("  }\n\n");
        sb.append("  @Getter\n");
        sb.append("  private String baseUrlProperty = \"").append(kebabName).append(".url\";\n\n");
        sb.append("  @Override\n");
        sb.append("  protected ApiEndpoint determineEndpointByName(String apiEndpointName) {\n");
        sb.append("    return ").append(endpointEnum).append(".byString(apiEndpointName);\n");
        sb.append("  }\n\n");
        sb.append("  @Override\n");
        sb.append("  protected Optional<Map<String, Object>> collectQueryParams(ApiEndpoint apiEndpoint,\n");
        sb.append("      Map<String, String> providedParameters) {\n");
        sb.append("    Map<String, Object> queryParams = new HashMap<>();\n");
        sb.append("    switch ((").append(endpointEnum).append(") apiEndpoint) {\n");
        sb.append("      default:\n");
        sb.append("        return Optional.empty();\n");
        sb.append("    }\n");
        sb.append("  }\n\n");
        sb.append("  @Override\n");
        sb.append("  protected Optional<Map<String, Object>> collectPathParams(ApiEndpoint apiEndpoint,\n");
        sb.append("      Map<String, String> providedParameters) {\n");
        sb.append("    Map<String, Object> pathParams = new HashMap<>();\n");
        sb.append("    switch ((").append(endpointEnum).append(") apiEndpoint) {\n");
        sb.append("      default:\n");
        sb.append("        return Optional.empty();\n");
        sb.append("    }\n");
        sb.append("  }\n\n");
        sb.append("  @Override\n");
        sb.append("  protected void determineExpectedResponseCode(ApiRequestDefinition apiRequestDefinition) {\n");
        sb.append("    switch ((").append(endpointEnum).append(") apiRequestDefinition.getApiEndpoint()) {\n");
        sb.append("      default:\n");
        sb.append("        super.determineExpectedResponseCode(apiRequestDefinition);\n");
        sb.append("    }\n");
        sb.append("  }\n\n");
        if (hasAuth) {
            sb.append("  @Override\n");
            sb.append("  protected ApiAuthenticationProvider getDefaultApiAuthenticationProvider() {\n");
            sb.append("    return new ").append(base).append("AuthenticationProvider();\n");
            sb.append("  }\n\n");
        }
        sb.append("  /**\n");
        sb.append("   * Concrete endpoints that can be used in {@link ").append(className).append("}\n");
        sb.append("   */\n");
        sb.append("  @Getter\n");
        sb.append("  @AllArgsConstructor\n");
        sb.append("  public enum ").append(endpointEnum).append(" implements ApiEndpoint {\n\n");
        sb.append("    NO_ENDPOINT(Method.POST, \"/no/endpoint\", null);\n\n");
        sb.append("    private final Method method;\n");
        sb.append("    private final String defaultPath;\n");
        sb.append("    private final String pathProperty;\n");
        sb.append("    private final String apiName = ").append(constantName).append(".toString();\n\n");
        sb.append("    public static ").append(endpointEnum).append(" byString(String apiEndpointName) {\n");
        sb.append("      return Arrays.stream(values())\n");
        sb.append("          .filter(ae -> ae.apiEndpointNameEqualsIgnoreSpelling(apiEndpointName)).findFirst()\n");
        sb.append("          .orElseThrow(\n");
        sb.append("              () -> UnknownEndpointException.byEndpointAndApi(apiEndpointName, ")
                .append(constantName).append(".toString()));\n");
        sb.append("    }\n");
        sb.append("  }\n\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** Mirrors the exact authentication-provider skeleton for {@code method} (never called for {@link AuthenticationMethod#NONE}). */
    static @NotNull String buildProviderSource(@NotNull String base, @NotNull AuthenticationMethod method,
                                               @NotNull Map<String, AuthFieldValue> fieldValues) {
        String className = base + "AuthenticationProvider";
        String parentClass = switch (method) {
            case BASIC -> "BasicAuthenticationProvider";
            case CLIENT_CREDENTIALS -> "ClientCredentialsApiAuthenticationProvider";
            case PASSWORD -> "PasswordAuthenticationProvider";
            case AUTHORIZATION_CODE -> "AuthorizationCodeApiAuthenticationProvider";
            case NONE -> throw new IllegalArgumentException("NONE has no authentication provider.");
        };

        // An optional field left blank isn't overridden at all - the abstract base class's own
        // default implementation applies - so it contributes neither an import nor a getter.
        List<AuthenticationMethod.Field> fieldsToEmit = new ArrayList<>();
        for (AuthenticationMethod.Field field : method.fields()) {
            AuthFieldValue fieldValue = fieldValues.get(field.key());
            if (field.optional() && (fieldValue == null || fieldValue.value().isBlank())) continue;
            fieldsToEmit.add(field);
        }

        boolean usesContextParameter = fieldsToEmit.stream()
                .anyMatch(f -> sourceOf(fieldValues, f) == FieldValueSource.CONTEXT_PARAMETER);
        boolean usesEnvironmentVariable = fieldsToEmit.stream()
                .anyMatch(f -> sourceOf(fieldValues, f) == FieldValueSource.ENVIRONMENT_VARIABLE);

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(PROVIDER_IMPL_PACKAGE).append(";\n\n");
        if (usesContextParameter) {
            sb.append("import at.compax.rp.test.context.RPTAContext;\n");
        }
        if (usesEnvironmentVariable) {
            sb.append("import ").append(GENERIC_CONFIGURATION_FQN).append(";\n");
        }
        sb.append("import at.compax.rp.test.services.api.providers.").append(parentClass).append(";\n");
        sb.append("import org.springframework.stereotype.Component;\n\n");
        sb.append("@Component\n");
        sb.append("public class ").append(className).append("\n");
        sb.append("    extends ").append(parentClass).append(" {\n\n");
        sb.append("  @Override\n");
        sb.append("  public String getName() {\n");
        sb.append("    return \"").append(base).append("Authentication\";\n");
        sb.append("  }\n");

        for (AuthenticationMethod.Field field : fieldsToEmit) {
            String getterName = "get" + Character.toUpperCase(field.key().charAt(0)) + field.key().substring(1);
            AuthFieldValue fieldValue = fieldValues.getOrDefault(
                    field.key(), new AuthFieldValue("", FieldValueSource.CONTEXT_PARAMETER));
            String value = escapeJavaString(fieldValue.value());
            sb.append("\n  @Override\n");
            sb.append("  public String ").append(getterName).append("() {\n");
            if (fieldValue.source() == FieldValueSource.ENVIRONMENT_VARIABLE) {
                sb.append("    return GenericConfiguration.getProperty(\"").append(value).append("\");\n");
            } else {
                sb.append("    return RPTAContext.getInstance().getApiContext().getContextParameters()\n");
                sb.append("        .get(\"").append(value).append("\");\n");
            }
            sb.append("  }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static @NotNull FieldValueSource sourceOf(@NotNull Map<String, AuthFieldValue> fieldValues,
                                                       @NotNull AuthenticationMethod.Field field) {
        AuthFieldValue fieldValue = fieldValues.get(field.key());
        return fieldValue != null ? fieldValue.source() : FieldValueSource.CONTEXT_PARAMETER;
    }

    private static @NotNull String escapeJavaString(@NotNull String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
