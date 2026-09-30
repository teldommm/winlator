// See gsfg_engine.h.
#include "gsfg_engine.h"

#include "gsfg_graph.h"
#include "gsfg_vkd.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>

#include <cstdlib>
#include <cctype>
#include <chrono>

#ifdef __ANDROID__
#include <android/log.h>
#include <sys/system_properties.h>
#define GSFG_TAG "Winlator_GSFG"
#define GSFG_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  GSFG_TAG, __VA_ARGS__)
#define GSFG_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  GSFG_TAG, __VA_ARGS__)
#define GSFG_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, GSFG_TAG, __VA_ARGS__)
// Debug knobs: `adb shell setprop debug.gsfg.<name> <int>` (read while running, no restart needed).
static int gsfgProp(const char* name, int def) {
    char v[PROP_VALUE_MAX] = {0};
    return __system_property_get(name, v) > 0 ? atoi(v) : def;
}
#else
#define GSFG_LOGI(...) do { fprintf(stderr, "[gsfg I] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#define GSFG_LOGW(...) do { fprintf(stderr, "[gsfg W] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#define GSFG_LOGE(...) do { fprintf(stderr, "[gsfg E] " __VA_ARGS__); fputc('\n', stderr); } while (0)
// Host tests: debug.gsfg.trace -> GSFG_TRACE.
static int gsfgProp(const char* name, int def) {
    std::string n = "GSFG_";
    const char* dot = strrchr(name, '.');
    for (const char* p = dot ? dot + 1 : name; *p; p++) n += (char)toupper((unsigned char)*p);
    const char* v = getenv(n.c_str());
    return v ? atoi(v) : def;
}
#endif

static double gsfgNowMs() {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

// Check a Vulkan call and log the failing step with its VkResult.
#define GCHECK(expr, what) do { const VkResult r_ = (expr); if (r_ != VK_SUCCESS) { \
        GSFG_LOGE("%s failed: VkResult %d", what, (int)r_); return false; } } while (0)

namespace gsfg {
namespace {

constexpr uint32_t kRequiredFrames   = 3;   // current + two predecessors in the ring
constexpr uint32_t kRecurrenceFrames = 2;
constexpr uint64_t kTelemetryInterval = 120;
constexpr float kFlowScaleMin   = 0.25f;
constexpr float kFlowScaleMax   = 1.0f;
constexpr float kFlowScaleSteps = 20.0f;
constexpr int   kMaxPipe = 40;

// ---- geometry --------------------------------------------------------------
// Names and order follow the exporter (gexport_core.quantities): eighteen sized
// entities x (w, h, w*h), then cumulative level areas, then level-change flags.
enum DimId { D_W = 0, D_W4, D_FLOW, D_P0, D_L0 = D_P0 + 7, D_ONE = D_L0 + 7, D_COUNT };

struct Geometry {
    int32_t q[kQuantities];
    int     dw[D_COUNT], dh[D_COUNT];
    int     fw, fh;
    bool    small;
    int     cls;
};

// The reference rounds in float32: floor(float(x) * s + 0.5f). `volatile` keeps
// the compiler from fusing the multiply-add, which would round differently.
int flowRound(int x, float s) {
    volatile float p = (float)x * s;
    volatile float r = p + 0.5f;
    return std::max(64, (int)std::floor(r));   // each side of the flow field is at least 64
}

void buildGeometry(int W, int H, float S, Geometry& g) {
    g.fw = flowRound(W, S);
    g.fh = flowRound(H, S);
    g.cls = S >= 1.0f ? 2 : (S >= 0.5f ? 1 : 0);
    g.small = std::min(g.fw, g.fh) < 256;

    g.dw[D_W] = W;        g.dh[D_W] = H;
    g.dw[D_W4] = W >> 2;  g.dh[D_W4] = H >> 2;
    g.dw[D_FLOW] = g.fw;  g.dh[D_FLOW] = g.fh;
    g.dw[D_P0] = g.fw;    g.dh[D_P0] = g.fh;
    for (int j = 1; j < 7; j++) {                   // pyramid: halve, keep a level if a side would drop below 4
        int nw = g.dw[D_P0 + j - 1] >> 1, nh = g.dh[D_P0 + j - 1] >> 1;
        if (std::min(nw, nh) < 4) { nw = g.dw[D_P0 + j - 1]; nh = g.dh[D_P0 + j - 1]; }
        g.dw[D_P0 + j] = nw; g.dh[D_P0 + j] = nh;
    }
    g.dw[D_L0] = g.fw >> 2; g.dh[D_L0] = g.fh >> 2;
    for (int j = 1; j < 7; j++) {                   // levels: halve, keep a level if a side would reach 0
        int nw = g.dw[D_L0 + j - 1] >> 1, nh = g.dh[D_L0 + j - 1] >> 1;
        if (std::min(nw, nh) == 0) { nw = g.dw[D_L0 + j - 1]; nh = g.dh[D_L0 + j - 1]; }
        g.dw[D_L0 + j] = nw; g.dh[D_L0 + j] = nh;
    }
    g.dw[D_ONE] = 1; g.dh[D_ONE] = 1;

    int k = 0;
    for (int i = 0; i < D_COUNT; i++) {
        g.q[k++] = g.dw[i];
        g.q[k++] = g.dh[i];
        g.q[k++] = g.dw[i] * g.dh[i];
    }
    int32_t acc = 0;
    for (int j = 0; j < 7; j++) { acc += g.dw[D_L0 + j] * g.dh[D_L0 + j]; g.q[k++] = acc; }   // SL1..SL7
    acc = 0;
    for (int j = 0; j < 7; j++) { acc += g.dw[D_P0 + j] * g.dh[D_P0 + j]; g.q[k++] = acc; }   // SP1..SP7
    int dp[7] = {}, dl[7] = {};
    for (int j = 1; j < 7; j++) {
        dp[j] = (g.dw[D_P0 + j] != g.dw[D_P0 + j - 1] || g.dh[D_P0 + j] != g.dh[D_P0 + j - 1]) ? 1 : 0;
        dl[j] = (g.dw[D_L0 + j] != g.dw[D_L0 + j - 1] || g.dh[D_L0 + j] != g.dh[D_L0 + j - 1]) ? 1 : 0;
    }
    for (int j = 1; j < 7; j++) { g.q[k++] = dp[j]; g.q[k++] = dl[j]; }                        // DP1,DL1 .. DP6,DL6
    for (int j = 1; j < 6; j++) { g.q[k++] = dp[j] + dp[j + 1]; g.q[k++] = dl[j] + dl[j + 1]; } // DP12,DL12 .. DP56,DL56
    int ndl = 0, ndp = 0;
    for (int j = 1; j < 7; j++) { ndl += dl[j]; ndp += dp[j]; }
    g.q[k++] = ndl;                                                                            // NDL
    g.q[k++] = ndp;                                                                            // NDP
}

int32_t evalInt(const GExpr& e, const Geometry& g) { return e.kind == 0 ? e.a : g.q[e.a]; }

uint32_t evalGroup(const GExpr& e, const Geometry& g, const int32_t* ints) {
    switch (e.kind) {
        case 0: return (uint32_t)e.a;
        case 2: return (uint32_t)((g.q[e.a] + e.b - 1) / e.b);
        case 3: return (uint32_t)(ints[e.a] * ints[e.b]);
    }
    return 1;
}

int64_t evalBuffer(const GExpr& e, const Geometry& g) {
    if (e.kind == 0) return e.a;
    return (((int64_t)g.q[e.a] * e.b) + 15) & ~(int64_t)15;
}

VkDescriptorType descType(int t) {
    switch (t) {
        case 1: return VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        case 3: return VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        default: return VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    }
}

uint32_t pickMemoryType(const VkPhysicalDeviceMemoryProperties& mp, uint32_t bits,
                        VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & want) == want) return i;
    return UINT32_MAX;
}

