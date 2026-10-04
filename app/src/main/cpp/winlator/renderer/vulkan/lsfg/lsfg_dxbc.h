#pragma once
// ============================================================================
// lsfg_dxbc — DXBC -> SPIR-V for the Lossless Scaling shader chain.
//
// Why this exists at all: the base chain inside Lossless.dll is DXBC, and up
// to 3.2.1 it is the ONLY chain (checked: 3.2.1.0, PE 2025-07-14 - 202 RCDATA
// entries, all DXBC, no SPIR-V magic anywhere in the file). 3.2.2.0 (PE
// 2025-08-05, "Added shaders intended for use by the lsfg-vk project") adds
// precompiled SPIR-V at RCDATA base+49 (fp16) and base+98 (fp32), 25/25
// modules each; lsfg_dll prefers those and this translator is the fallback.
// (An earlier note here claimed no public build carries SPIR-V; that held for
// the build it was measured on, not for 3.2.2.0.)
//
// So a translator is still required for older DLLs. Upstream lsfg-vk reaches the same
// conclusion and links DXVK's `dxbc` in src/extract/trans.cpp; we vendor the
// same subset (zlib licence) under cpp/thirdparty/dxbc and follow its
// trans.cpp step for step.
//
// Verified on 3.2.1: encounter order and a set/binding sort (GameNative's
// variant) give identical per-variable bindings in all 25 modules, and both
// match the precompiled 3.2.2 layout (cb, Sampler..., Input..., Output...).
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
