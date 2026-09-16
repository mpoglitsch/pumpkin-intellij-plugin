package com.pumpkin.intellij.proxy;

/**
 * Where a generated {@code ApiAuthenticationProvider} field getter reads its value from - offered
 * per-field in {@link AddApiProxyDialog} for every {@link AuthenticationMethod}, not fixed per
 * method.
 */
public enum FieldValueSource {

    CONTEXT_PARAMETER("Context Parameter"),
    ENVIRONMENT_VARIABLE("Environment Variable");

    private final String displayName;

    FieldValueSource(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
