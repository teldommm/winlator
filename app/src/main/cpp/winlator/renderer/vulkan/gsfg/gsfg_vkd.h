#pragma once
// Vulkan dispatch used by the GSFG engine. On device it is filled from the
// renderer's own VkTable; the host test fills it with recording stubs.
#include <vulkan/vulkan.h>

struct VkTable;

struct GsfgVkDispatch {
    PFN_vkAllocateDescriptorSets        AllocateDescriptorSets;
    PFN_vkAllocateMemory                AllocateMemory;
    PFN_vkBindBufferMemory              BindBufferMemory;
    PFN_vkBindImageMemory               BindImageMemory;
    PFN_vkCmdBindDescriptorSets         CmdBindDescriptorSets;
    PFN_vkCmdBindPipeline               CmdBindPipeline;
    PFN_vkCmdBlitImage                  CmdBlitImage;
    PFN_vkCmdDispatch                   CmdDispatch;
    PFN_vkCmdFillBuffer                 CmdFillBuffer;
    PFN_vkCmdPipelineBarrier            CmdPipelineBarrier;
    PFN_vkCmdPushConstants              CmdPushConstants;
    PFN_vkCreateBuffer                  CreateBuffer;
    PFN_vkCreateComputePipelines        CreateComputePipelines;
    PFN_vkCreateDescriptorPool          CreateDescriptorPool;
    PFN_vkCreateDescriptorSetLayout     CreateDescriptorSetLayout;
    PFN_vkCreateImage                   CreateImage;
    PFN_vkCreateImageView               CreateImageView;
    PFN_vkCreatePipelineLayout          CreatePipelineLayout;
    PFN_vkCreateSampler                 CreateSampler;
    PFN_vkCreateShaderModule            CreateShaderModule;
    PFN_vkDestroyBuffer                 DestroyBuffer;
    PFN_vkDestroyDescriptorPool         DestroyDescriptorPool;
    PFN_vkDestroyDescriptorSetLayout    DestroyDescriptorSetLayout;
    PFN_vkDestroyImage                  DestroyImage;
    PFN_vkDestroyImageView              DestroyImageView;
    PFN_vkDestroyPipeline               DestroyPipeline;
    PFN_vkDestroyPipelineLayout         DestroyPipelineLayout;
    PFN_vkDestroySampler                DestroySampler;
    PFN_vkDestroyShaderModule           DestroyShaderModule;
    PFN_vkFreeMemory                    FreeMemory;
    PFN_vkGetBufferMemoryRequirements   GetBufferMemoryRequirements;
    PFN_vkGetImageMemoryRequirements    GetImageMemoryRequirements;
    PFN_vkGetPhysicalDeviceMemoryProperties GetPhysicalDeviceMemoryProperties;
    PFN_vkGetPhysicalDeviceProperties   GetPhysicalDeviceProperties;
    PFN_vkUpdateDescriptorSets          UpdateDescriptorSets;
};

extern GsfgVkDispatch vkd;

#ifdef __ANDROID__
bool gsfgVkdInit(const VkTable& table);
#endif
bool gsfgVkdReady();
void gsfgVkdMarkReady();   // host tests
