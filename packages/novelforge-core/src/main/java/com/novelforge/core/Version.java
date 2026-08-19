package com.novelforge.core;

/**
 * NovelForge version constants. Updated on release.
 */
public final class Version {
    public static final String VERSION = "0.6.0";
    public static final String NAME = "NovelForge";

    private Version() {} // prevent instantiation

    public static String full() {
        return NAME + " v" + VERSION;
    }
}
