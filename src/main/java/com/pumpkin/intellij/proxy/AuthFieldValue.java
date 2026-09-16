package com.pumpkin.intellij.proxy;

import org.jetbrains.annotations.NotNull;

/**
 * One {@link AuthenticationMethod.Field}'s entered value plus where the generated getter should
 * read it from at runtime - either a context-parameter name (looked up via {@code
 * RPTAContext.getInstance().getApiContext().getContextParameters().get(...)}) or an environment
 * variable/property name (looked up via {@code GenericConfiguration.getProperty(...)}). See
 * {@link ApiProxyCodeGenerator#buildProviderSource}.
 */
public record AuthFieldValue(@NotNull String value, @NotNull FieldValueSource source) {
}
