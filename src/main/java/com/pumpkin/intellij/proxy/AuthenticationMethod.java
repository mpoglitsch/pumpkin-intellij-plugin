package com.pumpkin.intellij.proxy;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The authentication methods {@link AddApiProxyDialog} offers, each carrying the ordered list of
 * context-parameter-name fields it needs (in the exact order they should be asked for and, later,
 * overridden as getters in the generated {@code ApiAuthenticationProvider}).
 */
public enum AuthenticationMethod {

    NONE("None", List.of()),
    BASIC("Basic", List.of(
            new Field("username", "Username (context parameter)"),
            new Field("password", "Password (context parameter)"))),
    CLIENT_CREDENTIALS("Client Credentials", List.of(
            new Field("clientId", "ClientId (context parameter)"),
            new Field("clientSecret", "ClientSecret (context parameter)"))),
    PASSWORD("Password Flow", List.of(
            new Field("clientId", "ClientId (context parameter)"),
            new Field("clientSecret", "ClientSecret (context parameter)"),
            new Field("username", "Username (context parameter)"),
            new Field("password", "Password (context parameter)")));

    private final String displayName;
    private final List<Field> fields;

    AuthenticationMethod(@NotNull String displayName, @NotNull List<Field> fields) {
        this.displayName = displayName;
        this.fields = fields;
    }

    public @NotNull List<Field> fields() {
        return fields;
    }

    @Override
    public String toString() {
        return displayName;
    }

    /** @param key stable identifier (matches the generated getter name, capitalized) - not the display label. */
    public record Field(@NotNull String key, @NotNull String label) {
    }
}
