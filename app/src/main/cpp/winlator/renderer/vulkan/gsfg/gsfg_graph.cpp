// See gsfg_graph.h.
#include "gsfg_graph.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <initializer_list>

namespace gsfg {
namespace {

constexpr VkFormat kRGBA8   = VK_FORMAT_R8G8B8A8_UNORM;
constexpr VkFormat kRGBA16F = VK_FORMAT_R16G16B16A16_SFLOAT;
constexpr VkFormat kRGBA32F = VK_FORMAT_R32G32B32A32_SFLOAT;

// Usage sets of the reference.
constexpr VkImageUsageFlags kWork   = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT;
constexpr VkImageUsageFlags kWorkTx = kWork | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
constexpr VkImageUsageFlags kInput  = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;

constexpr uint8_t kBarCompute = 1, kBarStage = 2;

// The reference rounds in float32: floor(float(x) * s + 0.5f). `volatile` keeps
// the compiler from fusing the multiply-add, which would round differently.
int flowSide(int x, float s) {
    volatile float p = (float)x * s;
    volatile float r = p + 0.5f;
    return std::max(64, (int)std::floor(r));   // each side of the flow field is at least 64
}

uint32_t cdiv(int a, int b) { return (uint32_t)((a + b - 1) / b); }
VkDeviceSize align16(int64_t x) { return (VkDeviceSize)((x + 15) & ~(int64_t)15); }

// ---- shader interface (layouts as the reference declares them) -------------
constexpr BindKind S = BindKind::Sampled, W = BindKind::Storage, B = BindKind::Buffer;
const PipeDef kPipes[] = {
    { 0, "fast_downscale",          2, {{0,S},{1,W}}},
    { 1, "fast_pyramid3",           4, {{0,S},{1,W},{2,W},{3,W}}},
    { 2, "hb_features",             2, {{0,S},{1,W}}},
    { 3, "hb_stable_pre",           4, {{0,S},{1,S},{2,S},{3,W}}},
    { 4, "hb_dwmix_stable",         2, {{0,S},{1,W}}},
    { 5, "hb_tail_stable",          6, {{0,S},{1,W},{2,B},{3,S},{4,B},{5,B}}},
    { 6, "fast_ui_mask",            2, {{0,B},{1,B}}},
    { 7, "fast_mask_pyramid",       1, {{0,B}}},
    { 8, "pool_small",              2, {{0,B},{1,B}}},
    { 9, "pool_small_256",          2, {{0,B},{1,B}}},
    {10, "fast_pack",               2, {{0,B},{1,W}}},
    {11, "hc8_update_unit",         3, {{0,S},{1,S},{2,W}}},
    {12, "hc16_update_unit",        3, {{0,S},{1,S},{2,W}}},
    {13, "hc16_update_pre_coarse",  6, {{0,S},{1,S},{2,S},{3,S},{4,W},{5,B}}},
    {14, "hc8_update_pre_primary",  6, {{0,S},{1,S},{2,S},{3,S},{4,W},{5,B}}},
    {15, "hc8_update_pre_local",    6, {{0,S},{1,S},{2,S},{3,S},{4,W},{5,B}}},
    {16, "hc8_update_unit_decoded", 3, {{0,S},{1,S},{2,W}}},
    {17, "hc16_update_pre_primary", 6, {{0,S},{1,S},{2,S},{3,S},{4,W},{5,B}}},
    {18, "hc16_update_pre_local",   6, {{0,S},{1,S},{2,S},{3,S},{4,W},{5,B}}},
    {19, "hb_dwmix_coarse",         2, {{0,S},{1,W}}},
    {20, "hb_dwmix_primary",        2, {{0,S},{1,W}}},
    {21, "hb_dwmix_local",          2, {{0,S},{1,W}}},
    {22, "hb_dwmix_blend",          2, {{0,S},{1,W}}},
    {23, "hb_tail_coarse",          6, {{0,S},{1,W},{2,W},{3,S},{4,B},{5,B}}},
    {24, "hb_tail_primary",         6, {{0,S},{1,W},{2,W},{3,S},{4,B},{5,B}}},
    {25, "hb_tail_local",           6, {{0,S},{1,W},{2,W},{3,S},{4,B},{5,B}}},
    {26, "hb_tail_blend",           6, {{0,S},{1,W},{2,W},{3,S},{4,B},{5,B}}},
    {27, "fast3_resize_flow",       2, {{0,S},{1,W}}},
    {28, "hc8_blend_unit",          5, {{0,S},{1,S},{2,S},{3,S},{4,W}}},
    {29, "hc8_blend_pre",           9, {{0,S},{1,S},{2,S},{3,S},{4,B},{5,S},{6,S},{7,S},{8,W}}},
    {30, "fast_pack3_resized",      4, {{0,S},{1,S},{2,S},{3,W}}},
    {31, "hy_synth_guide",          8, {{0,S},{1,S},{2,S},{3,S},{4,S},{5,S},{6,S},{7,W}}},
    {32, "hy_synth_evidence",      13, {{0,S},{1,S},{2,S},{3,S},{4,S},{5,S},{6,S},{7,W},{8,W},{9,W},{10,B},{11,B},{12,S}}},
    {34, "hy_synth_final_u8",      13, {{0,S},{1,S},{2,S},{3,S},{4,S},{5,S},{6,S},{7,S},{8,S},{9,W},{10,W},{11,B},{12,S}}},
    {35, "fast_prior_img",          2, {{0,S},{1,B}}},
};

// How a shader touches a storage buffer: bit0 reads, bit1 writes (from the SPIR-V).
int bufferAccess(int pipe, int binding) {
    switch (pipe) {
        case 5:  return binding == 2 ? 2 : 0;
        case 6: case 8: case 9: return binding == 0 ? 1 : binding == 1 ? 2 : 0;
        case 7:  return binding == 0 ? 3 : 0;
        case 10: return binding == 0 ? 1 : 0;
        case 13: case 14: case 15: case 17: case 18: return binding == 5 ? 2 : 0;
        case 23: case 24: case 25: return (binding == 4 || binding == 5) ? 1 : 0;
        case 29: return binding == 4 ? 1 : 0;
        case 34: return binding == 11 ? 2 : 0;
        case 35: return binding == 1 ? 2 : 0;
    }
    return 0;
}

// ---- builder ---------------------------------------------------------------
// A resource reference resolved per ring position r (= frame count % 3).
struct Ref { uint16_t r[kRing]; };
Ref fixed(int i) { return Ref{{(uint16_t)i, (uint16_t)i, (uint16_t)i}}; }

// Per-frame resources live in three slots; ring position r writes slot r.
struct Slotted {
    uint16_t s[kRing];
    Ref cur()    const { return Ref{{s[0], s[1], s[2]}}; }
    Ref prev()   const { return Ref{{s[2], s[0], s[1]}}; }   // frame N-1
    Ref oldest() const { return Ref{{s[1], s[2], s[0]}}; }   // frame N-2
};

struct FlowLevel {             // one level of a flow branch
    int      k;                // feature level
    uint16_t flow, hidden, upd, mix, unit;
    int      buf;
};

class Builder {
public:
    explicit Builder(Graph& g) : g_(g) {}

