// See gsfg_vkd.h.
#include "gsfg_vkd.h"

GsfgVkDispatch vkd;

namespace { bool g_ready = false; }

bool gsfgVkdReady() { return g_ready; }
void gsfgVkdMarkReady() { g_ready = true; }

#ifdef __ANDROID__
#include "../VulkanRendererContext.h"
#include <android/log.h>

bool gsfgVkdInit(const VkTable& table) {
    bool ok = true;
#define COPY(fn)                                                                   \
    do {                                                                           \
        vkd.fn = table.fn;                                                         \
        if (!vkd.fn) {                                                             \
            __android_log_print(ANDROID_LOG_ERROR, "GsfgVkd",                      \
                                "entry point vk" #fn " did not resolve");          \
            ok = false;                                                            \
        }                                                                          \
    } while (0)
    COPY(AllocateDescriptorSets); COPY(AllocateMemory); COPY(BindBufferMemory);
    COPY(BindImageMemory); COPY(CmdBindDescriptorSets); COPY(CmdBindPipeline);
    COPY(CmdBlitImage); COPY(CmdDispatch); COPY(CmdFillBuffer); COPY(CmdPipelineBarrier);
    COPY(CmdPushConstants); COPY(CreateBuffer); COPY(CreateComputePipelines);
    COPY(CreateDescriptorPool); COPY(CreateDescriptorSetLayout); COPY(CreateImage);
    COPY(CreateImageView); COPY(CreatePipelineLayout); COPY(CreateSampler);
    COPY(CreateShaderModule); COPY(DestroyBuffer); COPY(DestroyDescriptorPool);
    COPY(DestroyDescriptorSetLayout); COPY(DestroyImage); COPY(DestroyImageView);
    COPY(DestroyPipeline); COPY(DestroyPipelineLayout); COPY(DestroySampler);
    COPY(DestroyShaderModule); COPY(FreeMemory); COPY(GetBufferMemoryRequirements);
    COPY(GetImageMemoryRequirements); COPY(GetPhysicalDeviceMemoryProperties);
    COPY(GetPhysicalDeviceProperties); COPY(UpdateDescriptorSets);
#undef COPY
    g_ready = ok;
    return ok;
}
#endif
