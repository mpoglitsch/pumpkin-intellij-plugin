package com.pumpkin.intellij.icons;

import com.intellij.openapi.util.IconLoader;
import com.intellij.util.IconUtil;

import javax.swing.*;

public final class PumpkinIcons {

    private static final Icon PLUGIN_ICON = IconLoader.getIcon("/META-INF/pluginIcon.svg", PumpkinIcons.class);

    /** Same artwork as the plugin's Marketplace/Settings icon (40x40), scaled down for the toolbar button. */
    public static final Icon PUMPKIN = IconUtil.scale(PLUGIN_ICON, null, 16f / 40f);

    private PumpkinIcons() {}
}
