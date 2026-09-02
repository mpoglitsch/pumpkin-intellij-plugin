package com.pumpkin.intellij.model;

import lombok.Value;
import org.jetbrains.annotations.NotNull;

/** A variable placeholder in a Process scenario name, e.g. {@code {customerName}}. */
@Value
public class PumpkinProcessVariable {
    @NotNull String name;
}
