#pragma once
// ============================================================================
// gsfg_engine - the compositor's handle on native GSFG frame generation.
//
// Replays, from static tables, the compute graph of the reference GSFG 1.1
// implementation (35 SPIR-V shaders, ~63 dispatches per generated frame):
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
// Dispatch shape, push constants, descriptor bindings and barriers come from
// traces of the reference library; see tools/gsfg_export in the study archive.
// ============================================================================

#include <atomic>
#include <cstdint>
#include <memory>
#include <thread>
#include <string>
#include <vector>

#include <vulkan/vulkan.h>

#include "gsfg_pacer.hpp"

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
    // Synchronous: compiles every pipeline on the calling thread (used by tests).
    bool init(VkDevice device, VkPhysicalDevice physicalDevice, const std::string& packPath);

    // Asynchronous: pipeline compilation runs on a worker thread so a slow (or stuck) driver
    // compiler can never freeze the render loop. Poll initState(); frames are simply not
    // generated until it reports Ready.
    enum class InitState { Idle, Pending, Ready, Failed };
    bool beginInit(VkDevice device, VkPhysicalDevice physicalDevice, const std::string& packPath);
    InitState initState() const { return (InitState)state_.load(std::memory_order_acquire); }
    // While Pending: which pipeline is being compiled and for how long (for the watchdog log).
    int    pendingPipeline() const { return progPipe_.load(std::memory_order_relaxed); }
    double pendingSeconds() const;

    bool valid() const { return initState() == InitState::Ready && pipelines_ != nullptr && !unavailable_; }
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
    bool     needSeed_{true};
    bool     unavailable_{};

    std::atomic<int>     state_{0};          // InitState
    std::atomic<int>     progPipe_{-1};
    std::atomic<int64_t> progSinceMs_{0};
    std::thread          initThread_;

    // Diagnostics. Knobs (adb shell setprop debug.gsfg.<name> <int>):
    //   trace  1 = log every frame (default: the first 90 frames after each graph build + state changes)
    //   show   1 = generated frames show the CURRENT input frame instead of the graph output. The graph
    //              still runs, so cost and pacing are unchanged; it separates compositor plumbing
    //              (blits, swapchain, pacing, cursor) from the shaders.
    int      traceLeft_{0};
    bool     traceAll_{false};
    int      showMode_{0};
    VkImage  lastSource_{};
    uint32_t lastPlanGens_{~0u};
    bool     lastWarm_{false};
    bool     lastGenerating_{false};
    void     refreshKnobs();
    bool     tracing() const { return traceAll_ || traceLeft_ > 0; }
};

} // namespace gsfg
