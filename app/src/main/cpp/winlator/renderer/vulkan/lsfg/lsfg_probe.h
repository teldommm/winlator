#pragma once
// ============================================================================
// lsfg_probe — capability gate for native (compositor-side) LSFG frame
// generation.
//
// The Lossless Scaling chain is 25 compute shaders that DXVK's DXBC translator
// emits as SPIR-V 1.5 (vendored DXVK patched down from 1.6, see
// dxbc_compiler.cpp) with OpCapability VulkanMemoryModel and
// StorageImageWriteWithoutFormat. Three consequences, all checked here:
//
//   * SPIR-V 1.5 needs a Vulkan 1.2 DEVICE (not just instance); memory model
//     is core there.
//   * vulkanMemoryModel, shaderStorageImageWriteWithoutFormat and
//     shaderStorageImageExtendedFormats must be ENABLED at device creation.
//     Today the renderer enables no features at all, so all three are off.
//   * `generate` writes into a storage image, and Android swapchain formats
//     are frequently not storage-capable — so the format is probed separately
//     once the swapchain has picked one.
//
// That is the DXBC-TRANSLATED variant only. Some Lossless.dll builds also ship
// precompiled SPIR-V (base+49 fp16, base+98 fp32; checked on the real DLL:
// 25/25 modules each). Those are SPIR-V 1.0 with the GLSL450 memory model and
// need only StorageImageWriteWithoutFormat + StorageImageExtendedFormats (+
// ImageQuery, core) - no Vulkan 1.2, no vulkanMemoryModel. The fp16 set adds
// OpCapability Float16 (23 of 25 modules), i.e. shaderFloat16. So the gate is
// evaluated PER VARIANT: supported(variant).
//
// A device failing any gate reports unsupported UP FRONT, with a reason, so
// the UI can grey the engine out instead of failing later inside
// vkCreateShaderModule or vkCreateComputePipelines.
// ============================================================================

#include <vulkan/vulkan.h>
#include <cstdint>

#include "lsfg_dll.h"

struct VkTable;

namespace lsfg {

// What the physical device OFFERS. Queried before vkCreateDevice; what we
// actually enable is recorded in Caps below.
struct FeatureSupport {
    bool queried                     = false;  // vkGetPhysicalDeviceFeatures2 resolved
    bool apiAtLeast12                = false;
    bool vulkanMemoryModel           = false;
    bool vulkanMemoryModelDeviceScope= false;
    bool storageImageWriteWithoutFormat = false;
    bool storageImageExtendedFormats = false;
    bool shaderFloat16               = false;
    uint32_t deviceApiVersion        = 0;

    // Gates every variant needs (the precompiled SPIR-V 1.0 sets).
    bool nativeGatesPass() const {
        return queried && storageImageWriteWithoutFormat && storageImageExtendedFormats;
    }
    // Additional gates of the DXVK-translated set (SPIR-V 1.5 + memory model).
    bool translatedGatesPass() const {
        return nativeGatesPass() && apiAtLeast12 && vulkanMemoryModel;
    }
};

struct Caps {
    FeatureSupport features;
    bool featuresEnabled     = false;  // storage-image features passed to vkCreateDevice
    bool memoryModelEnabled  = false;  // vulkanMemoryModel passed to vkCreateDevice
    bool float16Enabled      = false;  // shaderFloat16 passed to vkCreateDevice
    bool storageOnSwapchainFormat = false;
    bool linearBlitOnSwapchainFormat = false;
    VkFormat probedFormat  = VK_FORMAT_UNDEFINED;
    char reason[160]       = "not probed";

    // Can this device run a cache of the given variant? Variant::None (not
    // known yet) is judged by the strictest set, the translated one.
    bool supported(Variant v) const {
        if (!featuresEnabled || !features.nativeGatesPass() || !storageOnSwapchainFormat)
            return false;
        switch (v) {
            case Variant::SpirvFp32: return true;
            case Variant::SpirvFp16: return float16Enabled;
            default:                 return memoryModelEnabled && features.translatedGatesPass();
        }
    }
};

FeatureSupport queryFeatures(const VkTable& vk, VkPhysicalDevice pd);

bool probeStorageFormat(const VkTable& vk, VkPhysicalDevice pd, VkFormat fmt);

bool probeLinearBlit(const VkTable& vk, VkPhysicalDevice pd, VkFormat fmt);

// Fill caps.reason for the given variant (default: what this device can run
// at best, i.e. the precompiled fp32 set, noting if translation is out).
void explain(Caps& caps, Variant v = Variant::None);

} // namespace lsfg