    uint16_t image(const std::string& name, VkFormat f, Extent e, uint32_t layers, VkImageUsageFlags u) {
        g_.images.push_back({f, (uint32_t)std::max(1, e.w), (uint32_t)std::max(1, e.h), layers, u, name});
        return (uint16_t)(g_.images.size() - 1);
    }
    int buffer(const std::string& name, int64_t bytes) {
        g_.buffers.push_back({align16(bytes), name});
        return (int)g_.buffers.size() - 1;
    }

    // Bindings
    static Binding smp(int b, Ref r, int sampler) { return mk(b, BindKind::Sampled, sampler, r, -1); }
    static Binding sto(int b, Ref r) { return mk(b, BindKind::Storage, -1, r, -1); }
    static Binding buf(int b, int i, int cold = -1) { return mk(b, BindKind::Buffer, -1, fixed(i), cold); }

    Dispatch& dispatch(std::vector<Dispatch>& out, int pipe, const char* stage, std::initializer_list<Binding> bind,
                       std::initializer_list<int32_t> ints, Extent groups8, uint32_t gz = 1) {
        Dispatch d{};
        d.pipe = (uint8_t)pipe; d.stage = stage; d.bind = bind;
        int i = 0;
        for (int32_t v : ints) d.ints[i++] = v;
        d.groups[0] = cdiv(groups8.w, 8); d.groups[1] = cdiv(groups8.h, 8); d.groups[2] = gz;
        out.push_back(d);
        return out.back();
    }

private:
    static Binding mk(int b, BindKind k, int s, Ref r, int cold) {
        Binding x{};
        x.binding = (uint8_t)b; x.kind = k; x.sampler = (int8_t)s; x.cold = (int16_t)cold;
        for (int i = 0; i < kRing; i++) x.res[i] = r.r[i];
        return x;
    }
    Graph& g_;
};

// Barriers follow the reference's rule: a dispatch waits (compute->compute) when
// it reads or writes anything written since the last barrier, or writes anything
// read since then. A stage boundary (a new command buffer in the reference) is a
// full barrier and starts over.
void placeBarriers(std::vector<Dispatch*> seq, const std::vector<bool>& stageStart) {
    std::vector<uint32_t> written, read;
    auto key = [](const Binding& b) { return (uint32_t)b.res[0] | (b.kind == BindKind::Buffer ? 0x10000u : 0u); };
    auto has = [](const std::vector<uint32_t>& v, uint32_t k) { return std::find(v.begin(), v.end(), k) != v.end(); };
    for (size_t i = 0; i < seq.size(); i++) {
        Dispatch& d = *seq[i];
        std::vector<uint32_t> r, w;
        for (const Binding& b : d.bind) {
            int acc = b.kind == BindKind::Sampled ? 1 : b.kind == BindKind::Storage ? 2 : bufferAccess(d.pipe, b.binding);
            if (acc & 1) r.push_back(key(b));
            if (acc & 2) w.push_back(key(b));
        }
        bool hazard = false;
        for (uint32_t k : r) hazard |= has(written, k);
        for (uint32_t k : w) hazard |= has(written, k) || has(read, k);
        if (stageStart[i]) {
            d.barrier = kBarStage;
            written.clear(); read.clear();
        } else {
            d.barrier = hazard ? kBarCompute : 0;
            if (hazard) { written.clear(); read.clear(); }
        }
        written.insert(written.end(), w.begin(), w.end());
        read.insert(read.end(), r.begin(), r.end());
    }
}

float lvlFrac(int k) { return (float)k / 6.0f; }

} // namespace

// ============================================================================
Geometry makeGeometry(uint32_t width, uint32_t height, float scale) {
    Geometry g{};
    g.input   = {(int)width, (int)height};
    g.quarter = {(int)width >> 2, (int)height >> 2};
    g.flow    = {flowSide((int)width, scale), flowSide((int)height, scale)};
    g.cls     = scale >= 1.0f ? 2 : (scale >= 0.5f ? 1 : 0);

    g.pyr[0] = g.flow;
    g.pyrLevels = 0;
    for (int j = 1; j < kLevels; j++) {
        Extent n{g.pyr[j - 1].w >> 1, g.pyr[j - 1].h >> 1};
        if (std::min(n.w, n.h) < 4) n = g.pyr[j - 1];
        g.pyr[j] = n;
        if (n.w != g.pyr[j - 1].w || n.h != g.pyr[j - 1].h) g.pyrLevels++;
    }
    g.feat[0] = {g.flow.w >> 2, g.flow.h >> 2};
    for (int j = 1; j < kLevels; j++) {
        Extent n{g.feat[j - 1].w >> 1, g.feat[j - 1].h >> 1};
        if (std::min(n.w, n.h) == 0) n = g.feat[j - 1];
        g.feat[j] = n;
    }
    g.featOffset[0] = 0;
    for (int j = 0; j < kLevels; j++) g.featOffset[j + 1] = g.featOffset[j] + g.feat[j].area();
    return g;
}

bool buildGraph(int variant, uint32_t width, uint32_t height, float scale, Graph& g) {
    if (width < 1 || height < 1) return false;
    g = Graph{};
    g.variant = variant;
    g.geo = makeGeometry(width, height, scale);
    const Geometry& G = g.geo;
    const Extent* F = G.feat;
    const bool full = G.cls == 2;
    Builder b(g);

    // Shader choice: Adreno 840 has its own hc8/hc16 update shaders; small flow
    // fields pool with the 64-wide kernel.
    const int pUnitHc8  = variant ? 16 : 11;
    const int pUpdPrim  = variant ? 17 : 14;
    const int pUpdLocal = variant ? 18 : 15;
    const int pPool     = G.cls == 0 ? 8 : 9;
    const VkFormat hc8Unit = variant ? kRGBA16F : kRGBA8;   // what the hc8 unit shader writes

    // ---- images (creation order follows the reference) -----------------------
    const uint16_t nullFlow  = b.image("null.flow",   kRGBA32F, {1, 1}, 1, kWork);
    const uint16_t nullState = b.image("null.state",  kRGBA16F, {1, 1}, 4, kWork);
    const uint16_t nullL3    = b.image("null.l3",     kRGBA16F, {1, 1}, 3, kWork);
    const uint16_t nullL2    = b.image("null.l2",     kRGBA16F, {1, 1}, 2, kWork);
    g.output                 = b.image("synth.output", kRGBA8, G.input, 1, kWorkTx);

    Slotted input{}, flowImg{}, pyr[kLevels]{}, feat[kLevels]{};
    for (int s = 0; s < kRing; s++) {
        input.s[s] = b.image("frame.input", kRGBA8, G.input, 1, kInput);
        // At full scale the flow field is the frame itself; otherwise it is downscaled first.
        flowImg.s[s] = full ? input.s[s] : b.image("frame.flow", kRGBA8, G.flow, 1, kWork);
        pyr[0].s[s] = flowImg.s[s];
        for (int j = 1; j < kLevels; j++) pyr[j].s[s] = b.image("frame.pyramid" + std::to_string(j), kRGBA16F, G.pyr[j], 1, kWork);
        for (int k = 0; k < kLevels; k++) feat[k].s[s] = b.image("frame.features" + std::to_string(k), kRGBA8, F[k], 3, kWork);
        g.input[s] = input.s[s];
    }

    const uint16_t stablePre = b.image("temporal.pre", kRGBA8, F[0], 4, kWorkTx);
    const uint16_t stableMix = b.image("temporal.mix", kRGBA8, F[0], 4, kWorkTx);
    const uint16_t stable    = b.image("temporal.out", kRGBA8, F[0], 4, kWorkTx);
    const uint16_t flowInit  = b.image("flow.init", kRGBA32F, F[6], 1, kWorkTx);

    auto levelImages = [&](int k, VkFormat unitFmt, const char* branch) {
        FlowLevel L{};
        L.k = k;
        const std::string p = std::string("flow.") + branch + std::to_string(k) + ".";
        L.flow   = b.image(p + "flow",   kRGBA32F, F[k], 1, kWorkTx);
        L.hidden = b.image(p + "hidden", kRGBA8,   F[k], 4, kWorkTx);
        L.upd    = b.image(p + "update", kRGBA8,   F[k], 4, kWorkTx);
        L.mix    = b.image(p + "mix",    kRGBA8,   F[k], 4, kWorkTx);
        L.unit   = b.image(p + "unit",   unitFmt,  F[k], 3, kWorkTx);
        return L;
    };
    FlowLevel coarse[4], primary[3], local[2];
    for (int i = 0; i < 4; i++) coarse[i]  = levelImages(6 - i, kRGBA16F, "coarse");    // levels 6,5,4,3
    for (int i = 0; i < 3; i++) primary[i] = levelImages(2 - i, hc8Unit, "primary");     // levels 2,1,0
    for (int i = 0; i < 2; i++) local[i]   = levelImages(2 - i, hc8Unit, "local");       // levels 2,1

    const uint16_t resized     = b.image("blend.resized",  kRGBA32F, F[0], 1, kWorkTx);
    const uint16_t blendPre    = b.image("blend.pre",      kRGBA8,   F[2], 3, kWorkTx);
    const uint16_t blendMix    = b.image("blend.mix",      kRGBA8,   F[2], 3, kWorkTx);
    const uint16_t blendFlow   = b.image("blend.flow",     kRGBA32F, F[2], 1, kWorkTx);
    const uint16_t blendHidden = b.image("blend.hidden",   kRGBA8,   F[2], 3, kWorkTx);
    const uint16_t blendUnit   = b.image("blend.unit",     kRGBA8,   F[2], 12, kWorkTx);
    const uint16_t evidenceA   = b.image("synth.evidenceA", kRGBA16F, G.quarter, 1, kWorkTx);
    const uint16_t evidenceB   = b.image("synth.evidenceB", kRGBA16F, G.quarter, 1, kWorkTx);
    const uint16_t guide       = b.image("synth.guide",     kRGBA16F, F[0], 1, kWorkTx);
    const uint16_t evidenceC   = b.image("synth.evidenceC", kRGBA16F, G.quarter, 2, kWorkTx);
    const uint16_t packed      = b.image("synth.packed",    kRGBA16F, F[0], 3, kWorkTx);

    // ---- buffers --------------------------------------------------------------
    const int bMask   = b.buffer("mask.pyramid", (int64_t)G.featOffset[kLevels] * 4);
    const int bPrior  = b.buffer("prior",        (int64_t)F[0].area() * 16);
    const int bZero   = b.buffer("prior.zero",   (int64_t)F[0].area() * 16);   // never written: the cold prior
    const int bDummy  = b.buffer("dummy",        16);
    const int bUi     = b.buffer("mask.ui",      (int64_t)F[0].area() * 4);
    const int bPool   = b.buffer("pool",         (int64_t)F[6].area() * 16);
    for (int i = 0; i < 4; i++) coarse[i].buf  = b.buffer("flow.coarse" + std::to_string(coarse[i].k), (int64_t)F[coarse[i].k].area() * 8);
    for (int i = 0; i < 3; i++) primary[i].buf = b.buffer("flow.primary" + std::to_string(primary[i].k), (int64_t)F[primary[i].k].area() * 8);
    for (int i = 0; i < 2; i++) local[i].buf   = b.buffer("flow.local" + std::to_string(local[i].k), (int64_t)F[local[i].k].area() * 8);

    using V = std::vector<Dispatch>;
    typedef Builder Bd;

    // ---- encode: frame N into its slot ---------------------------------------
    auto encode = [&](V& out) {
        if (!full)
            b.dispatch(out, 0, "encode.downscale", {Bd::smp(0, input.cur(), 1), Bd::sto(1, flowImg.cur())},
                       {G.flow.w, G.flow.h}, G.flow);
        const int dp[kLevels] = {0,
            G.pyr[1].w != G.pyr[0].w || G.pyr[1].h != G.pyr[0].h, G.pyr[2].w != G.pyr[1].w || G.pyr[2].h != G.pyr[1].h,
            G.pyr[3].w != G.pyr[2].w || G.pyr[3].h != G.pyr[2].h, G.pyr[4].w != G.pyr[3].w || G.pyr[4].h != G.pyr[3].h,
            G.pyr[5].w != G.pyr[4].w || G.pyr[5].h != G.pyr[4].h, G.pyr[6].w != G.pyr[5].w || G.pyr[6].h != G.pyr[5].h};
        for (int j0 : {0, 3}) {   // three pyramid levels per pass
            const Extent* P = G.pyr;
            b.dispatch(out, 1, "encode.pyramid",
                       {Bd::smp(0, pyr[j0].cur(), 1), Bd::sto(1, pyr[j0 + 1].cur()), Bd::sto(2, pyr[j0 + 2].cur()),
                        Bd::sto(3, pyr[j0 + 3].cur())},
                       {P[j0 + 1].w, P[j0 + 1].h, P[j0 + 2].w, P[j0 + 2].h, P[j0 + 3].w, P[j0 + 3].h,
                        dp[j0 + 2], dp[j0 + 2] + dp[j0 + 3]},
                       P[j0 + 1]);
        }
        for (int k = 0; k < kLevels; k++) {
            Dispatch& d = b.dispatch(out, 2, "encode.features", {Bd::smp(0, pyr[k].cur(), 1), Bd::sto(1, feat[k].cur())},
                                     {G.pyr[k].w, G.pyr[k].h, F[k].w, F[k].h}, F[k]);
            d.groups[0] = cdiv(F[k].w, 16); d.groups[1] = cdiv(F[k].h, 16);   // 16x16 workgroups
        }
    };

    // ---- temporal: stability and UI mask over frames N-2, N-1, N ------------
    auto temporal = [&](V& out) {
        b.dispatch(out, 3, "temporal.pre",
                   {Bd::smp(0, feat[0].oldest(), 0), Bd::smp(1, feat[0].prev(), 0), Bd::smp(2, feat[0].cur(), 0),
                    Bd::sto(3, fixed(stablePre))},
                   {F[0].w, F[0].h}, F[0]);
        b.dispatch(out, 4, "temporal.mix", {Bd::smp(0, fixed(stablePre), 1), Bd::sto(1, fixed(stableMix))},
                   {F[0].h, F[0].w}, F[0]);
        b.dispatch(out, 5, "temporal.tail",
                   {Bd::smp(0, fixed(stableMix), 1), Bd::sto(1, fixed(stable)), Bd::buf(2, bUi), Bd::smp(3, fixed(nullFlow), 1),
                    Bd::buf(4, bDummy), Bd::buf(5, bDummy)},
                   {F[0].h, F[0].w, F[0].h, F[0].w}, F[0]);
        b.dispatch(out, 6, "temporal.uimask", {Bd::buf(0, bUi), Bd::buf(1, bMask)}, {F[0].h, F[0].w, 2, 1}, F[0])
            .floats[0] = 0.5f;
        for (int start : {0, 3}) {   // mask pyramid, levels 1..3 then 4..6
            const Extent gx = F[start + 1];
            Dispatch& d = b.dispatch(out, 7, "temporal.maskpyramid", {Bd::buf(0, bMask)},
                {G.featOffset[0], G.featOffset[1], G.featOffset[2], G.featOffset[3], G.featOffset[4], G.featOffset[5],
                 G.featOffset[6], F[0].w, F[1].w, F[2].w, F[3].w, F[4].w, F[5].w, F[6].w,
                 F[1].h, F[2].h, F[3].h, F[4].h, F[5].h, F[6].h}, gx, 3);
            d.floats[0] = (float)start;
            for (int j = 0; j < kLevels; j++) d.floats[1 + j] = (float)std::min(G.pyrLevels, j);
        }
    };

    // ---- flow: one level of a branch -----------------------------------------
    // mode 2: seeded from the global prior, 1: refines the coarser level, 0: starts from nothing.
    auto level = [&](V& out, const FlowLevel& L, int pUnit, int pUpd, int pMix, int pTail, uint16_t flowIn,
                     uint16_t hiddenIn, Extent coarser, int mode, float t, const char* stage) {
        const Extent e = F[L.k];
        const float frac = lvlFrac(L.k);
        b.dispatch(out, pUnit, stage, {Bd::smp(0, feat[L.k].cur(), 0), Bd::smp(1, fixed(flowIn), 1), Bd::sto(2, fixed(L.unit))},
                   {e.h, e.w, coarser.h, coarser.w, mode}, e).floats[0] = t;
        Dispatch& u = b.dispatch(out, pUpd, stage,
                   {Bd::smp(0, feat[L.k].prev(), 0), Bd::smp(1, fixed(flowIn), 1), Bd::smp(2, fixed(hiddenIn), 1),
                    Bd::smp(3, fixed(L.unit), 1), Bd::sto(4, fixed(L.upd)), Bd::buf(5, L.buf)},
                   {e.h, e.w, coarser.h, coarser.w, mode}, e);
        u.floats[0] = t; u.floats[1] = frac;
        b.dispatch(out, pMix, stage, {Bd::smp(0, fixed(L.upd), 1), Bd::sto(1, fixed(L.mix))}, {e.h, e.w}, e);
        Dispatch& tl = b.dispatch(out, pTail, stage,
                   {Bd::smp(0, fixed(L.mix), 1), Bd::sto(1, fixed(L.hidden)), Bd::sto(2, fixed(L.flow)),
                    Bd::smp(3, fixed(flowIn), 1), Bd::buf(4, L.buf), Bd::buf(5, bMask)},
                   {e.h, e.w, coarser.h, coarser.w, mode, G.featOffset[L.k]}, e);
        tl.floats[0] = t; tl.floats[1] = frac;
    };

    // Shared by every generated frame: global prior, coarse levels, primary levels 2 and 1.
    // These run at the midpoint regardless of the frames' t (as the reference does).
    auto sharedFlow = [&](V& out) {
        b.dispatch(out, pPool, "flow.prior",
                   {Bd::buf(0, bPrior, bZero), Bd::buf(1, bPool)},
                   {4, F[0].h, F[0].w, F[6].h, F[6].w, 1}, {0, 0});
        Dispatch& pool = out.back();
        pool.groups[0] = (uint32_t)(F[6].h * F[6].w); pool.groups[1] = 4; pool.groups[2] = 1;
        b.dispatch(out, 10, "flow.prior", {Bd::buf(0, bPool), Bd::sto(1, fixed(flowInit))}, {4, F[6].h, F[6].w}, F[6]);

        uint16_t flowIn = flowInit, hiddenIn = nullState;
        Extent coarser = F[6];
        int mode = 2;
        for (const FlowLevel& L : coarse) {
            level(out, L, 12, 13, 19, 23, flowIn, hiddenIn, coarser, mode, 0.5f, "flow.coarse");
            flowIn = L.flow; hiddenIn = L.hidden; coarser = F[L.k]; mode = 1;
        }
        for (int i = 0; i < 2; i++) {
            level(out, primary[i], pUnitHc8, pUpdPrim, 20, 24, flowIn, hiddenIn, coarser, 1, 0.5f, "flow.primary");
            flowIn = primary[i].flow; hiddenIn = primary[i].hidden; coarser = F[primary[i].k];
        }
    };

    // One generated frame at time t.
    auto generated = [&](V& out, float t) {
        level(out, primary[2], pUnitHc8, pUpdPrim, 20, 24, primary[1].flow, primary[1].hidden, F[1], 1, t, "flow.primary");
        level(out, local[0], pUnitHc8, pUpdLocal, 21, 25, nullFlow, nullState, F[2], 0, t, "flow.local");
        level(out, local[1], pUnitHc8, pUpdLocal, 21, 25, local[0].flow, local[0].hidden, F[2], 1, t, "flow.local");

        b.dispatch(out, 27, "blend.resize", {Bd::smp(0, fixed(local[1].flow), 1), Bd::sto(1, fixed(resized))},
                   {F[1].h, F[1].w, F[0].h, F[0].w}, F[0]);
        const Ref fPrev = feat[2].prev(), fCur = feat[2].cur();
        b.dispatch(out, 28, "blend.unit",
                   {Bd::smp(0, fPrev, 0), Bd::smp(1, fCur, 0), Bd::smp(2, fixed(primary[0].flow), 1),
                    Bd::smp(3, fixed(local[0].flow), 1), Bd::sto(4, fixed(blendUnit))},
                   {F[2].h, F[2].w}, F[2]).floats[0] = t;
        b.dispatch(out, 29, "blend.pre",
                   {Bd::smp(0, fPrev, 0), Bd::smp(1, fCur, 0), Bd::smp(2, fixed(primary[0].flow), 1),
                    Bd::smp(3, fixed(local[0].flow), 1), Bd::buf(4, bMask), Bd::smp(5, fixed(primary[0].hidden), 1),
                    Bd::smp(6, fixed(nullL3), 1), Bd::smp(7, fixed(blendUnit), 1), Bd::sto(8, fixed(blendPre))},
                   {F[2].h, F[2].w, F[2].h, F[2].w, 0, G.featOffset[2]}, F[2]).floats[0] = t;
        b.dispatch(out, 22, "blend.mix", {Bd::smp(0, fixed(blendPre), 1), Bd::sto(1, fixed(blendMix))},
                   {F[2].h, F[2].w}, F[2]);
        b.dispatch(out, 26, "blend.tail",
                   {Bd::smp(0, fixed(blendMix), 1), Bd::sto(1, fixed(blendHidden)), Bd::sto(2, fixed(blendFlow)),
                    Bd::smp(3, fixed(nullFlow), 1), Bd::buf(4, bDummy), Bd::buf(5, bDummy)},
                   {F[2].h, F[2].w, F[2].h, F[2].w, 0, G.featOffset[2]}, F[2]).floats[0] = t;

        b.dispatch(out, 30, "synth.pack",
                   {Bd::smp(0, fixed(primary[2].flow), 1), Bd::smp(1, fixed(resized), 1), Bd::smp(2, fixed(blendFlow), 1),
                    Bd::sto(3, fixed(packed))},
                   {F[0].w, F[0].h, F[2].w, F[2].h}, F[0]);
        const Ref inPrev = input.prev(), inCur = input.cur();
        const float kSynth[3] = {60.0f, 0.03f, 0.0128f};
        Dispatch& gd = b.dispatch(out, 31, "synth.guide",
                   {Bd::smp(0, inPrev, 1), Bd::smp(1, inCur, 1), Bd::smp(2, fixed(packed), 1), Bd::smp(3, fixed(packed), 1),
                    Bd::smp(4, fixed(packed), 1), Bd::smp(5, fixed(nullL2), 1), Bd::smp(6, fixed(nullL2), 1),
                    Bd::sto(7, fixed(guide))},
                   {F[0].h, F[0].w, F[0].h, F[0].w, 1}, F[0]);
        gd.floats[0] = t; gd.floats[1] = kSynth[0]; gd.floats[2] = kSynth[1]; gd.floats[3] = kSynth[2];
        Dispatch& ev = b.dispatch(out, 32, "synth.evidence",
                   {Bd::smp(0, inPrev, 1), Bd::smp(1, inCur, 1), Bd::smp(2, fixed(packed), 1), Bd::smp(3, fixed(packed), 1),
                    Bd::smp(4, fixed(packed), 1), Bd::smp(5, fixed(guide), 1), Bd::smp(6, fixed(guide), 1),
                    Bd::sto(7, fixed(evidenceA)), Bd::sto(8, fixed(evidenceB)), Bd::sto(9, fixed(evidenceC)),
                    Bd::buf(10, bDummy), Bd::buf(11, bDummy), Bd::smp(12, fixed(nullL2), 1)},
                   {G.input.h, G.input.w, F[0].h, F[0].w, 2, 1}, G.quarter);
        ev.floats[0] = t; ev.floats[3] = kSynth[2];
        Dispatch& fin = b.dispatch(out, 34, "synth.final",
                   {Bd::smp(0, inPrev, 1), Bd::smp(1, inCur, 1), Bd::smp(2, fixed(packed), 1), Bd::smp(3, fixed(packed), 1),
                    Bd::smp(4, fixed(packed), 1), Bd::smp(5, fixed(guide), 1), Bd::smp(6, fixed(guide), 1),
                    Bd::smp(7, fixed(evidenceA), 1), Bd::smp(8, fixed(evidenceB), 1), Bd::sto(9, fixed(g.output)),
                    Bd::sto(10, fixed(guide)), Bd::buf(11, bDummy), Bd::smp(12, fixed(evidenceC), 1)},
                   {G.input.h, G.input.w, F[0].h, F[0].w, 3, 0, 1}, G.input);
        fin.floats[0] = t; fin.floats[1] = kSynth[0]; fin.floats[2] = kSynth[1]; fin.floats[3] = kSynth[2];
    };

    // ---- templates -----------------------------------------------------------
    for (int n = 1; n <= 3; n++) {
        Template& T = g.tmpl[n - 1];
        V& d = T.disp;
        encode(d);
        T.encodeCount = (int)d.size();
        temporal(d);
        const int flowStart = (int)d.size();
        sharedFlow(d);
        T.sharedCount = (int)d.size();
        for (int k = 0; k < n; k++) {
            T.genStart[k] = (int)d.size();
            generated(d, (float)(k + 1) / (float)(n + 1));
        }
        std::vector<Dispatch> pr;
        b.dispatch(pr, 35, "prior", {Bd::smp(0, fixed(primary[2].flow), 1), Bd::buf(1, bPrior)}, {F[0].h, F[0].w}, F[0]);
        T.prior = pr[0];

        // Stage boundaries: encode, flow, each generated frame when there are several, prior.
        std::vector<Dispatch*> seq;
        std::vector<bool> start;
        for (int i = 0; i < (int)d.size(); i++) {
            seq.push_back(&d[i]);
            bool s = i == 0 || i == flowStart;
            if (n > 1) for (int k = 0; k < n; k++) s = s || i == T.genStart[k];
            start.push_back(s);
        }
        seq.push_back(&T.prior);
        start.push_back(true);
        placeBarriers(seq, start);
    }
    return true;
}

// ============================================================================
const PipeDef* pipeDefs(int& count) {
    count = (int)(sizeof(kPipes) / sizeof(kPipes[0]));
    return kPipes;
}

const PipeDef* pipeDef(int pipe) {
    for (const PipeDef& p : kPipes) if (p.pipe == pipe) return &p;
    return nullptr;
}

std::vector<int> pipelinesFor(int variant) {
    bool used[64] = {};
    for (float s : {0.25f, 0.5f, 1.0f}) {
        Graph g;
        if (!buildGraph(variant, 1920, 1080, s, g)) continue;
        for (const Template& t : g.tmpl) {
            for (const Dispatch& d : t.disp) used[d.pipe] = true;
            used[t.prior.pipe] = true;
        }
    }
    std::vector<int> out;
    for (int i = 0; i < 64; i++) if (used[i]) out.push_back(i);
    return out;
}

} // namespace gsfg
