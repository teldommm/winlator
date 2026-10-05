#pragma once
// lsfg_dxbc — DXBC -> SPIR-V for the Lossless Scaling shader chain.


#include <cstdint>
#include <vector>

namespace lsfg {

// Translate one DXBC compute shader to SPIR-V, renumbering descriptor bindings
// into a dense 0..n range in encounter order. Returns false (leaving outWords
// empty) on any malformed input — DXVK's compiler throws, and everything is
// caught here.
bool translateDxbc(const uint8_t* bytecode, uint32_t size, std::vector<uint32_t>& outWords);

} // namespace lsfg
