#pragma once
// Static description of the GSFG compute graph, generated from traces of the
// reference implementation (see tools/gsfg_export). Sizes, push constants and
// dispatch counts are expressions over a handful of "quantities" derived from
// the capture size and flow scale, so one table serves every resolution.
#include <cstdint>

namespace gsfg {

constexpr int kQuantities = 92;   // see quantities() in gsfg_engine.cpp

struct GExpr {           // ints: kind 0 = const a | 1 = q[a]
    uint8_t kind;        // groups: 0 const a | 2 ceil(q[a]/b) | 3 ints[a]*ints[b]
    int32_t a;           // buffers: 0 const a | 1 align16(q[a]*b)
    int32_t b;
};

struct GDesc { uint8_t binding; uint8_t type; int8_t sampler; uint16_t idx[3]; };

// bar: bit0 = compute->compute barrier before the dispatch,
//      bit1 = command-buffer boundary in the reference (full barrier).
struct GDisp {
    uint8_t  pipe, bar, nDesc, hasAlt;
    uint16_t descOff;
    GExpr    ints[20];
    GExpr    groups[3];
    uint32_t floats[8];
    uint32_t falt[8];    // used instead of `floats` when the flow field is small
};

struct GImage   { int fmt, layers, usage, dim; };
struct GView    { int image, vt, fmt, baseLayer, layers; };
struct GSampler { int mag, min, mip, addr; };

// First generating frame after ingest-only frames: a few descriptors differ
// from steady state (dispatch `disp`, descriptor `slot` -> buffer/view `idx`).
struct GFix { uint16_t disp; uint8_t slot; uint16_t idx; };
struct GFixList { const GFix* f; int n; };

struct GTemplate {
    int nd, nA, genStart;
    const GDisp*    disp;
    uint16_t        inDst[3];
    const uint16_t* outSrc;      // [generation*3 + ring] -> image index
    int             genStarts[3];
    const GDisp*    init[3];     // one-shot seed pass, per ring slot
    GFixList        fix[3];      // first-generating-frame descriptor fixes, per ring slot
};

struct GModel {
    int nImages, nViews, nBuffers, nSamplers;
    const GImage*    images;
    const GView*     views;
    const GExpr*     buffers;
    const GSampler*  samplers;
    int nDescs;
    const GDesc*     descs;
    const GTemplate* tmpl;       // [n-1], n = generations 1..3
};

struct GPipeDef {
    uint8_t pipe, nBind;
    struct { uint8_t binding, type; } b[13];
};

constexpr int kGenBlock = 21;    // dispatches per generated frame

const GModel*    gsfgModel(int variant, int cls);   // variant 0 std, 1 Adreno 840; cls 0 low, 1 mid, 2 full
const GPipeDef*  gsfgPipeDefs(int& count);

} // namespace gsfg
