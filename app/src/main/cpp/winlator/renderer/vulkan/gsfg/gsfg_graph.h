#pragma once
// ============================================================================
// gsfg_graph - the GSFG 1.1 compute graph, built in code.
//
// buildGraph() describes, for one capture size / flow scale / device variant,
// every image and buffer of the graph and the dispatch sequences that run it:
//
//   encode     per source frame: flow-resolution copy, image pyramid, and the
//              seven feature levels, written into the frame's ring slot
//   temporal   UI/stability mask over the last three frames (generating only)
//   flow       global prior -> coarse levels 6..3 -> primary levels 2..0,
//              plus a local branch (levels 2..1) started from scratch
//   blend      primary and local estimates merged at level 2
//   synth      guide -> evidence -> final frame at capture resolution
//   prior      carries this frame's level-0 flow into the next frame
//
// Generated frames at evenly spaced times (t_i = i / (n + 1), as the reference
// checks to 1e-6) share the flow estimate up to primary level 1, computed once
// at the midpoint; from the finest primary level on, every generated frame has
// its own block (kGenBlock dispatches). At any other times every generated
// frame runs the whole flow at its own t (kGenBlock + kFlowBlock dispatches).
// A single generated frame always runs the flow at its own t.
//
// The shapes, push constants and bindings are those of the reference
// implementation (libGameScopeV2.so), as recovered from its traces. Barriers
// are not stored: they follow from the read/write sets of the dispatches.
//
// Two resource layouts compute the same frames:
//   Reference  resources exactly as the reference allocates them (what the
//              trace comparisons check)
//   Compact    the default: the flow-resolution copy and the image pyramid
//              exist once instead of once per ring slot (they never outlive
//              the encode of their own frame), and only two copies of the
//              input frame are kept (synthesis reads frames N-1 and N only).
//              Verified bit-exact against Reference on a real Vulkan device.
// ============================================================================

#include <cstdint>
#include <string>
#include <vector>

#include <vulkan/vulkan.h>

namespace gsfg {

constexpr int kLevels   = 7;     // pyramid and feature levels 0..6
constexpr int kRing     = 3;     // source frames whose features are kept: current, previous, oldest
constexpr int kPhases   = 6;     // descriptor phases: frame count % 6 covers rings of 3 and of 2
constexpr int kGenBlock = 21;    // dispatches per generated frame (finest primary level .. final)
constexpr int kFlowBlock = 26;   // global prior + coarse levels + primary levels 2..1
constexpr int kMaxGenerated = 6; // generated frames per source frame the reference accepts
constexpr int kMaxBind  = 13;

// Sizes derived from the capture size W x H and the flow scale S.
struct Extent { int w = 0, h = 0; int area() const { return w * h; } };

struct Geometry {
    Extent input;              // capture size
    Extent quarter;            // input >> 2: synthesis evidence resolution
    Extent flow;               // max(64, round(input * S)): base of the image pyramid
    Extent pyr[kLevels];       // image pyramid; pyr[0] = flow. Halves until a side would drop below 4.
    Extent feat[kLevels];      // feature/flow levels; feat[0] = flow >> 2. Halves until a side would reach 0.
    int    featOffset[kLevels + 1];   // running sum of feature-level areas (texel offsets into the mask pyramid)
    int    pyrLevels;          // pyramid levels that actually differ from the one above (reference: NDP)
    int    cls;                // 0: S < 0.5, 1: 0.5 <= S < 1, 2: S == 1 (no downscale pass)
};

Geometry makeGeometry(uint32_t width, uint32_t height, float scale);

// ---- resources ---------------------------------------------------------------
struct ImageDesc {
    VkFormat          format;
    uint32_t          width, height, layers;
    VkImageUsageFlags usage;
    std::string       name;
};

struct BufferDesc {
    VkDeviceSize size;
    std::string  name;
};

enum class BindKind : uint8_t { Sampled, Storage, Buffer };

// One descriptor. `res` is an image index (Sampled/Storage) or a buffer index,
// one per phase (frame count % Graph::phases). `cold` (if >= 0) replaces it on
// a generating frame without valid temporal state.
struct Binding {
    uint8_t  binding;
    BindKind kind;
    int8_t   sampler;          // Sampled only: 0 clamp-to-border, 1 clamp-to-edge
    uint16_t res[kPhases];
    int16_t  cold;
};

struct Dispatch {
    uint8_t  pipe;
    uint8_t  barrier;          // bit0 compute->compute barrier, bit1 full barrier (stage boundary)
    const char* stage;
    std::vector<Binding> bind;
    int32_t  ints[20];
    float    floats[8];
    uint32_t groups[3];
    bool     timed;            // floats[0] is the time of the generated frame it belongs to
};

// The dispatch list for n generated frames. Dispatches marked `timed` carry
// placeholder times (evenly spaced); the engine writes the real ones.
struct Template {
    std::vector<Dispatch> disp;
    int encodeCount = 0;       // dispatches of an ingest-only frame (the encode stage)
    int sharedCount = 0;       // dispatches shared by all generated frames (incl. encode)
    int genStart[kMaxGenerated] = {};   // first dispatch of each generated frame's block
    Dispatch prior;            // after the last generated frame
};

enum class Layout : uint8_t { Compact, Reference };

struct Graph {
    int      variant = 0;      // 0 standard shaders, 1 Adreno 840 shaders
    Layout   layout = Layout::Compact;
    int      phases = kRing;   // descriptor phases in use: 3 (Reference) or 6 (Compact)
    Geometry geo;
    std::vector<ImageDesc>  images;
    std::vector<BufferDesc> buffers;
    uint16_t input[kPhases] = {};  // per phase: the image that receives the composited frame
    uint16_t output = 0;           // final frame, capture size, RGBA8
    int      maxGenerated = 3;
    Template tmpl[kMaxGenerated];      // [n-1]: evenly spaced times (n = 1: the single frame at any t)
    Template perFrame[kMaxGenerated];  // [n-1], n >= 2: any other times; each frame runs the whole flow

    // The template for n generated frames at times t[0..n-1] (strictly increasing, in (0, 1)).
    const Template& select(int n, const float* t) const;
    static bool evenlySpaced(int n, const float* t);
};

bool buildGraph(int variant, uint32_t width, uint32_t height, float scale, Graph& out,
                Layout layout = Layout::Compact, int maxGenerated = 3);

// ---- shader interface ---------------------------------------------------------
// Descriptor layout of each pipeline (as the reference declares it; a few
// layouts carry bindings the shader never reads) and the shader's file name.
struct PipeDef {
    uint8_t     pipe;
    const char* name;
    uint8_t     nBind;
    struct { uint8_t binding; BindKind kind; } b[kMaxBind];
};

const PipeDef* pipeDefs(int& count);
const PipeDef* pipeDef(int pipe);

// Pipelines a variant can use (any size, any scale, any n).
std::vector<int> pipelinesFor(int variant);

struct SamplerDef { VkFilter filter; VkSamplerAddressMode address; };
constexpr SamplerDef kSamplers[2] = {
    {VK_FILTER_LINEAR, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER},   // transparent black border
    {VK_FILTER_LINEAR, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE},
};

} // namespace gsfg