void memBarrier(VkCommandBuffer cmd, VkPipelineStageFlags srcStage, VkPipelineStageFlags dstStage,
                VkAccessFlags srcAccess, VkAccessFlags dstAccess) {
    VkMemoryBarrier mb{};
    mb.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mb.srcAccessMask = srcAccess;
    mb.dstAccessMask = dstAccess;
    vkd.CmdPipelineBarrier(cmd, srcStage, dstStage, 0, 1, &mb, 0, nullptr, 0, nullptr);
}

void fullBarrier(VkCommandBuffer cmd) {
    memBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
               VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
               VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
}

void computeBarrier(VkCommandBuffer cmd) {
    memBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
               VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
               VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
}

} // namespace

// ============================================================================
// Pipelines: shader modules, descriptor layouts, pipelines and samplers. They
// do not depend on the resolution, so they outlive chain rebuilds.
// ============================================================================
class Pipelines {
public:
    Pipelines(VkDevice dev, const std::vector<uint8_t>& pack, const GModel& samplerSource,
              const bool* needed)
        : dev_(dev) {
        ok_ = build(pack, samplerSource, needed);
        if (!ok_) destroy();
    }
    ~Pipelines() { destroy(); }

    bool ok() const { return ok_; }

    VkPipeline            pipe[kMaxPipe]{};
    VkPipelineLayout      lay[kMaxPipe]{};
    VkDescriptorSetLayout dsl[kMaxPipe]{};
    VkSampler             samp[4]{};
    int                   nSamp = 0;

private:
    bool build(const std::vector<uint8_t>& pack, const GModel& sm, const bool* needed) {
        if (pack.size() < 8 || memcmp(pack.data(), "GSFG", 4) != 0) {
            GSFG_LOGE("shader pack has a bad header (size %zu)", pack.size());
            return false;
        }
        uint32_t count; memcpy(&count, pack.data() + 4, 4);
        if (pack.size() < 8 + (size_t)count * 12) return false;
        struct Ent { uint32_t id, size, off; };
        std::vector<Ent> ents(count);
        memcpy(ents.data(), pack.data() + 8, (size_t)count * 12);

        for (int i = 0; i < sm.nSamplers && i < 4; i++) {
            VkSamplerCreateInfo si{};
            si.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
            si.magFilter = (VkFilter)sm.samplers[i].mag;
            si.minFilter = (VkFilter)sm.samplers[i].min;
            si.mipmapMode = (VkSamplerMipmapMode)sm.samplers[i].mip;
            si.addressModeU = si.addressModeV = si.addressModeW = (VkSamplerAddressMode)sm.samplers[i].addr;
            si.borderColor = VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK;
            GCHECK(vkd.CreateSampler(dev_, &si, nullptr, &samp[i]), "CreateSampler");
            nSamp = i + 1;
        }

        int npd = 0;
        const GPipeDef* defs = gsfgPipeDefs(npd);
        for (int i = 0; i < npd; i++) {
            const GPipeDef& d = defs[i];
            if (d.pipe >= kMaxPipe || (needed && !needed[d.pipe])) continue;

            const Ent* ent = nullptr;
            for (const Ent& e : ents) if (e.id == d.pipe) ent = &e;
            if (!ent || (size_t)ent->off + ent->size > pack.size()) {
                GSFG_LOGE("shader pack is missing pipeline %d", (int)d.pipe);
                return false;
            }

            GSFG_LOGI("pipeline %d (%u bytes SPIR-V): creating shader module", (int)d.pipe, ent->size);
            VkShaderModuleCreateInfo mi{};
            mi.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
            mi.codeSize = ent->size;
            mi.pCode = reinterpret_cast<const uint32_t*>(pack.data() + ent->off);   // offsets are 4-aligned
            VkShaderModule mod = VK_NULL_HANDLE;
            GCHECK(vkd.CreateShaderModule(dev_, &mi, nullptr, &mod), "CreateShaderModule");
            mods_.push_back(mod);

            VkDescriptorSetLayoutBinding bnd[13]{};
            for (int b = 0; b < d.nBind; b++) {
                bnd[b].binding = d.b[b].binding;
                bnd[b].descriptorType = descType(d.b[b].type);
                bnd[b].descriptorCount = 1;
                bnd[b].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
            }
            VkDescriptorSetLayoutCreateInfo di{};
            di.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
            di.bindingCount = d.nBind; di.pBindings = bnd;
            GCHECK(vkd.CreateDescriptorSetLayout(dev_, &di, nullptr, &dsl[d.pipe]), "CreateDescriptorSetLayout");

            VkPushConstantRange pcr{VK_SHADER_STAGE_COMPUTE_BIT, 0, 112};
            VkPipelineLayoutCreateInfo li{};
            li.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
            li.setLayoutCount = 1; li.pSetLayouts = &dsl[d.pipe];
            li.pushConstantRangeCount = 1; li.pPushConstantRanges = &pcr;
            GCHECK(vkd.CreatePipelineLayout(dev_, &li, nullptr, &lay[d.pipe]), "CreatePipelineLayout");

            VkComputePipelineCreateInfo pi{};
            pi.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
            pi.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
            pi.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
            pi.stage.module = mod; pi.stage.pName = "main";
            pi.layout = lay[d.pipe];
            GSFG_LOGI("pipeline %d: compiling", (int)d.pipe);
            const double t0 = gsfgNowMs();
            const VkResult pr = vkd.CreateComputePipelines(dev_, VK_NULL_HANDLE, 1, &pi, nullptr, &pipe[d.pipe]);
            GSFG_LOGI("pipeline %d: compiled in %.0f ms", (int)d.pipe, gsfgNowMs() - t0);
            if (pr != VK_SUCCESS) {
                GSFG_LOGE("pipeline %d failed to compile: VkResult %d", (int)d.pipe, (int)pr);
                return false;
            }
        }
        return true;
    }

