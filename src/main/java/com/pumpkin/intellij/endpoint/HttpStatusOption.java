package com.pumpkin.intellij.endpoint;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A selectable success response code in {@link AddEndpointDialog}. {@link #enumName()} is the
 * Spring {@code org.springframework.http.HttpStatus} constant name used when generating
 * {@code determineExpectedResponseCode}.
 */
record HttpStatusOption(int code, @NotNull String enumName, @NotNull String label) {

    static final HttpStatusOption OK = new HttpStatusOption(200, "OK", "OK");

    static final List<HttpStatusOption> ALL = List.of(
            OK,
            new HttpStatusOption(201, "CREATED", "Created"),
            new HttpStatusOption(202, "ACCEPTED", "Accepted"),
            new HttpStatusOption(204, "NO_CONTENT", "No Content"),
            new HttpStatusOption(206, "PARTIAL_CONTENT", "Partial Content")
    );

    @Override
    public String toString() {
        return code + " - " + label;
    }
}
