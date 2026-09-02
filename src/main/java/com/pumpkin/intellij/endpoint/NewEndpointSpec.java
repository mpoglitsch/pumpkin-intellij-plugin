package com.pumpkin.intellij.endpoint;

import com.intellij.psi.PsiClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Everything the user entered in {@link AddEndpointDialog}, assembled once on OK and handed to
 * {@link EndpointCodeGenerator}.
 */
public record NewEndpointSpec(
        @NotNull PsiClass proxyClass,
        @NotNull String endpointName,
        @NotNull String httpMethod,
        @NotNull String path,
        @NotNull List<QueryParamRow> queryParams,
        @NotNull List<HeaderRow> headers,
        @Nullable String bodyJson,
        @NotNull HttpStatusOption status
) {
}