    void destroy() {
        for (int i = 0; i < kMaxPipe; i++) {
            if (pipe[i]) vkd.DestroyPipeline(dev_, pipe[i], nullptr);
            if (lay[i])  vkd.DestroyPipelineLayout(dev_, lay[i], nullptr);
            if (dsl[i])  vkd.DestroyDescriptorSetLayout(dev_, dsl[i], nullptr);
            pipe[i] = VK_NULL_HANDLE; lay[i] = VK_NULL_HANDLE; dsl[i] = VK_NULL_HANDLE;
        }
        for (VkShaderModule m : mods_) vkd.DestroyShaderModule(dev_, m, nullptr);
        mods_.clear();
        for (int i = 0; i < 4; i++) { if (samp[i]) vkd.DestroySampler(dev_, samp[i], nullptr); samp[i] = VK_NULL_HANDLE; }
    }

    VkDevice dev_{};
    bool ok_ = false;
    std::vector<VkShaderModule> mods_;
};

// ============================================================================
// Chain: every image, view and buffer of the graph at one resolution, plus the
// pre-built descriptor sets, and the recording of the dispatch sequences.
// ============================================================================
class Chain {
public:
    Chain(VkDevice dev, VkPhysicalDevice pd, const Pipelines& pl, int variant, uint32_t W, uint32_t H,
          float S)
        : dev_(dev), pl_(pl), W_(W), H_(H) {
        buildGeometry((int)W, (int)H, S, geo_);
        model_ = gsfgModel(variant, geo_.cls);
        if (!model_) return;
        valid_ = createResources(pd) && createDescriptors();
        if (!valid_) { GSFG_LOGE("graph resources could not be created (see the errors above)"); destroy(); }
        else GSFG_LOGI("graph ready: %d images, %d views, %d buffers, descriptor sets created", model_->nImages,
                       model_->nViews, model_->nBuffers);
    }
    ~Chain() { destroy(); }

    bool valid() const { return valid_; }
    const Geometry& geometry() const { return geo_; }
    int  takeDispatchCount() { const int n = dispCount_; dispCount_ = 0; return n; }
    VkImage inputImage(uint64_t count, uint32_t n) const {
        return images_[model_->tmpl[(n > 0 ? n : 1) - 1].inDst[count % 3]];
    }
    int nImages() const { return model_ ? model_->nImages : 0; }
    int nBuffers() const { return model_ ? model_->nBuffers : 0; }

    // Frame N in: copy it into the ring slot, encode its features, and - when
    // `n` generated frames are planned - run every stage they share.
    void ingest(VkCommandBuffer cmd, VkImage source, uint64_t count, uint32_t n, bool first) {
        const int r = (int)(count % 3);
        if (!initialised_) initResources(cmd);

        const GTemplate& t1 = model_->tmpl[0];
        fullBarrier(cmd);
        // Blit the composited frame into the ring slot (NEAREST, same size; the
        // blit also converts BGRA<->RGBA when the swapchain is BGRA).
        memBarrier(cmd,
                   VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                       | VK_PIPELINE_STAGE_TRANSFER_BIT,
                   VK_PIPELINE_STAGE_TRANSFER_BIT,
                   VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT
                       | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT
                       | VK_ACCESS_TRANSFER_WRITE_BIT,
                   VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);
        VkImageBlit blit{};
        blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        blit.srcOffsets[1] = {(int32_t)W_, (int32_t)H_, 1};
        blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        blit.dstOffsets[1] = {(int32_t)W_, (int32_t)H_, 1};
        vkd.CmdBlitImage(cmd, source, VK_IMAGE_LAYOUT_GENERAL, images_[t1.inDst[r]],
                         VK_IMAGE_LAYOUT_GENERAL, 1, &blit, VK_FILTER_NEAREST);
        memBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                   VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT
                       | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                       | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                   VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT,
                   VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT
                       | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT);

