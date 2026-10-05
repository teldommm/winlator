#pragma once
// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <cstdint>
#include <string>
#include <vector>

namespace lsfg {

enum class DllStatus {
    Ok = 0,
    NotInstalled,        // no such file / cannot open
    UnreadableFile,      // opens but cannot be mapped or is empty
    NotPortableExecutable,
    MissingShaders,      // parses, but the base chain is not all there
    TranslationFailed,   // a shader failed DXBC->SPIR-V or SPIR-V adoption
    CacheUnusable        // cache could not be written or read back
};

enum class Variant {
    None = 0,
    SpirvFp16,      // precompiled fp16 blobs at base+49
    SpirvFp32,      // precompiled fp32 blobs at base+98
    DxbcTranslated  // base chain, translated on device (the normal case)
};

// The 25 shader ids that make up the chain.
constexpr uint32_t kShaderMipmaps   = 255u;
constexpr uint32_t kShaderGenerate  = 256u;
constexpr uint32_t kShaderChainFirst= 280u;
constexpr uint32_t kShaderChainLast = 302u;
constexpr uint32_t kShaderCount     = 25u;

// Upstream spelling for the two ids the ported chain files refer to by name.
// Kept so those files stay byte-close to their upstream form; these alias the
// constants above rather than being a second source of truth.
constexpr uint32_t LSFG_SHADER_MIPMAPS  = kShaderMipmaps;
constexpr uint32_t LSFG_SHADER_GENERATE = kShaderGenerate;

struct Module {
    uint32_t              id = 0;
    std::vector<uint32_t> words;   // SPIR-V
};

struct ModuleSet {
    std::vector<Module> modules;
    Variant             variant = Variant::None;

    const std::vector<uint32_t>* find(uint32_t id) const;
    bool complete() const;
};

// The canonical shader-id list, in dispatch-independent order.
const std::vector<uint32_t>& shaderIds();

const char* statusName(DllStatus s);
const char* variantName(Variant v);

// Does this file look like a usable Lossless.dll? Checks the base chain only,
// so a DLL without the SPIR-V variants (i.e. every build on Steam today) is
// reported as valid — the translator handles it.
DllStatus validateDll(const std::string& dllPath);

// Which producer would be used for this DLL.
Variant dllVariant(const std::string& dllPath, bool preferFp16);

// Parse the DLL, produce all 25 SPIR-V modules, and write them to cachePath
// (via temp file + rename, so a failed build cannot leave a half-written
// cache behind). Records which producer ran in the cache header.
DllStatus buildCache(const std::string& dllPath, const std::string& cachePath, bool preferFp16);

// Is the cache current for this DLL? Compares source size + content hash.
DllStatus cacheMatchesSource(const std::string& cachePath, const std::string& dllPath,
                             bool& outMatches);

// Load a previously built cache.
DllStatus loadModules(const std::string& cachePath, ModuleSet& outSet);

// Which producer built an existing cache, without loading the modules.
DllStatus cacheVariant(const std::string& cachePath, Variant& outVariant);

} // namespace lsfg
