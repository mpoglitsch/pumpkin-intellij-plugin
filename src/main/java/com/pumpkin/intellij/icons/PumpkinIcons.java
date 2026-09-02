package com.pumpkin.intellij.icons;

import com.intellij.openapi.util.IconLoader;

import javax.swing.*;

public final class PumpkinIcons {

    /**
     * Same artwork as the plugin's Marketplace/Settings icon ({@code META-INF/pluginIcon.svg}),
     * duplicated here at toolbar size. Loading pluginIcon.svg itself via IconLoader didn't
     * render correctly — that path is reserved for the platform's own plugin-descriptor
     * loading, not the general classpath-icon pipeline actions use — so this is a plain copy
     * under the normal icons/ location instead.
     */
    public static final Icon PUMPKIN = IconLoader.getIcon("/icons/pumpkin.svg", PumpkinIcons.class);

    private PumpkinIcons() {}
}
