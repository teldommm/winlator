package com.winlator.cmod.core;

/**
 * Resolves the value for WRAPPER_VK_VERSION ("major.minor.patch").
 *
 * The wrapper writes this value verbatim into VkPhysicalDeviceProperties::apiVersion, so it must
 * never exceed what the underlying driver really supports, otherwise guests would be told that
 * Vulkan 1.4 features exist when the driver cannot back them.
 */
public final class VulkanVersion {
    private VulkanVersion() {}

    /** Parses "M.m" or "M.m.p". Returns {major, minor, patch} or null if not parseable. */
    public static int[] parse(String version) {
        if (version == null) return null;
        String[] parts = version.trim().split("\\.");
        if (parts.length < 2) return null;
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
            if (major < 1 || minor < 0 || patch < 0) return null;
            return new int[]{major, minor, patch};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * @param requested     version chosen in settings, e.g. "1.4" (null/invalid falls back to default)
     * @param driverVersion version reported by the selected driver, e.g. "1.4.318" or "Unknown"
     * @param fallbackDriverVersion version reported by the system driver, used if the first is unknown
     */
    public static String resolve(String requested, String driverVersion, String fallbackDriverVersion) {
        int[] req = parse(requested);
        if (req == null) req = parse(DefaultVersion.VULKAN);

        int[] driver = parse(driverVersion);
        if (driver == null) driver = parse(fallbackDriverVersion);

        int major = req[0], minor = req[1];
        int patch = 0;
        if (driver != null) {
            // Never advertise more than the driver provides.
            if (driver[0] < major || (driver[0] == major && driver[1] < minor)) {
                major = driver[0];
                minor = driver[1];
            }
            patch = driver[2];
        }
        return major + "." + minor + "." + patch;
    }
}
