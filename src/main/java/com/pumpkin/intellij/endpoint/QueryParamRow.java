package com.pumpkin.intellij.endpoint;

import org.jetbrains.annotations.NotNull;

/** One row of the "Query parameters" table in {@link AddEndpointDialog}. */
record QueryParamRow(@NotNull String name, boolean required) {
}
