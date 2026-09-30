// See gsfg_probe.h.
#include "gsfg_probe.h"
#include "../VulkanRendererContext.h"

#include <cstdio>

namespace gsfg {

FeatureSupport queryFeatures(const VkTable& vk, VkPhysicalDevice pd) {
    FeatureSupport fs{};
    if (pd == VK_NULL_HANDLE || !vk.GetPhysicalDeviceProperties) return fs;

    VkPhysicalDeviceProperties props{};
    vk.GetPhysicalDeviceProperties(pd, &props);
    fs.deviceApiVersion = props.apiVersion;
    fs.apiAtLeast12 = props.apiVersion >= VK_API_VERSION_1_2;

    // Below 1.2 there is no VkPhysicalDeviceVulkan12Features to chain, so the
    // device is left exactly as the renderer always created it.
    if (!fs.apiAtLeast12 || !vk.GetPhysicalDeviceFeatures2) return fs;

    VkPhysicalDeviceVulkan12Features v12{};
    v12.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
    VkPhysicalDeviceFeatures2 f2{};
    f2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    f2.pNext = &v12;
    vk.GetPhysicalDeviceFeatures2(pd, &f2);

    fs.queried = true;
    fs.shaderFloat16 = v12.shaderFloat16 == VK_TRUE;
    return fs;
}

bool probeFormats(const VkTable& vk, VkPhysicalDevice pd, VkFormat swapchainFmt) {
    if (pd == VK_NULL_HANDLE || swapchainFmt == VK_FORMAT_UNDEFINED
        || !vk.GetPhysicalDeviceFormatProperties) return false;

    // Only 8-bit UNORM is handled: the graph is fed and drained through blits, so
    // a UNORM swapchain (RGBA or BGRA) round-trips exactly. sRGB would be
    // re-encoded by the blit and is left to the ordinary path.
    if (swapchainFmt != VK_FORMAT_R8G8B8A8_UNORM && swapchainFmt != VK_FORMAT_B8G8R8A8_UNORM)
        return false;

    const VkFormatFeatureFlags graphNeed =
        VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
    const VkFormat graphFormats[] = {
        VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R32G32B32A32_SFLOAT};
    for (VkFormat f : graphFormats) {
        VkFormatProperties fp{};
        vk.GetPhysicalDeviceFormatProperties(pd, f, &fp);
        if ((fp.optimalTilingFeatures & graphNeed) != graphNeed) return false;
    }
    // The graph's own RGBA8 image is blitted into the swapchain format.
    VkFormatProperties rgba{};
    vk.GetPhysicalDeviceFormatProperties(pd, VK_FORMAT_R8G8B8A8_UNORM, &rgba);
    const VkFormatFeatureFlags rgbaBlit = VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT;
    if ((rgba.optimalTilingFeatures & rgbaBlit) != rgbaBlit) return false;

    // The composite target: drawn into, sampled, and both ends of a blit.
    VkFormatProperties sw{};
    vk.GetPhysicalDeviceFormatProperties(pd, swapchainFmt, &sw);
    const VkFormatFeatureFlags swNeed =
          VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
        | VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT;
    return (sw.optimalTilingFeatures & swNeed) == swNeed;
}

bool probeLinearBlit(const VkTable& vk, VkPhysicalDevice pd, VkFormat fmt) {
    if (pd == VK_NULL_HANDLE || fmt == VK_FORMAT_UNDEFINED
        || !vk.GetPhysicalDeviceFormatProperties) return false;
    VkFormatProperties fp{};
    vk.GetPhysicalDeviceFormatProperties(pd, fmt, &fp);
    return (fp.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0;
}

void explain(Caps& caps) {
    const FeatureSupport& f = caps.features;
    const char* why = nullptr;
    if (!f.queried) {
        why = f.deviceApiVersion < VK_API_VERSION_1_2 ? "device Vulkan version below 1.2"
                                                      : "vkGetPhysicalDeviceFeatures2 unavailable";
    } else if (!f.shaderFloat16) {
        why = "driver lacks shaderFloat16";
    } else if (!caps.featuresEnabled) {
        why = "required features not enabled at device creation";
    } else if (!caps.formatsOk) {
        why = "swapchain is not 8-bit UNORM or the graph formats lack storage/blit support";
    }
    if (why) snprintf(caps.reason, sizeof(caps.reason), "unsupported: %s", why);
    else snprintf(caps.reason, sizeof(caps.reason), "supported (device Vulkan %u.%u.%u, fmt %d)",
                  VK_VERSION_MAJOR(f.deviceApiVersion), VK_VERSION_MINOR(f.deviceApiVersion),
                  VK_VERSION_PATCH(f.deviceApiVersion), (int)caps.probedFormat);
}

} // namespace gsfg
