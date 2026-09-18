package com.pumpkin.intellij.proxy;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Everything the user entered in {@link AddApiProxyDialog}, assembled once on OK and handed to
 * {@link ApiProxyCodeGenerator}.
 *
 * @param fieldValues keyed by {@link AuthenticationMethod.Field#key()} - empty when
 *                     {@code authenticationMethod} is {@link AuthenticationMethod#NONE}.
 * @param environmentBaseUrls zero or more per-environment base URL overrides - see
 *                     {@link EnvironmentBaseUrl}; empty rows are already filtered out.
 */
public record NewApiProxySpec(
        @NotNull String proxyName,
        @NotNull AuthenticationMethod authenticationMethod,
        @NotNull Map<String, AuthFieldValue> fieldValues,
        @NotNull List<EnvironmentBaseUrl> environmentBaseUrls
) {
}
