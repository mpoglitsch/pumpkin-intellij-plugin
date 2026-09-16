package com.pumpkin.intellij.proxy;

import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Everything the user entered in {@link AddApiProxyDialog}, assembled once on OK and handed to
 * {@link ApiProxyCodeGenerator}.
 *
 * @param fieldValues keyed by {@link AuthenticationMethod.Field#key()} - empty when
 *                     {@code authenticationMethod} is {@link AuthenticationMethod#NONE}.
 */
public record NewApiProxySpec(
        @NotNull String proxyName,
        @NotNull AuthenticationMethod authenticationMethod,
        @NotNull Map<String, AuthFieldValue> fieldValues
) {
}
