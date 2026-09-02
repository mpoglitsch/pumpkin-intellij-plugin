package com.pumpkin.intellij.endpoint;

import org.jetbrains.annotations.NotNull;

/**
 * One row of the "Headers" table in {@link AddEndpointDialog}: the provided-parameter name
 * (e.g. {@code "myHeader"}) and the outgoing HTTP header key (e.g. {@code "MY-HEADER"}).
 */
record HeaderRow(@NotNull String providedName, @NotNull String headerKey) {
}
