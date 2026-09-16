package com.pumpkin.intellij.proxy;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The authentication methods {@link AddApiProxyDialog} offers, each carrying the ordered list of
 * fields it needs (in the exact order they should be asked for and, later, overridden as getters
 * in the generated {@code ApiAuthenticationProvider}). Each field's value can independently be
 * sourced from a context parameter or an environment variable - see {@link FieldValueSource} -
 * that choice isn't fixed per method or per field, so it isn't part of this enum.
 */
public enum AuthenticationMethod {

    NONE("None", List.of()),
    BASIC("Basic", List.of(
            new Field("username", "Username"),
            new Field("password", "Password"))),
    CLIENT_CREDENTIALS("Client Credentials", List.of(
            new Field("authUrl", "Authentication URL (optional)", true),
            new Field("clientId", "ClientId"),
            new Field("clientSecret", "ClientSecret"))),
    PASSWORD("Password Flow", List.of(
            new Field("authUrl", "Authentication URL (optional)", true),
            new Field("clientId", "ClientId"),
            new Field("clientSecret", "ClientSecret"),
            new Field("username", "Username"),
            new Field("password", "Password"))),
    AUTHORIZATION_CODE("Authorization Code", List.of(
            new Field("authUrl", "Authentication URL (optional)", true),
            new Field("clientId", "ClientId"),
            new Field("clientSecret", "ClientSecret"),
            new Field("redirectUri", "RedirectUri"),
            new Field("tokenUrl", "TokenUrl"),
            new Field("username", "Username"),
            new Field("password", "Password")));

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

    /**
     * @param key      stable identifier (matches the generated getter name, capitalized) - not the display label.
     * @param optional when {@code true} and left blank, the generated provider doesn't override this field's
     *                 getter at all - relying on the abstract base class's own default implementation - instead
     *                 of failing dialog validation the way a blank required field would.
     */
    public record Field(@NotNull String key, @NotNull String label, boolean optional) {
        public Field(@NotNull String key, @NotNull String label) {
            this(key, label, false);
        }
    }
}
