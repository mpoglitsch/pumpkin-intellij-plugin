package com.pumpkin.intellij.model;

import lombok.Value;
import org.jetbrains.annotations.NotNull;

/** A required table parameter declared via {@code @processRequired(name,...)}. */
@Value
public class PumpkinProcessParameter {
    @NotNull String name;
}