        const GTemplate& t = model_->tmpl[(n > 0 ? n : 1) - 1];
        const int end = n > 0 ? t.genStart : t.nA;      // A only for an ingest-only frame
        const uint32_t nn = n > 0 ? n : 1;
        for (int d = 0; d < end; d++)
            run(cmd, t.disp[d], (first && n > 0) ? firstSetFor(nn, r, d) : setFor(nn, r, d));
    }

    // One generated frame: its private block of the graph, then (once, on the
    // first generating frame) the seed pass for the next frame's temporal state.
    void generate(VkCommandBuffer cmd, uint64_t count, uint32_t n, uint32_t g, bool seed) {
        const int r = (int)(count % 3);
        const GTemplate& t = model_->tmpl[n - 1];
        fullBarrier(cmd);
        const int lo = t.genStarts[g];
        for (int d = lo; d < lo + kGenBlock; d++) run(cmd, t.disp[d], setFor(n, r, d));
        if (seed) run(cmd, *t.init[r], initSet(n, r));
        memBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                   VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
    }

    VkImage finalImage(uint32_t g, uint64_t count, uint32_t n) const {
        if (!valid_ || n == 0 || g >= n) return VK_NULL_HANDLE;
        return images_[model_->tmpl[n - 1].outSrc[g * 3 + (count % 3)]];
    }

    // Test hook: the set index a dispatch would use.
    const GModel* model() const { return model_; }

