#pragma once
// ============================================================================
// gsfg_engine - the compositor's handle on native GSFG frame generation.
//
// Runs the GSFG 1.1 compute graph built by gsfg_graph (35 SPIR-V shaders,
// ~63 dispatches for the first generated frame, 21 for each further one):
//
//   prepare(w, h, format)            build/rebuild the graph for this size
//   plan(capacity, sourceFrames)     how many frames to generate this time
//   process(cmd, source, ...)        take frame N in (blit + feature encode);
//                                    when generating, also the shared stages
//   generateInto(cmd, g)             synthesise generated frame g
//   finalImage(g)                    image holding frame g, in GENERAL; the
//                                    caller blits it to the swapchain
//
// Ordering is unchanged from the old engine: generated frames lie BETWEEN N-1
// and N, so they are presented first and the real frame last.
//
// The graph (resources, dispatches, push constants, bindings; barriers derived
// from read/write sets) is described in gsfg_graph.h.
// ============================================================================

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include <vulkan/vulkan.h>

#include "gsfg_pacer.hpp"
#include "gsfg_graph.h"

namespace gsfg {

class Pipelines;
class Chain;

// Hard ceiling on generated frames per source frame (2x..4x -> 1..3).
constexpr uint32_t kMaxGenerations = 3;

class Engine {
public:
    // Out-of-line on purpose: the members are unique_ptrs to types that are
    // only complete in gsfg_engine.cpp.
    Engine();
    ~Engine();

    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    // `packPath` is the shader pack copied out of the APK assets (gsfg_shaders.bin).
    bool init(VkDevice device, VkPhysicalDevice physicalDevice, const std::string& packPath);

    bool valid() const { return pipelines_ != nullptr && !unavailable_; }
    bool unavailable() const { return unavailable_; }

    void configure(uint32_t multiplier, uint32_t targetRate, float flowScale, float refreshRate);
    void setRefreshRate(float refreshRate);
    void setGuestExtent(uint32_t width, uint32_t height);

    bool needsRebuild(uint32_t width, uint32_t height, VkFormat format) const;
    bool prepare(uint32_t width, uint32_t height, VkFormat format);

    // Generated-frame count for this source frame; 0 until three real frames
    // are in the history ring and the rates have settled.
    uint32_t plan(uint32_t capacity, uint64_t sourceFrames);

    // `source` is the just-composited frame and must be in GENERAL.
    void process(VkCommandBuffer cmd, VkImage source, uint32_t width, uint32_t height,
                 uint32_t generations);

    void generateInto(VkCommandBuffer cmd, uint32_t generation);
    VkImage finalImage(uint32_t generation) const;

    float sourceRate() const;
    float loopRate() const { return presentedRate_; }
    void  setPresentedRate(float fps) { presentedRate_ = fps; }

    void forgetTargets() {}     // the graph owns its images; nothing to forget
    void reset();

    // Test hooks.
    // Resource layout of the graph (gsfg_graph.h); Reference reproduces the reference
    // implementation's allocation for trace comparisons. Takes effect on the next build.
    void setLayout(Layout layout);
    int  selectedVariant() const { return variant_; }
    int  selectedClass() const { return cls_; }

private:
    float effectiveFlowScale(uint32_t width) const;

    VkDevice         device_{};
    VkPhysicalDevice physical_{};
    std::unique_ptr<Pipelines> pipelines_;
    std::unique_ptr<Chain>     chain_;
    Pacer  pacer_;
    PacerPlan plan_{};
    int    variant_{0};
    Layout layout_{Layout::Compact};
    int    cls_{1};

    VkExtent2D builtExtent_{};
    VkExtent2D peakGuestExtent_{};
    VkFormat   builtFormat_{VK_FORMAT_UNDEFINED};
    float      builtFlowScale_{};
    float      flowScale_{1.0f};
    float      presentedRate_{};

    uint64_t frameCount_{};
    uint64_t lastCount_{};
    uint32_t lastGenerations_{};
    uint64_t planCalls_{};
    uint32_t warmStreak_{};
    bool     warm_{};
    bool     generating_{};
    bool     temporalValid_{false};   // reference context +0x528
    uint32_t idleFrames_{0};          // reference context +0x55c: ingest-only frames in a row
    bool     unavailable_{};
};

} // namespace gsfg
