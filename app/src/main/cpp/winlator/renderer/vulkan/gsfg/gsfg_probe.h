#pragma once
// ============================================================================
// gsfg_probe - capability gate for native (compositor-side) GSFG frame
// generation.
//
// The GSFG graph is 35 compute shaders (SPIR-V 1.3, Logical/GLSL450 memory
// model). The only device feature they need beyond core Vulkan 1.1 is
// shaderFloat16 (OpCapability Float16, arithmetic only - no 16-bit storage).
// Storage images use Rgba8 / Rgba16f / Rgba32f, which are core formats. The
// composite target is never a storage image: frames enter and leave the graph
// through blits, so any 8-bit UNORM swapchain format works.
// ============================================================================

#include <vulkan/vulkan.h>
#include <cstdint>

struct VkTable;

namespace gsfg {

struct FeatureSupport {
    bool queried       = false;   // vkGetPhysicalDeviceFeatures2 resolved
    bool apiAtLeast12  = false;
    bool shaderFloat16 = false;
    uint32_t deviceApiVersion = 0;

    bool deviceGatesPass() const { return queried && apiAtLeast12 && shaderFloat16; }
};

struct Caps {
    FeatureSupport features;
    bool featuresEnabled = false;      // the chain was passed to vkCreateDevice
    bool formatsOk       = false;      // graph image formats + blit on the swapchain format
    bool linearBlitOnSwapchainFormat = false;
    VkFormat probedFormat = VK_FORMAT_UNDEFINED;
    char reason[160]     = "not probed";

    bool supported() const { return featuresEnabled && features.deviceGatesPass() && formatsOk; }
};

FeatureSupport queryFeatures(const VkTable& vk, VkPhysicalDevice pd);

// Graph formats (RGBA8 / RGBA16F / RGBA32F) usable as storage + sampled, and the
// swapchain format usable as blit source and destination.
bool probeFormats(const VkTable& vk, VkPhysicalDevice pd, VkFormat swapchainFmt);

bool probeLinearBlit(const VkTable& vk, VkPhysicalDevice pd, VkFormat fmt);

void explain(Caps& caps);

} // namespace gsfg