private:

    bool createResources(VkPhysicalDevice pd) {
        VkPhysicalDeviceMemoryProperties mp{};
        vkd.GetPhysicalDeviceMemoryProperties(pd, &mp);

        GSFG_LOGI("graph: creating %d images", model_->nImages);
        // ---- images
        images_.assign(model_->nImages, VK_NULL_HANDLE);
        views_.assign(model_->nViews, VK_NULL_HANDLE);
        std::vector<VkMemoryRequirements> req(model_->nImages);
        uint32_t bits = 0xffffffffu; VkDeviceSize total = 0;
        std::vector<VkDeviceSize> offs(model_->nImages);
        for (int i = 0; i < model_->nImages; i++) {
            const GImage& gi = model_->images[i];
            VkImageCreateInfo ii{};
            ii.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
            ii.imageType = VK_IMAGE_TYPE_2D;
            ii.format = (VkFormat)gi.fmt;
            ii.extent = {(uint32_t)std::max(1, geo_.dw[gi.dim]), (uint32_t)std::max(1, geo_.dh[gi.dim]), 1};
            ii.mipLevels = 1; ii.arrayLayers = (uint32_t)gi.layers;
            ii.samples = VK_SAMPLE_COUNT_1_BIT; ii.tiling = VK_IMAGE_TILING_OPTIMAL;
            ii.usage = (VkImageUsageFlags)gi.usage; ii.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
            ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            {
                const VkResult r = vkd.CreateImage(dev_, &ii, nullptr, &images_[i]);
                if (r != VK_SUCCESS) {
                    GSFG_LOGE("CreateImage #%d (%ux%u fmt %d layers %d usage 0x%x) failed: VkResult %d", i,
                              ii.extent.width, ii.extent.height, (int)ii.format, (int)ii.arrayLayers,
                              (unsigned)ii.usage, (int)r);
                    return false;
                }
            }
            vkd.GetImageMemoryRequirements(dev_, images_[i], &req[i]);
            bits &= req[i].memoryTypeBits;
            total = (total + req[i].alignment - 1) / req[i].alignment * req[i].alignment;
            offs[i] = total; total += req[i].size;
        }
        if (!allocate(mp, bits, total, req.data(), offs.data(), model_->nImages, /*image=*/true)) return false;

        GSFG_LOGI("graph: creating %d views", model_->nViews);
        for (int i = 0; i < model_->nViews; i++) {
            const GView& gv = model_->views[i];
            VkImageViewCreateInfo vi{};
            vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
            vi.image = images_[gv.image]; vi.viewType = (VkImageViewType)gv.vt; vi.format = (VkFormat)gv.fmt;
            vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, (uint32_t)gv.baseLayer, (uint32_t)gv.layers};
            GCHECK(vkd.CreateImageView(dev_, &vi, nullptr, &views_[i]), "CreateImageView");
        }

        GSFG_LOGI("graph: creating %d buffers", model_->nBuffers);
        // ---- buffers (device-local scratch; the graph never reads them from the host)
        buffers_.assign(model_->nBuffers, VK_NULL_HANDLE);
        bufSize_.assign(model_->nBuffers, 0);
        std::vector<VkMemoryRequirements> breq(model_->nBuffers);
        std::vector<VkDeviceSize> boffs(model_->nBuffers);
        bits = 0xffffffffu; total = 0;
        for (int i = 0; i < model_->nBuffers; i++) {
            bufSize_[i] = evalBuffer(model_->buffers[i], geo_);
            VkBufferCreateInfo bi{};
            bi.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
            bi.size = (VkDeviceSize)bufSize_[i];
            bi.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
            {
                const VkResult r = vkd.CreateBuffer(dev_, &bi, nullptr, &buffers_[i]);
                if (r != VK_SUCCESS) {
                    GSFG_LOGE("CreateBuffer #%d (%lld bytes) failed: VkResult %d", i, (long long)bi.size, (int)r);
                    return false;
                }
            }
            vkd.GetBufferMemoryRequirements(dev_, buffers_[i], &breq[i]);
            bits &= breq[i].memoryTypeBits;
            total = (total + breq[i].alignment - 1) / breq[i].alignment * breq[i].alignment;
            boffs[i] = total; total += breq[i].size;
        }
        return allocate(mp, bits, total, breq.data(), boffs.data(), model_->nBuffers, /*image=*/false);
    }

    bool allocate(const VkPhysicalDeviceMemoryProperties& mp, uint32_t bits, VkDeviceSize total,
                  const VkMemoryRequirements* req, const VkDeviceSize* offs, int n, bool image) {
        auto bind = [&](int i, VkDeviceMemory mem, VkDeviceSize off) {
            return image ? vkd.BindImageMemory(dev_, images_[i], mem, off) == VK_SUCCESS
                         : vkd.BindBufferMemory(dev_, buffers_[i], mem, off) == VK_SUCCESS;
        };
        uint32_t type = bits ? pickMemoryType(mp, bits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) : UINT32_MAX;
        if (type == UINT32_MAX && bits) type = pickMemoryType(mp, bits, 0);
        if (type != UINT32_MAX) {                        // one allocation for the whole set
            VkMemoryAllocateInfo ai{};
            ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
            ai.allocationSize = total; ai.memoryTypeIndex = type;
            VkDeviceMemory mem = VK_NULL_HANDLE;
            const VkResult ar = vkd.AllocateMemory(dev_, &ai, nullptr, &mem);
            if (ar == VK_SUCCESS) {
                mems_.push_back(mem);
                for (int i = 0; i < n; i++)
                    if (!bind(i, mem, offs[i])) { GSFG_LOGE("bind %s #%d failed", image ? "image" : "buffer", i); return false; }
                GSFG_LOGI("allocated %.1f MB in one block for %d %s", (double)total / 1048576.0, n, image ? "images" : "buffers");
                return true;
            }
            GSFG_LOGW("single %.1f MB allocation failed (VkResult %d); falling back to one block per resource",
                      (double)total / 1048576.0, (int)ar);
        }
        for (int i = 0; i < n; i++) {                    // fall back to one allocation per resource
            uint32_t t = pickMemoryType(mp, req[i].memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            if (t == UINT32_MAX) t = pickMemoryType(mp, req[i].memoryTypeBits, 0);
            if (t == UINT32_MAX) return false;
            VkMemoryAllocateInfo ai{};
            ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
            ai.allocationSize = req[i].size; ai.memoryTypeIndex = t;
            VkDeviceMemory mem = VK_NULL_HANDLE;
            const VkResult ar = vkd.AllocateMemory(dev_, &ai, nullptr, &mem);
            if (ar != VK_SUCCESS) {
                GSFG_LOGE("AllocateMemory %s #%d (%lld bytes) failed: VkResult %d", image ? "image" : "buffer", i,
                          (long long)req[i].size, (int)ar);
                return false;
            }
            mems_.push_back(mem);
            if (!bind(i, mem, 0)) { GSFG_LOGE("bind %s #%d failed", image ? "image" : "buffer", i); return false; }
        }
        return true;
    }

    // One descriptor set per (generation count, ring slot, dispatch), written once.
    bool createDescriptors() {
        GSFG_LOGI("graph: creating descriptor pool and sets");
        uint32_t nSets = 0, nImg = 0, nStore = 0, nBuf = 0;
        auto count = [&](const GDisp& d) {
            nSets += 3;
            for (int x = 0; x < d.nDesc; x++) {
                const int ty = model_->descs[d.descOff + x].type;
                (ty == 1 ? nImg : ty == 3 ? nStore : nBuf) += 3;
            }
        };
        for (int n = 1; n <= 3; n++) {
            const GTemplate& t = model_->tmpl[n - 1];
            for (int d = 0; d < t.nd; d++) count(t.disp[d]);
            for (int r = 0; r < 3; r++) { count(*t.init[r]); nSets -= 2; }   // init is per ring already
        }
        VkDescriptorPoolSize ps[3] = {
            {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, nImg + 128},
            {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, nStore + 128},
            {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, nBuf + 128}};
        VkDescriptorPoolCreateInfo pi{};
        pi.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        pi.maxSets = nSets + 32; pi.poolSizeCount = 3; pi.pPoolSizes = ps;
        GCHECK(vkd.CreateDescriptorPool(dev_, &pi, nullptr, &pool_), "CreateDescriptorPool");

        for (int n = 1; n <= 3; n++) {
            const GTemplate& t = model_->tmpl[n - 1];
            for (int r = 0; r < 3; r++) {
                std::vector<VkDescriptorSet>& v = sets_[n - 1][r];
                v.resize(t.nd);
                for (int d = 0; d < t.nd; d++)
                    if (!makeSet(t.disp[d], r, v[d])) return false;
                if (!makeSet(*t.init[r], r, initSets_[n - 1][r])) return false;
                for (int k = 0; k < t.fix[r].n; k++) {
                    const GFix& fx = t.fix[r].f[k];
                    VkDescriptorSet s = VK_NULL_HANDLE;
                    if (!makeSet(t.disp[fx.disp], r, s, fx.slot, fx.idx)) return false;
                    firstSets_[n - 1][r].push_back({fx.disp, s});
                }
            }
        }
        return true;
    }

    bool makeSet(const GDisp& d, int r, VkDescriptorSet& out, int overSlot = -1, int overIdx = 0) {
        VkDescriptorSetAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        ai.descriptorPool = pool_; ai.descriptorSetCount = 1; ai.pSetLayouts = &pl_.dsl[d.pipe];
        GCHECK(vkd.AllocateDescriptorSets(dev_, &ai, &out), "AllocateDescriptorSets");

        VkWriteDescriptorSet w[13]{};
        VkDescriptorImageInfo  ii[13]{};
        VkDescriptorBufferInfo bi[13]{};
        for (int x = 0; x < d.nDesc; x++) {
            const GDesc& gd = model_->descs[d.descOff + x];
            w[x].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            w[x].dstSet = out; w[x].dstBinding = gd.binding; w[x].descriptorCount = 1;
            w[x].descriptorType = descType(gd.type);
            const int idx = (x == overSlot) ? overIdx : gd.idx[r];
            if (gd.type == 7) {
                bi[x] = {buffers_[idx], 0, VK_WHOLE_SIZE};
                w[x].pBufferInfo = &bi[x];
            } else {
                ii[x].imageView = views_[idx];
                ii[x].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
                ii[x].sampler = gd.sampler >= 0 ? pl_.samp[gd.sampler] : VK_NULL_HANDLE;
                w[x].pImageInfo = &ii[x];
            }
        }
        vkd.UpdateDescriptorSets(dev_, d.nDesc, w, 0, nullptr);
        return true;
    }

    // UNDEFINED -> GENERAL for every image, and a zeroed scratch buffer set.
    void initResources(VkCommandBuffer cmd) {
        std::vector<VkImageMemoryBarrier> b(model_->nImages);
        for (int i = 0; i < model_->nImages; i++) {
            b[i] = {};
            b[i].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            b[i].srcAccessMask = 0;
            b[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT
                               | VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
            b[i].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED; b[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
            b[i].srcQueueFamilyIndex = b[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            b[i].image = images_[i];
            b[i].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, (uint32_t)model_->images[i].layers};
        }
        vkd.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                               VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr,
                               (uint32_t)b.size(), b.data());
        for (VkBuffer buf : buffers_) vkd.CmdFillBuffer(cmd, buf, 0, VK_WHOLE_SIZE, 0);
        memBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                   VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        initialised_ = true;
    }

    VkDescriptorSet setFor(uint32_t n, int r, int d) const { return sets_[n - 1][r][d]; }
    VkDescriptorSet firstSetFor(uint32_t n, int r, int d) const {
        for (const auto& p : firstSets_[n - 1][r]) if (p.first == d) return p.second;
        return sets_[n - 1][r][d];
    }
    VkDescriptorSet initSet(uint32_t n, int r) const { return initSets_[n - 1][r]; }

    void run(VkCommandBuffer cmd, const GDisp& d, VkDescriptorSet set) {
        if (d.bar & 2) fullBarrier(cmd);
        else if (d.bar & 1) computeBarrier(cmd);

        int32_t ints[20];
        for (int j = 0; j < 20; j++) ints[j] = evalInt(d.ints[j], geo_);
        uint32_t pc[28];
        memcpy(pc, ints, sizeof(ints));
        memcpy(pc + 20, d.floats, 32);
        if (d.hasAlt) {   // the last two floats follow the number of distinct pyramid levels (quantity NDP)
            const int ndp = geo_.q[kQuantities - 1];
            const float f6 = (float)std::min(ndp, 5), f7 = (float)ndp;
            memcpy(&pc[26], &f6, 4);
            memcpy(&pc[27], &f7, 4);
        }

        vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pl_.pipe[d.pipe]);
        vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pl_.lay[d.pipe], 0, 1, &set, 0, nullptr);
        vkd.CmdPushConstants(cmd, pl_.lay[d.pipe], VK_SHADER_STAGE_COMPUTE_BIT, 0, 112, pc);
        dispCount_++;
        vkd.CmdDispatch(cmd, evalGroup(d.groups[0], geo_, ints), evalGroup(d.groups[1], geo_, ints),
                        evalGroup(d.groups[2], geo_, ints));
    }

    void destroy() {
        if (pool_) vkd.DestroyDescriptorPool(dev_, pool_, nullptr);
        pool_ = VK_NULL_HANDLE;
        for (VkImageView v : views_) if (v) vkd.DestroyImageView(dev_, v, nullptr);
        for (VkImage i : images_) if (i) vkd.DestroyImage(dev_, i, nullptr);
        for (VkBuffer b : buffers_) if (b) vkd.DestroyBuffer(dev_, b, nullptr);
        for (VkDeviceMemory m : mems_) vkd.FreeMemory(dev_, m, nullptr);
        views_.clear(); images_.clear(); buffers_.clear(); mems_.clear();
    }

    VkDevice dev_{};
    const Pipelines& pl_;
    uint32_t W_{}, H_{};
    Geometry geo_{};
    const GModel* model_{};
    bool valid_ = false, initialised_ = false;
    int  dispCount_ = 0;
    uint64_t memBytes_ = 0;

    std::vector<VkImage> images_;
    std::vector<VkImageView> views_;
    std::vector<VkBuffer> buffers_;
    std::vector<int64_t> bufSize_;
    std::vector<VkDeviceMemory> mems_;
    VkDescriptorPool pool_{};
    std::vector<VkDescriptorSet> sets_[3][3];
    VkDescriptorSet initSets_[3][3]{};
    std::vector<std::pair<int, VkDescriptorSet>> firstSets_[3][3];
};

