// See gsfg_engine.h.
#include "gsfg_engine.h"

#include "gsfg_graph.h"
#include "gsfg_vkd.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>

#ifdef __ANDROID__
#include <android/log.h>
#define GSFG_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GsfgEngine", __VA_ARGS__)
#define GSFG_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "GsfgEngine", __VA_ARGS__)
#else
#define GSFG_LOGI(...) do { fprintf(stderr, "[gsfg I] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#define GSFG_LOGW(...) do { fprintf(stderr, "[gsfg W] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

namespace gsfg {
namespace {

constexpr uint32_t kRequiredFrames   = 3;   // current + two predecessors in the ring
constexpr uint32_t kRecurrenceFrames = 2;
constexpr uint64_t kTelemetryInterval = 120;
constexpr float kFlowScaleMin   = 0.25f;
constexpr float kFlowScaleMax   = 1.0f;
constexpr float kFlowScaleSteps = 20.0f;
constexpr int   kMaxPipe = 40;
constexpr uint32_t kPushBytes = 112;        // 20 ints + 8 floats, as the reference declares

VkDescriptorType descType(BindKind k) {
    switch (k) {
        case BindKind::Sampled: return VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        case BindKind::Storage: return VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        default:                return VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
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
    Pipelines(VkDevice dev, const std::vector<uint8_t>& pack, const std::vector<int>& pipes) : dev_(dev) {
        ok_ = build(pack, pipes);
        if (!ok_) destroy();
    }
    ~Pipelines() { destroy(); }

    bool ok() const { return ok_; }

    VkPipeline            pipe[kMaxPipe]{};
    VkPipelineLayout      lay[kMaxPipe]{};
    VkDescriptorSetLayout dsl[kMaxPipe]{};
    VkSampler             samp[2]{};

private:
    bool build(const std::vector<uint8_t>& pack, const std::vector<int>& pipes) {
        if (pack.size() < 8 || memcmp(pack.data(), "GSFG", 4) != 0) return false;
        uint32_t count; memcpy(&count, pack.data() + 4, 4);
        if (pack.size() < 8 + (size_t)count * 12) return false;
        struct Ent { uint32_t id, size, off; };
        std::vector<Ent> ents(count);
        memcpy(ents.data(), pack.data() + 8, (size_t)count * 12);

        for (int i = 0; i < 2; i++) {
            VkSamplerCreateInfo si{};
            si.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
            si.magFilter = si.minFilter = kSamplers[i].filter;
            si.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
            si.addressModeU = si.addressModeV = si.addressModeW = kSamplers[i].address;
            si.borderColor = VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK;
            if (vkd.CreateSampler(dev_, &si, nullptr, &samp[i]) != VK_SUCCESS) return false;
        }

        for (int p : pipes) {
            const PipeDef* d = pipeDef(p);
            if (!d || p >= kMaxPipe) { GSFG_LOGW("no interface for pipeline %d", p); return false; }

            const Ent* ent = nullptr;
            for (const Ent& e : ents) if (e.id == (uint32_t)p) ent = &e;
            if (!ent || (size_t)ent->off + ent->size > pack.size()) {
                GSFG_LOGW("shader pack is missing pipeline %d (%s)", p, d->name);
                return false;
            }

            VkShaderModuleCreateInfo mi{};
            mi.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
            mi.codeSize = ent->size;
            mi.pCode = reinterpret_cast<const uint32_t*>(pack.data() + ent->off);   // offsets are 4-aligned
            VkShaderModule mod = VK_NULL_HANDLE;
            if (vkd.CreateShaderModule(dev_, &mi, nullptr, &mod) != VK_SUCCESS) return false;
            mods_.push_back(mod);

            VkDescriptorSetLayoutBinding bnd[kMaxBind]{};
            for (int b = 0; b < d->nBind; b++) {
                bnd[b].binding = d->b[b].binding;
                bnd[b].descriptorType = descType(d->b[b].kind);
                bnd[b].descriptorCount = 1;
                bnd[b].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
            }
            VkDescriptorSetLayoutCreateInfo di{};
            di.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
            di.bindingCount = d->nBind; di.pBindings = bnd;
            if (vkd.CreateDescriptorSetLayout(dev_, &di, nullptr, &dsl[p]) != VK_SUCCESS) return false;

            VkPushConstantRange pcr{VK_SHADER_STAGE_COMPUTE_BIT, 0, kPushBytes};
            VkPipelineLayoutCreateInfo li{};
            li.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
            li.setLayoutCount = 1; li.pSetLayouts = &dsl[p];
            li.pushConstantRangeCount = 1; li.pPushConstantRanges = &pcr;
            if (vkd.CreatePipelineLayout(dev_, &li, nullptr, &lay[p]) != VK_SUCCESS) return false;

            VkComputePipelineCreateInfo pi{};
            pi.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
            pi.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
            pi.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
            pi.stage.module = mod; pi.stage.pName = "main";
            pi.layout = lay[p];
            if (vkd.CreateComputePipelines(dev_, VK_NULL_HANDLE, 1, &pi, nullptr, &pipe[p]) != VK_SUCCESS) {
                GSFG_LOGW("pipeline %d (%s) failed to compile", p, d->name);
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
        for (int i = 0; i < 2; i++) { if (samp[i]) vkd.DestroySampler(dev_, samp[i], nullptr); samp[i] = VK_NULL_HANDLE; }
    }

    VkDevice dev_{};
    bool ok_ = false;
    std::vector<VkShaderModule> mods_;
};

// ============================================================================
// Chain: the graph (gsfg_graph) at one resolution - its images, buffers and
// pre-built descriptor sets - and the recording of its dispatch sequences.
// ============================================================================
class Chain {
public:
    Chain(VkDevice dev, VkPhysicalDevice pd, const Pipelines& pl, int variant, uint32_t W, uint32_t H, float S,
          Layout layout)
        : dev_(dev), pl_(pl) {
        if (!buildGraph(variant, W, H, S, graph_, layout)) return;
        valid_ = createResources(pd) && createDescriptors();
        if (!valid_) destroy();
    }
    ~Chain() { destroy(); }

    bool valid() const { return valid_; }
    const Graph& graph() const { return graph_; }
    const Geometry& geometry() const { return graph_.geo; }

    // Frame N in: copy it into its ring slot and encode it; when `n` frames are
    // to be generated, also run every stage they share. `cold`: no valid
    // temporal state, so the shared stages read the cold inputs.
    void ingest(VkCommandBuffer cmd, VkImage source, uint64_t count, uint32_t n, bool cold) {
        const int r = (int)(count % (uint64_t)graph_.phases);
        if (!initialised_) initResources(cmd);

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
        const Extent in = graph_.geo.input;
        VkImageBlit blit{};
        blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        blit.srcOffsets[1] = {in.w, in.h, 1};
        blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        blit.dstOffsets[1] = {in.w, in.h, 1};
        vkd.CmdBlitImage(cmd, source, VK_IMAGE_LAYOUT_GENERAL, images_[graph_.input[r]],
                         VK_IMAGE_LAYOUT_GENERAL, 1, &blit, VK_FILTER_NEAREST);
        memBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                   VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT
                       | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                       | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                   VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT,
                   VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT
                       | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT);

        const int ti = (n > 0 ? (int)n : 1) - 1;
        const Template& t = graph_.tmpl[ti];
        const int end = n > 0 ? t.sharedCount : t.encodeCount;   // encode only for an ingest-only frame
        for (int d = 0; d < end; d++)
            run(cmd, t.disp[d], (cold && n > 0) ? coldSet(ti, r, d) : sets_[ti][r][d]);
    }

    // Generated frame g of n: its own block of the graph, then - after the last
    // one - the prior pass that carries this frame's flow into the next frame.
    void generate(VkCommandBuffer cmd, uint64_t count, uint32_t n, uint32_t g, bool prior) {
        const int r = (int)(count % (uint64_t)graph_.phases);
        const int ti = (int)n - 1;
        const Template& t = graph_.tmpl[ti];
        fullBarrier(cmd);
        const int lo = t.genStart[g];
        const int hi = g + 1 < n ? t.genStart[g + 1] : (int)t.disp.size();
        for (int d = lo; d < hi; d++) run(cmd, t.disp[d], sets_[ti][r][d]);
        if (prior) run(cmd, t.prior, priorSets_[ti][r]);
        memBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                   VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
    }

    VkImage finalImage(uint32_t g, uint32_t n) const {
        if (!valid_ || n == 0 || g >= n) return VK_NULL_HANDLE;
        return images_[graph_.output];
    }

private:
    bool createResources(VkPhysicalDevice pd) {
        VkPhysicalDeviceMemoryProperties mp{};
        vkd.GetPhysicalDeviceMemoryProperties(pd, &mp);

        // ---- images: one 2D-array view over all layers each
        const int nImages = (int)graph_.images.size();
        images_.assign(nImages, VK_NULL_HANDLE);
        views_.assign(nImages, VK_NULL_HANDLE);
        std::vector<VkMemoryRequirements> req(nImages);
        uint32_t bits = 0xffffffffu; VkDeviceSize total = 0;
        std::vector<VkDeviceSize> offs(nImages);
        for (int i = 0; i < nImages; i++) {
            const ImageDesc& gi = graph_.images[i];
            VkImageCreateInfo ii{};
            ii.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
            ii.imageType = VK_IMAGE_TYPE_2D;
            ii.format = gi.format;
            ii.extent = {gi.width, gi.height, 1};
            ii.mipLevels = 1; ii.arrayLayers = gi.layers;
            ii.samples = VK_SAMPLE_COUNT_1_BIT; ii.tiling = VK_IMAGE_TILING_OPTIMAL;
            ii.usage = gi.usage; ii.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
            ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            if (vkd.CreateImage(dev_, &ii, nullptr, &images_[i]) != VK_SUCCESS) {
                GSFG_LOGW("image %d (%s %ux%u) could not be created", i, gi.name.c_str(), gi.width, gi.height);
                return false;
            }
            vkd.GetImageMemoryRequirements(dev_, images_[i], &req[i]);
            bits &= req[i].memoryTypeBits;
            total = (total + req[i].alignment - 1) / req[i].alignment * req[i].alignment;
            offs[i] = total; total += req[i].size;
        }
        if (!allocate(mp, bits, total, req.data(), offs.data(), nImages, /*image=*/true)) return false;

        for (int i = 0; i < nImages; i++) {
            const ImageDesc& gi = graph_.images[i];
            VkImageViewCreateInfo vi{};
            vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
            vi.image = images_[i]; vi.viewType = VK_IMAGE_VIEW_TYPE_2D_ARRAY; vi.format = gi.format;
            vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, gi.layers};
            if (vkd.CreateImageView(dev_, &vi, nullptr, &views_[i]) != VK_SUCCESS) return false;
        }

        // ---- buffers (device-local scratch; the graph never reads them from the host)
        const int nBuffers = (int)graph_.buffers.size();
        buffers_.assign(nBuffers, VK_NULL_HANDLE);
        std::vector<VkMemoryRequirements> breq(nBuffers);
        std::vector<VkDeviceSize> boffs(nBuffers);
        bits = 0xffffffffu; total = 0;
        for (int i = 0; i < nBuffers; i++) {
            VkBufferCreateInfo bi{};
            bi.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
            bi.size = graph_.buffers[i].size;
            bi.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
            if (vkd.CreateBuffer(dev_, &bi, nullptr, &buffers_[i]) != VK_SUCCESS) return false;
            vkd.GetBufferMemoryRequirements(dev_, buffers_[i], &breq[i]);
            bits &= breq[i].memoryTypeBits;
            total = (total + breq[i].alignment - 1) / breq[i].alignment * breq[i].alignment;
            boffs[i] = total; total += breq[i].size;
        }
        return allocate(mp, bits, total, breq.data(), boffs.data(), nBuffers, /*image=*/false);
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
            if (vkd.AllocateMemory(dev_, &ai, nullptr, &mem) == VK_SUCCESS) {
                mems_.push_back(mem);
                for (int i = 0; i < n; i++) if (!bind(i, mem, offs[i])) return false;
                return true;
            }
        }
        for (int i = 0; i < n; i++) {                    // fall back to one allocation per resource
            uint32_t t = pickMemoryType(mp, req[i].memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            if (t == UINT32_MAX) t = pickMemoryType(mp, req[i].memoryTypeBits, 0);
            if (t == UINT32_MAX) return false;
            VkMemoryAllocateInfo ai{};
            ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
            ai.allocationSize = req[i].size; ai.memoryTypeIndex = t;
            VkDeviceMemory mem = VK_NULL_HANDLE;
            if (vkd.AllocateMemory(dev_, &ai, nullptr, &mem) != VK_SUCCESS) return false;
            mems_.push_back(mem);
            if (!bind(i, mem, 0)) return false;
        }
        return true;
    }

    // One descriptor set per (generation count, ring position, dispatch), written once;
    // plus the cold variants and the prior pass.
    bool createDescriptors() {
        uint32_t nSets = 0, nImg = 0, nStore = 0, nBuf = 0;
        auto count = [&](const Dispatch& d, uint32_t copies) {
            nSets += copies;
            for (const Binding& b : d.bind)
                (b.kind == BindKind::Sampled ? nImg : b.kind == BindKind::Storage ? nStore : nBuf) += copies;
        };
        for (const Template& t : graph_.tmpl) {
            for (const Dispatch& d : t.disp) {
                bool cold = false;
                for (const Binding& b : d.bind) cold |= b.cold >= 0;
                count(d, (uint32_t)(cold ? 2 * graph_.phases : graph_.phases));
            }
            count(t.prior, (uint32_t)graph_.phases);
        }
        VkDescriptorPoolSize ps[3] = {
            {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, nImg},
            {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, nStore},
            {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, nBuf}};
        VkDescriptorPoolCreateInfo pi{};
        pi.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        pi.maxSets = nSets; pi.poolSizeCount = 3; pi.pPoolSizes = ps;
        if (vkd.CreateDescriptorPool(dev_, &pi, nullptr, &pool_) != VK_SUCCESS) return false;

        for (int ti = 0; ti < 3; ti++) {
            const Template& t = graph_.tmpl[ti];
            for (int r = 0; r < graph_.phases; r++) {
                sets_[ti][r].resize(t.disp.size());
                for (size_t d = 0; d < t.disp.size(); d++) {
                    if (!makeSet(t.disp[d], r, false, sets_[ti][r][d])) return false;
                    bool cold = false;
                    for (const Binding& b : t.disp[d].bind) cold |= b.cold >= 0;
                    if (cold) {
                        VkDescriptorSet s = VK_NULL_HANDLE;
                        if (!makeSet(t.disp[d], r, true, s)) return false;
                        coldSets_[ti][r].push_back({(int)d, s});
                    }
                }
                if (!makeSet(t.prior, r, false, priorSets_[ti][r])) return false;
            }
        }
        return true;
    }

    bool makeSet(const Dispatch& d, int r, bool cold, VkDescriptorSet& out) {
        VkDescriptorSetAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        ai.descriptorPool = pool_; ai.descriptorSetCount = 1; ai.pSetLayouts = &pl_.dsl[d.pipe];
        if (vkd.AllocateDescriptorSets(dev_, &ai, &out) != VK_SUCCESS) return false;

        const size_t n = d.bind.size();
        std::vector<VkWriteDescriptorSet> w(n);
        std::vector<VkDescriptorImageInfo> ii(n);
        std::vector<VkDescriptorBufferInfo> bi(n);
        for (size_t x = 0; x < n; x++) {
            const Binding& b = d.bind[x];
            w[x] = {};
            w[x].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            w[x].dstSet = out; w[x].dstBinding = b.binding; w[x].descriptorCount = 1;
            w[x].descriptorType = descType(b.kind);
            const int idx = (cold && b.cold >= 0) ? b.cold : b.res[r];
            if (b.kind == BindKind::Buffer) {
                bi[x] = {buffers_[idx], 0, VK_WHOLE_SIZE};
                w[x].pBufferInfo = &bi[x];
            } else {
                ii[x].imageView = views_[idx];
                ii[x].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
                ii[x].sampler = b.kind == BindKind::Sampled ? pl_.samp[b.sampler] : VK_NULL_HANDLE;
                w[x].pImageInfo = &ii[x];
            }
        }
        vkd.UpdateDescriptorSets(dev_, (uint32_t)n, w.data(), 0, nullptr);
        return true;
    }

    // UNDEFINED -> GENERAL for every image, and zeroed buffers (the cold prior must read zeros).
    void initResources(VkCommandBuffer cmd) {
        std::vector<VkImageMemoryBarrier> b(images_.size());
        for (size_t i = 0; i < images_.size(); i++) {
            b[i] = {};
            b[i].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            b[i].srcAccessMask = 0;
            b[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT
                               | VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
            b[i].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED; b[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
            b[i].srcQueueFamilyIndex = b[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            b[i].image = images_[i];
            b[i].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, graph_.images[i].layers};
        }
        vkd.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                               VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr,
                               (uint32_t)b.size(), b.data());
        for (VkBuffer buf : buffers_) vkd.CmdFillBuffer(cmd, buf, 0, VK_WHOLE_SIZE, 0);
        memBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                   VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        initialised_ = true;
    }

    VkDescriptorSet coldSet(int ti, int r, int d) const {
        for (const auto& p : coldSets_[ti][r]) if (p.first == d) return p.second;
        return sets_[ti][r][d];
    }

    void run(VkCommandBuffer cmd, const Dispatch& d, VkDescriptorSet set) {
        if (d.barrier & 2) fullBarrier(cmd);
        else if (d.barrier & 1) computeBarrier(cmd);

        uint8_t pc[kPushBytes];
        memcpy(pc, d.ints, 80);
        memcpy(pc + 80, d.floats, 32);
        vkd.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pl_.pipe[d.pipe]);
        vkd.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pl_.lay[d.pipe], 0, 1, &set, 0, nullptr);
        vkd.CmdPushConstants(cmd, pl_.lay[d.pipe], VK_SHADER_STAGE_COMPUTE_BIT, 0, kPushBytes, pc);
        vkd.CmdDispatch(cmd, d.groups[0], d.groups[1], d.groups[2]);
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
    Graph graph_;
    bool valid_ = false, initialised_ = false;

    std::vector<VkImage> images_;
    std::vector<VkImageView> views_;
    std::vector<VkBuffer> buffers_;
    std::vector<VkDeviceMemory> mems_;
    VkDescriptorPool pool_{};
    std::vector<VkDescriptorSet> sets_[3][kPhases];
    VkDescriptorSet priorSets_[3][kPhases]{};
    std::vector<std::pair<int, VkDescriptorSet>> coldSets_[3][kPhases];
};

// ============================================================================
// Engine
// ============================================================================
Engine::Engine() = default;
Engine::~Engine() { chain_.reset(); pipelines_.reset(); }

bool Engine::init(VkDevice device, VkPhysicalDevice physicalDevice, const std::string& packPath) {
    if (device == VK_NULL_HANDLE || physicalDevice == VK_NULL_HANDLE || packPath.empty()) return false;
    if (!gsfgVkdReady()) { GSFG_LOGW("dispatch not initialised; frame generation unavailable"); return false; }
    device_ = device; physical_ = physicalDevice;

    std::vector<uint8_t> pack;
    {
        std::ifstream f(packPath, std::ios::binary | std::ios::ate);
        if (!f) { GSFG_LOGW("cannot open shader pack %s", packPath.c_str()); return false; }
        pack.resize((size_t)f.tellg());
        f.seekg(0);
        f.read(reinterpret_cast<char*>(pack.data()), (std::streamsize)pack.size());
        if (!f) return false;
    }

    // The reference ships different shaders for Adreno 840.
    VkPhysicalDeviceProperties props{};
    vkd.GetPhysicalDeviceProperties(physicalDevice, &props);
    variant_ = (props.vendorID == 0x5143 && strstr(props.deviceName, "840")) ? 1 : 0;

    const std::vector<int> pipes = pipelinesFor(variant_);
    pipelines_ = std::make_unique<Pipelines>(device, pack, pipes);
    if (!pipelines_->ok()) { pipelines_.reset(); return false; }
    GSFG_LOGI("GSFG pipelines ready (variant %d, %zu pipelines)", variant_, pipes.size());
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
    chain_.reset();      // the old images may still be referenced; the caller waits for idle first
    chain_ = std::make_unique<Chain>(device_, physical_, *pipelines_, variant_, width, height, scale, layout_);
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
    frameCount_ = 0; lastCount_ = 0; lastGenerations_ = 0;
    planCalls_ = 0; warmStreak_ = 0; warm_ = false; generating_ = false;
    temporalValid_ = false; idleFrames_ = 0;
    pacer_.Reset();
    {
        const Graph& g = chain_->graph();
        const Geometry& geo = g.geo;
        uint64_t bytes = 0;
        for (const ImageDesc& im : g.images) {
            const uint32_t texel = im.format == VK_FORMAT_R32G32B32A32_SFLOAT ? 16
                                 : im.format == VK_FORMAT_R16G16B16A16_SFLOAT ? 8 : 4;
            bytes += (uint64_t)im.width * im.height * im.layers * texel;
        }
        for (const BufferDesc& b : g.buffers) bytes += b.size;
        GSFG_LOGI("graph built at %ux%u, flow %dx%d scale %.2f (preset %.2f, guest %ux%u), class %d: "
                  "%zu images, %zu buffers, ~%.1f MB; levels %dx%d .. %dx%d",
                  width, height, geo.flow.w, geo.flow.h, (double)scale, (double)flowScale_,
                  peakGuestExtent_.width, peakGuestExtent_.height, cls_, g.images.size(), g.buffers.size(),
                  (double)bytes / 1048576.0, geo.feat[0].w, geo.feat[0].h, geo.feat[kLevels - 1].w,
                  geo.feat[kLevels - 1].h);
    }
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
    // Without valid temporal state the shared stages read the cold inputs (first-frame
    // descriptor set), exactly as the reference does on its first generating frame.
    chain_->ingest(cmd, source, count, lastGenerations_, lastGenerations_ > 0 && !temporalValid_);

    // Reference bookkeeping (Interpolate 0x11a410, context +0x528 / +0x55c): a generating
    // frame makes the temporal state valid; it survives ONE ingest-only frame and is
    // dropped by the second ingest-only frame in a row. A change of the generation count
    // does not touch it.
    if (lastGenerations_ > 0) {
        temporalValid_ = true;
        idleFrames_ = 0;
    } else {
        temporalValid_ = temporalValid_ && idleFrames_ < 1;
        idleFrames_++;
    }
}

void Engine::generateInto(VkCommandBuffer cmd, uint32_t generation) {
    if (!chain_ || !chain_->valid() || generation >= lastGenerations_) return;
    // The reference records fast_prior_img once into a cached command buffer (constant
    // key 0x3000...) and resubmits it on every frame that has outputs, after the last one.
    const bool prior = generation + 1 == lastGenerations_;
    chain_->generate(cmd, lastCount_, lastGenerations_, generation, prior);
}

VkImage Engine::finalImage(uint32_t generation) const {
    return chain_ ? chain_->finalImage(generation, lastGenerations_) : VK_NULL_HANDLE;
}

void Engine::setLayout(Layout layout) {
    if (layout == layout_) return;
    layout_ = layout;
    chain_.reset();   // rebuilt by the next prepare()
}

float Engine::sourceRate() const { return pacer_.Stats().source_rate; }

void Engine::reset() {
    pacer_.Reset();
    peakGuestExtent_ = VkExtent2D{};
    frameCount_ = 0; lastCount_ = 0; lastGenerations_ = 0;
    warmStreak_ = 0; warm_ = false; generating_ = false;
    temporalValid_ = false; idleFrames_ = 0;
    plan_ = {};
}

} // namespace gsfg
