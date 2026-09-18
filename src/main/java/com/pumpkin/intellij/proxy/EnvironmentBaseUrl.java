package com.pumpkin.intellij.proxy;

import org.jetbrains.annotations.NotNull;

/**
 * One environment's base URL for a new proxy, entered in {@link AddApiProxyDialog}. Written into
 * that environment's own {@code testsuite_configuration_<environment>.properties} file as {@code
 * <baseUrlProperty>=<baseUrl>} by {@link ApiProxyCodeGenerator} - see {@link EnvironmentResolver}
 * for how the set of known environments (and each one's properties file) is discovered.
 */
public record EnvironmentBaseUrl(@NotNull String environment, @NotNull String baseUrl) {
}
