#pragma once
// ============================================================================
// lsfg_dxbc — DXBC -> SPIR-V for the Lossless Scaling shader chain.
//
// Why this exists at all: the base chain inside Lossless.dll is DXBC. Some
// Lossless.dll builds carry ONLY that chain (all RCDATA entries DXBC, no
// SPIR-V anywhere in the file); others additionally carry precompiled SPIR-V
// at RCDATA base+49 (fp16) and base+98 (fp32), 25/25 modules each. lsfg_dll
// prefers the precompiled sets when present; this translator is the fallback.
//
// So a translator is still required for DXBC-only DLLs. Upstream lsfg-vk reaches the same
// conclusion and links DXVK's `dxbc` in src/extract/trans.cpp; we vendor the
// same subset (zlib licence) under cpp/thirdparty/dxbc and follow its
// trans.cpp step for step.
//
// Verified on a DXBC-only DLL: encounter order and a set/binding sort
// (GameNative's variant) give identical per-variable bindings in all 25
// modules, and both match the precompiled-SPIR-V layout
// (cb, Sampler..., Input..., Output...).
//
// The binding renumber here is ENCOUNTER ORDER — the order in which Binding
// decorations appear in DXVK's output — which is what DXVK's own layout pairs
// with. The precompiled-SPIR-V path in lsfg_dll.cpp uses a set/binding sort
// instead, because those blobs were built with that convention. The two must
// not be merged.
// ============================================================================

#include <cstdint>
#include <vector>

namespace lsfg {

// Translate one DXBC compute shader to SPIR-V, renumbering descriptor bindings
// into a dense 0..n range in encounter order. Returns false (leaving outWords
// empty) on any malformed input — DXVK's compiler throws, and everything is
// caught here.
bool translateDxbc(const uint8_t* bytecode, uint32_t size, std::vector<uint32_t>& outWords);

} // namespace lsfg