// ============================================================================
// Engine
// ============================================================================
Engine::Engine() = default;

void Engine::refreshKnobs() {
    traceAll_ = gsfgProp("debug.gsfg.trace", 0) != 0;
    const int show = gsfgProp("debug.gsfg.show", 0);
    if (show != showMode_) {
        showMode_ = show;
        GSFG_LOGW("debug knob show=%d (%s)", show, show ? "generated frames show the current INPUT frame, graph output ignored"
                                                        : "normal output");
    }
}
Engine::~Engine() { chain_.reset(); pipelines_.reset(); }

bool Engine::init(VkDevice device, VkPhysicalDevice physicalDevice, const std::string& packPath) {
    if (device == VK_NULL_HANDLE || physicalDevice == VK_NULL_HANDLE || packPath.empty()) return false;
    if (!gsfgVkdReady()) { GSFG_LOGW("dispatch not initialised; frame generation unavailable"); return false; }
    device_ = device; physical_ = physicalDevice;
    GSFG_LOGI("init: begin, pack %s", packPath.c_str());

    std::vector<uint8_t> pack;
    {
        std::ifstream f(packPath, std::ios::binary | std::ios::ate);
        if (!f) { GSFG_LOGW("cannot open shader pack %s", packPath.c_str()); return false; }
        pack.resize((size_t)f.tellg());
        f.seekg(0);
        f.read(reinterpret_cast<char*>(pack.data()), (std::streamsize)pack.size());
        if (!f) return false;
    }

    GSFG_LOGI("init: pack read, %zu bytes", pack.size());
    // The reference ships different shaders for Adreno 840.
    VkPhysicalDeviceProperties props{};
    vkd.GetPhysicalDeviceProperties(physicalDevice, &props);
    variant_ = (props.vendorID == 0x5143 && strstr(props.deviceName, "840")) ? 1 : 0;

    bool needed[kMaxPipe] = {};
    for (int c = 0; c < 3; c++) {
        const GModel* m = gsfgModel(variant_, c);
        for (int n = 0; n < 3; n++) {
            const GTemplate& t = m->tmpl[n];
            for (int d = 0; d < t.nd; d++) needed[t.disp[d].pipe] = true;
            for (int r = 0; r < 3; r++) needed[t.init[r]->pipe] = true;
        }
    }
    GSFG_LOGI("init: building pipelines");
    const double tp = gsfgNowMs();
    pipelines_ = std::make_unique<Pipelines>(device, pack, *gsfgModel(variant_, 1), needed);
    GSFG_LOGI("init: pipelines %s in %.0f ms", pipelines_->ok() ? "built" : "FAILED", gsfgNowMs() - tp);
    if (!pipelines_->ok()) { pipelines_.reset(); return false; }
    GSFG_LOGI("GSFG pipelines ready: variant %d (%s), device \"%s\" vendor 0x%x api %u.%u.%u, pack %zu bytes",
              variant_, variant_ ? "Adreno 840 shaders" : "standard shaders", props.deviceName,
              (unsigned)props.vendorID, VK_VERSION_MAJOR(props.apiVersion), VK_VERSION_MINOR(props.apiVersion),
              VK_VERSION_PATCH(props.apiVersion), pack.size());
    refreshKnobs();
    return true;
}

