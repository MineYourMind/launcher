/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.util;

import lombok.Data;

/**
 * Represents information about the current environment.
 */
@Data
public class Environment {

    private final Platform platform;
    private final String platformVersion;
    private final String arch;
    private final String javaBits;

    /**
     * Get an instance of the current environment.
     *
     * @return the current environment
     */
    public static Environment getInstance() {
        return new Environment(detectPlatform(), System.getProperty("os.version"), System.getProperty("os.arch"), System.getProperty("sun.arch.data.model"));
    }

    public String getArchBits() {
        String realArch = arch.contains("64") ? "64" : "32";

        // Windows tends to lie about its arch, so further steps are required.
        if (platform == Platform.WINDOWS) {
            String arch = System.getenv("PROCESSOR_ARCHITECTURE");
            String wow64Arch = System.getenv("PROCESSOR_ARCHITEW6432");
            realArch = arch.endsWith("64")
                    || wow64Arch != null && wow64Arch.endsWith("64")
                    ? "64" : "32";
        }
        return realArch;
    }

    /**
     * Detect the current platform.
     *
     * @return the current platform
     */
    public static Platform detectPlatform() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win"))
            return Platform.WINDOWS;
        if (osName.contains("mac"))
            return Platform.MAC_OS_X;
        if (osName.contains("solaris") || osName.contains("sunos"))
            return Platform.SOLARIS;
        if (osName.contains("linux"))
            return Platform.LINUX;
        if (osName.contains("unix"))
            return Platform.LINUX;

        return Platform.UNKNOWN;
    }

}