void Engine::configure(uint32_t multiplier, uint32_t targetRate, float flowScale, float refreshRate) {
    PacerConfig config = pacer_.Config();
    config.multiplier = multiplier; config.target_rate = targetRate; config.refresh_rate = refreshRate;
    pacer_.SetConfig(config);
    flowScale_ = std::clamp(flowScale, kFlowScaleMin, kFlowScaleMax);
}

void Engine::setRefreshRate(float refreshRate) {
    PacerConfig config = pacer_.Config();
    if (config.refresh_rate == refreshRate) return;
    config.refresh_rate = refreshRate;
    pacer_.SetConfig(config);
}

void Engine::setGuestExtent(uint32_t width, uint32_t height) {
    if (width == 0 || height == 0) return;
    peakGuestExtent_.width  = std::max(peakGuestExtent_.width, width);
    peakGuestExtent_.height = std::max(peakGuestExtent_.height, height);
}

// A game running well below panel resolution does not need a panel-resolution flow field.
float Engine::effectiveFlowScale(uint32_t width) const {
    if (width == 0 || peakGuestExtent_.width == 0) return flowScale_;
    const float ratio = (float)peakGuestExtent_.width / (float)width;
    const float stepped = std::ceil(ratio * kFlowScaleSteps) / kFlowScaleSteps;
    return std::clamp(std::min(stepped, flowScale_), kFlowScaleMin, kFlowScaleMax);
}

bool Engine::needsRebuild(uint32_t width, uint32_t height, VkFormat format) const {
    if (unavailable_) return false;
    return !chain_ || builtExtent_.width != width || builtExtent_.height != height
        || builtFormat_ != format || builtFlowScale_ != effectiveFlowScale(width);
}

bool Engine::prepare(uint32_t width, uint32_t height, VkFormat format) {
    if (unavailable_ || !pipelines_) return false;
    if (width == 0 || height == 0) return false;
    if (format != VK_FORMAT_R8G8B8A8_UNORM && format != VK_FORMAT_B8G8R8A8_UNORM) {
        GSFG_LOGW("swapchain format %d is not 8-bit UNORM; frame generation unavailable", (int)format);
        unavailable_ = true;
        return false;
    }
    if (!needsRebuild(width, height, format)) return chain_ && chain_->valid();

    const float scale = effectiveFlowScale(width);
    GSFG_LOGI("prepare: building graph for %ux%u fmt %d scale %.2f", width, height, (int)format, (double)scale);
    chain_.reset();      // the old images may still be referenced; the caller waits for idle first
    chain_ = std::make_unique<Chain>(device_, physical_, *pipelines_, variant_, width, height, scale);
    if (!chain_->valid()) {
        GSFG_LOGW("graph build failed at %ux%u; frame generation unavailable", width, height);
        chain_.reset();
        unavailable_ = true;
        return false;
    }
    cls_ = chain_->geometry().cls;
    builtExtent_ = VkExtent2D{width, height};
    builtFormat_ = format;
    builtFlowScale_ = scale;
    traceLeft_ = 90; lastPlanGens_ = ~0u; lastWarm_ = false; lastGenerating_ = false; lastSource_ = VK_NULL_HANDLE;
    frameCount_ = 0; lastCount_ = 0; lastGenerations_ = 0;
    planCalls_ = 0; warmStreak_ = 0; warm_ = false; generating_ = false; needSeed_ = true;
    pacer_.Reset();
    GSFG_LOGI("graph built at %ux%u, flow %dx%d scale %.2f (preset %.2f, guest %ux%u), class %d%s",
              width, height, chain_->geometry().fw, chain_->geometry().fh, (double)scale,
              (double)flowScale_, peakGuestExtent_.width, peakGuestExtent_.height, cls_,
              chain_->geometry().small ? " (small flow)" : "");
    return true;
}

uint32_t Engine::plan(uint32_t capacity, uint64_t sourceFrames) {
    if (unavailable_ || !chain_) return 0;

    plan_ = pacer_.Plan(std::min<size_t>(capacity, kMaxGenerations), sourceFrames);

    // Three real frames must be in the ring (this frame included) before anything
    // interpolated from it means something.
    warm_ = plan_.warm && frameCount_ + 1 >= kRequiredFrames;
    warmStreak_ = warm_ ? warmStreak_ + 1 : 0;
    generating_ = warm_ && warmStreak_ >= kRecurrenceFrames && plan_.generations > 0;

    if ((planCalls_ % 60) == 0) refreshKnobs();
    if (tracing() || generating_ != lastGenerating_ || warm_ != lastWarm_ || (uint32_t)plan_.generations != lastPlanGens_) {
        GSFG_LOGI("plan: frame %llu capacity %u -> pacer gens %zu (warm=%d pacerWarm=%d streak=%u) => generating=%d",
                  (unsigned long long)frameCount_, capacity, plan_.generations, (int)warm_, (int)plan_.warm,
                  warmStreak_, (int)generating_);
        lastGenerating_ = generating_; lastWarm_ = warm_; lastPlanGens_ = (uint32_t)plan_.generations;
    }

    if ((planCalls_++ % kTelemetryInterval) == 0) {
        const PacerStats stats = pacer_.Stats();
        const float wanted = stats.source_rate * (float)(plan_.generations + 1);
        GSFG_LOGI("presented=%.1f fps (measured at the swapchain)", (double)presentedRate_);
        GSFG_LOGI("pace gen=%zu max=%zu cap=%u guest=%.1f loop=%.1f refresh=%.1f target=%.0f "
                  "slots=%.2f drawn=%llu needs=%.1fHz%s%s",
                  plan_.generations, pacer_.MaxGenerations(), capacity, (double)stats.source_rate,
                  (double)stats.loop_rate, (double)stats.refresh_rate, (double)stats.target_rate,
                  (double)stats.slots, (unsigned long long)stats.last_drawn, (double)wanted,
                  (stats.refresh_rate > 0.0f && wanted > stats.refresh_rate + 1.0f) ? " PANEL-BOUND" : "",
                  stats.rates_settled ? (warm_ ? "" : " cold") : " sampling");
    }
    return generating_ ? (uint32_t)plan_.generations : 0;
}

void Engine::process(VkCommandBuffer cmd, VkImage source, uint32_t, uint32_t, uint32_t generations) {
    if (!chain_ || !chain_->valid()) return;
    const uint64_t count = frameCount_++;
    lastCount_ = count;
    lastGenerations_ = std::min<uint32_t>(generations, kMaxGenerations);
    lastSource_ = source;
    const bool firstGen = needSeed_ && lastGenerations_ > 0;
    chain_->ingest(cmd, source, count, lastGenerations_, firstGen);
    if (tracing()) {
        GSFG_LOGI("process: frame %llu ring %d gens %u firstGen=%d -> %d dispatches (source %p)",
                  (unsigned long long)count, (int)(count % 3), lastGenerations_, (int)firstGen,
                  chain_->takeDispatchCount(), (void*)source);
        if (traceLeft_ > 0) traceLeft_--;
    } else {
        chain_->takeDispatchCount();
    }
    // A frame that only feeds the ring leaves the temporal state stale: the next generating
    // frame is treated like the first one (first-frame descriptors plus the seed pass).
    if (lastGenerations_ == 0) needSeed_ = true;
}

void Engine::generateInto(VkCommandBuffer cmd, uint32_t generation) {
    if (!chain_ || !chain_->valid() || generation >= lastGenerations_) return;
    const bool seed = needSeed_ && generation + 1 == lastGenerations_;
    chain_->generate(cmd, lastCount_, lastGenerations_, generation, seed);
    if (tracing()) {
        GSFG_LOGI("generate: frame %llu gen %u/%u seed=%d -> %d dispatches, output image %p",
                  (unsigned long long)lastCount_, generation + 1, lastGenerations_, (int)seed,
                  chain_->takeDispatchCount(), (void*)finalImage(generation));
    } else {
        chain_->takeDispatchCount();
    }
    if (seed) needSeed_ = false;
}

VkImage Engine::finalImage(uint32_t generation) const {
    if (showMode_ == 1 && lastSource_ != VK_NULL_HANDLE) return lastSource_;
    return chain_ ? chain_->finalImage(generation, lastCount_, lastGenerations_) : VK_NULL_HANDLE;
}

float Engine::sourceRate() const { return pacer_.Stats().source_rate; }

void Engine::reset() {
    pacer_.Reset();
    peakGuestExtent_ = VkExtent2D{};
    frameCount_ = 0; lastCount_ = 0; lastGenerations_ = 0;
    warmStreak_ = 0; warm_ = false; generating_ = false; needSeed_ = true;
    plan_ = {};
}

} // namespace gsfg
