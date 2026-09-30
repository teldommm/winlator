// Native GSFG compositor integration. Frames are composited into a ring of targets, handed to
// the GSFG graph (gsfg/), and generated frames are blitted from the graph's output image into
// the swapchain. Adapted from the former LSFG integration (Bannerlator 3.0.9, GPL-3.0-or-later;
// credits: Bannerlator/The412Banner, WinNative, Camille LaVey, the Eden Emulator Project and
// PancakeTAS/lsfg-vk), whose composite ring, per-present cursor overlay and pacing were kept.
#include "VulkanRendererContext.h"
#include "gsfg/gsfg_engine.h"
#include "gsfg/gsfg_vkd.h"

VkRenderPass VulkanRendererContext::createCompatibleRenderPass(
    VkAttachmentLoadOp loadOp, VkImageLayout initialLayout, VkImageLayout finalLayout) {

    // All three passes share attachment format, subpass and dependencies so
    // existing pipelines/framebuffers remain compatible. Only ops/layouts vary.
    VkAttachmentDescription att{}; att.format=swapchainFmt; att.samples=VK_SAMPLE_COUNT_1_BIT;
    att.loadOp=loadOp; att.storeOp=VK_ATTACHMENT_STORE_OP_STORE;
    att.stencilLoadOp=VK_ATTACHMENT_LOAD_OP_DONT_CARE; att.stencilStoreOp=VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att.initialLayout=initialLayout;
    att.finalLayout=finalLayout;

    VkAttachmentReference ref{0,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub{}; sub.pipelineBindPoint=VK_PIPELINE_BIND_POINT_GRAPHICS;
    sub.colorAttachmentCount=1; sub.pColorAttachments=&ref;

    VkSubpassDependency deps[2]{};
    // A previous frame may still be sampling or computing on this target.
    deps[0].srcSubpass=VK_SUBPASS_EXTERNAL; deps[0].dstSubpass=0;
    deps[0].srcStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT|VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT|VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT;
    deps[0].dstStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    deps[0].srcAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    deps[0].dstAccessMask=VK_ACCESS_COLOR_ATTACHMENT_READ_BIT|VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    // Make this pass's writes visible to the blit into the graph and to the swapchain copy.
    deps[1].srcSubpass=0; deps[1].dstSubpass=VK_SUBPASS_EXTERNAL;
    deps[1].srcStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    deps[1].dstStageMask=VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    deps[1].srcAccessMask=VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    deps[1].dstAccessMask=VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_SHADER_READ_BIT;

    VkRenderPassCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    ci.attachmentCount=1; ci.pAttachments=&att; ci.subpassCount=1; ci.pSubpasses=&sub;
    ci.dependencyCount=2; ci.pDependencies=deps;
    VkRenderPass result = VK_NULL_HANDLE;
    if (vk_.CreateRenderPass(device,&ci,nullptr,&result)!=VK_SUCCESS)
        RLOG_E("createCompatibleRenderPass failed");
    return result;
}

bool VulkanRendererContext::createCompositeRenderPass() {
    if (!compositeRenderPass)
        compositeRenderPass = createCompatibleRenderPass(VK_ATTACHMENT_LOAD_OP_CLEAR,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
    return compositeRenderPass != VK_NULL_HANDLE;
}

bool VulkanRendererContext::ensureCompositeTargets(uint32_t w, uint32_t h, uint32_t count) {
    if (w == 0 || h == 0 || count == 0) return false;
    if (count > kMaxCompositeTargets) count = kMaxCompositeTargets;

    if (compositeW == w && compositeH == h && compositeTargets.size() == count) return true;
    if (!createCompositeRenderPass()) return false;

    // Changing the multiplier resizes the ring, and the images being replaced
    // may still be referenced by a submitted command buffer. Destroying them
    // under the GPU is a use-after-free; wait first.
    if (!compositeTargets.empty()) vk_.DeviceWaitIdle(device);
    if (gsfgEngine_) gsfgEngine_->forgetTargets();
    destroyCompositeTargets();

    compositeTargets.resize(count);
    for (uint32_t i = 0; i < count; i++) {
        CompositeTarget& t = compositeTargets[i];

        VkImageCreateInfo ii{}; ii.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        ii.imageType=VK_IMAGE_TYPE_2D; ii.extent={w,h,1};
        ii.mipLevels=1; ii.arrayLayers=1; ii.format=swapchainFmt;
        ii.tiling=VK_IMAGE_TILING_OPTIMAL; ii.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;
        // What the frame-gen path needs: drawn into by the effect chain, blitted into the
        // graph's input ring (transfer source), and copied into the swapchain image. It is
        // not a storage image: generated frames come from the graph's own output image.
        ii.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                 | VK_IMAGE_USAGE_SAMPLED_BIT
                 | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                 | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        ii.samples=VK_SAMPLE_COUNT_1_BIT; ii.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
        if (vk_.CreateImage(device,&ii,nullptr,&t.img)!=VK_SUCCESS) { destroyCompositeTargets(); return false; }

        VkMemoryRequirements req; vk_.GetImageMemoryRequirements(device,t.img,&req);
        VkMemoryAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        ai.allocationSize=req.size;
        ai.memoryTypeIndex=findMemType(req.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (vk_.AllocateMemory(device,&ai,nullptr,&t.mem)!=VK_SUCCESS) { destroyCompositeTargets(); return false; }
        if (vk_.BindImageMemory(device,t.img,t.mem,0)!=VK_SUCCESS) { destroyCompositeTargets(); return false; }

        VkImageViewCreateInfo vci{}; vci.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        vci.image=t.img; vci.viewType=VK_IMAGE_VIEW_TYPE_2D; vci.format=swapchainFmt;
        vci.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        vci.components={VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY,
                        VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY};
        if (vk_.CreateImageView(device,&vci,nullptr,&t.view)!=VK_SUCCESS) { destroyCompositeTargets(); return false; }

        VkImageView att[]={t.view};
        VkFramebufferCreateInfo fi{}; fi.sType=VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        fi.renderPass=compositeRenderPass; fi.attachmentCount=1; fi.pAttachments=att;
        fi.width=w; fi.height=h; fi.layers=1;
        if (vk_.CreateFramebuffer(device,&fi,nullptr,&t.fb)!=VK_SUCCESS) { destroyCompositeTargets(); return false; }

        VkDescriptorSetAllocateInfo dsai{}; dsai.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        dsai.descriptorPool=winTexPool; dsai.descriptorSetCount=1; dsai.pSetLayouts=&dsLayout;
        if (vk_.AllocateDescriptorSets(device,&dsai,&t.ds)!=VK_SUCCESS) { t.ds=VK_NULL_HANDLE; destroyCompositeTargets(); return false; }
        VkDescriptorImageInfo dii{}; dii.imageLayout=VK_IMAGE_LAYOUT_GENERAL;
        dii.imageView=t.view; dii.sampler=sampler;
        VkWriteDescriptorSet wr{}; wr.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        wr.dstSet=t.ds; wr.dstBinding=0;
        wr.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; wr.descriptorCount=1;
        wr.pImageInfo=&dii;
        vk_.UpdateDescriptorSets(device,1,&wr,0,nullptr);
    }

    compositeW = w; compositeH = h; compositeIndex = 0;
    RLOG("composite ring: %ux%u x%u targets", w, h, count);
    return true;
}

void VulkanRendererContext::destroyCompositeTargets() {
    for (CompositeTarget& t : compositeTargets) {
        if (t.ds          !=VK_NULL_HANDLE){ vk_.FreeDescriptorSets(device,winTexPool,1,&t.ds); t.ds=VK_NULL_HANDLE; }
        if (t.fb          !=VK_NULL_HANDLE){ vk_.DestroyFramebuffer(device,t.fb,nullptr); t.fb=VK_NULL_HANDLE; }
        if (t.view        !=VK_NULL_HANDLE){ vk_.DestroyImageView(device,t.view,nullptr); t.view=VK_NULL_HANDLE; }
        if (t.img         !=VK_NULL_HANDLE){ vk_.DestroyImage(device,t.img,nullptr); t.img=VK_NULL_HANDLE; }
        if (t.mem         !=VK_NULL_HANDLE){ vk_.FreeMemory(device,t.mem,nullptr); t.mem=VK_NULL_HANDLE; }
    }
    compositeTargets.clear();
    compositeW = compositeH = 0;
    compositeIndex = 0;
}

// ===================== Native GSFG: generation passes ========================

bool VulkanRendererContext::ensureGsfgEngine() {
    if (gsfgEngine_) return gsfgEngine_->valid();
    if (gsfgEngineTried_) return false;      // failed once; don't retry every frame
    gsfgEngineTried_ = true;

    if (gsfgPackPath_.empty()) {
        RLOG_E("gsfg-native: no shader pack path set");
        return false;
    }
    if (!gsfgVkdInit(vk_)) {
        RLOG_E("gsfg-native: dispatch incomplete; frame generation unavailable");
        return false;
    }

    auto engine = std::make_unique<gsfg::Engine>();
    if (!engine->init(device, physicalDevice, gsfgPackPath_)) {
        RLOG_E("gsfg-native: engine init failed (pack %s)", gsfgPackPath_.c_str());
        return false;
    }
    gsfgEngine_ = std::move(engine);
    fgConfigDirty_.store(true, std::memory_order_relaxed);
    RLOG("gsfg-native: engine ready");
    return true;
}

bool VulkanRendererContext::fgCapsOk() const {
    return gsfgCaps_.supported();
}

void VulkanRendererContext::ensureFgQueryPool() {
    if (fgQueryPool_ != VK_NULL_HANDLE || !fgTimestampsOk_ || !vk_.CreateQueryPool) return;
    VkQueryPoolCreateInfo qi{}; qi.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
    qi.queryType = VK_QUERY_TYPE_TIMESTAMP; qi.queryCount = MAX_FRAMES_IN_FLIGHT * 2;
    if (vk_.CreateQueryPool(device, &qi, nullptr, &fgQueryPool_) != VK_SUCCESS) {
        fgQueryPool_ = VK_NULL_HANDLE;
        fgTimestampsOk_ = false;
        RLOG_E("gsfg-native: timestamp query pool unavailable; chain cost will not be reported");
    }
}

void VulkanRendererContext::destroyFgQueryPool() {
    if (fgQueryPool_ != VK_NULL_HANDLE && vk_.DestroyQueryPool)
        vk_.DestroyQueryPool(device, fgQueryPool_, nullptr);
    fgQueryPool_ = VK_NULL_HANDLE;
    for (auto& p : fgQueryPending_) p = false;
}

void VulkanRendererContext::readFgQueryResult() {
    if (fgQueryPool_ == VK_NULL_HANDLE || !fgQueryPending_[currentFrame]) return;
    fgQueryPending_[currentFrame] = false;      // one attempt per sample; a miss is dropped
    uint64_t ts[2] = {0, 0};
    const VkResult r = vk_.GetQueryPoolResults(device, fgQueryPool_, currentFrame * 2, 2,
                                               sizeof(ts), ts, sizeof(uint64_t),
                                               VK_QUERY_RESULT_64_BIT);
    if (r != VK_SUCCESS || ts[1] <= ts[0]) return;
    const double ms = (double)(ts[1] - ts[0]) * (double)fgTimestampPeriodNs_ / 1.0e6;
    const float perGen = (float)(ms / (double)std::max(1u, fgQueryGens_[currentFrame]));
    fgChainMsPerGen_ = fgChainMsPerGen_ < 0.0f
        ? perGen
        : fgChainMsPerGen_ + (perGen - fgChainMsPerGen_) * 0.1f;
    // Same cadence as the engine's own telemetry, so a log carries the cost
    // next to the rates it explains.
    if ((fgChainLogCount_++ % 120u) == 0u)
        RLOG("gsfg-native: chain %.2f ms per generated frame (%.2f ms for %u, smoothed %.2f)",
             perGen, (float)ms, fgQueryGens_[currentFrame], fgChainMsPerGen_);
}

void VulkanRendererContext::recordFrameGenProcess(VkCommandBuffer cb) {
    if (!compositeActive() || compositeTargets.empty()) return;
    if (!gsfgEngine_) return;
    const CompositeTarget& src = compositeTargets[compositeIndex];
    ensureFgQueryPool();
    if (fgQueryPool_ != VK_NULL_HANDLE && fgPlan_.generations > 0) {
        vk_.CmdResetQueryPool(cb, fgQueryPool_, currentFrame * 2, 2);
        vk_.CmdWriteTimestamp(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, fgQueryPool_, currentFrame * 2);
    }
    // Take frame N as the graph's newest input (blit + feature encode) and, when generating,
    // run every stage shared by the generated frames.
    static int sProcessLog = 0;
    if (sProcessLog < 90) {
        sProcessLog++;
        RLOG("gsfg-native: process: composite target %u/%zu image %p %ux%u, planned generations=%u, cmdSlot frame=%u",
             compositeIndex, compositeTargets.size(), (void*)src.img, compositeW, compositeH,
             fgPlan_.generations, (unsigned)currentFrame);
    }
    gsfgEngine_->process(cb, src.img, compositeW, compositeH, fgPlan_.generations);
}

void VulkanRendererContext::recordFrameGenGeneration(VkCommandBuffer cb, uint32_t g) {
    if (!compositeActive() || compositeTargets.empty()) return;
    if (!gsfgEngine_) return;
    if (g >= fgPlan_.generations) return;

    gsfgEngine_->generateInto(cb, g);
    const VkImage generated = gsfgEngine_->finalImage(g);
    if (generated == VK_NULL_HANDLE) {
        // The swapchain image for this present was already acquired; leaving it untouched would present garbage.
        RLOG_E("gsfg-native: generation %u produced no output image (engine state inconsistent)", g);
        return;
    }
    static int sGenLog = 0;
    if (sGenLog < 90) {
        sGenLog++;
        RLOG("gsfg-native: generation %u -> swapchain image %u (engine image %p) cursorOverlay=%d", g,
             fgPlan_.imgIdx[g], (void*)generated, (int)(cursorDrawnPerPresent() && cursorOverlay_.draw));
    }

    if (fgQueryPool_ != VK_NULL_HANDLE && g + 1 == fgPlan_.generations) {
        vk_.CmdWriteTimestamp(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, fgQueryPool_, currentFrame * 2 + 1);
        fgQueryPending_[currentFrame] = true;
        fgQueryGens_[currentFrame]    = fgPlan_.generations;
    }

    // The graph leaves its output image in GENERAL with the compute writes already made visible
    // to transfer reads. Move the target swapchain image into TRANSFER_DST, blit (the blit
    // converts RGBA to the swapchain's channel order), then hand the image on.
    const uint32_t imgIdx = fgPlan_.imgIdx[g];
    VkImageMemoryBarrier pre{};
    pre.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    pre.oldLayout=VK_IMAGE_LAYOUT_UNDEFINED; pre.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    pre.srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED; pre.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
    pre.image=swapchainImages[imgIdx]; pre.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    pre.srcAccessMask=0; pre.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
    vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0,nullptr, 0,nullptr, 1,&pre);

    VkImageBlit blit{};
    blit.srcSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
    blit.srcOffsets[1]={(int32_t)compositeW,(int32_t)compositeH,1};
    blit.dstSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
    blit.dstOffsets[1]={(int32_t)swapchainExt.width,(int32_t)swapchainExt.height,1};
    vk_.CmdBlitImage(cb, generated, VK_IMAGE_LAYOUT_GENERAL,
                     swapchainImages[imgIdx], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit,
                     VK_FILTER_NEAREST);

    if (cursorDrawnPerPresent() && cursorOverlay_.draw) {
        // The overlay pass takes the swapchain image from TRANSFER_DST to PRESENT_SRC itself.
        recordCursorOverlay(cb, imgIdx);
    } else {
        VkImageMemoryBarrier post{};
        post.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        post.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; post.newLayout=VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        post.srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED; post.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
        post.image=swapchainImages[imgIdx]; post.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        post.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT; post.dstAccessMask=0;
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0,nullptr, 0,nullptr, 1,&post);
    }
}

void VulkanRendererContext::setGsfgPackPath(const char* path) {
    std::unique_lock<std::shared_mutex> frameLock(frameMutex);
    std::lock_guard<std::mutex> lk(renderMutex);
    const std::string next = path ? path : "";
    // Only a genuinely NEW pack justifies throwing the engine away. This is
    // called on every multiplier change, and rebuilding here would discard the
    // graph and the pacer's rate history each time.
    if (next == gsfgPackPath_ && gsfgEngine_) return;
    gsfgPackPath_ = next;
    gsfgEngineTried_ = false;    // a new pack deserves a fresh attempt
    vk_.DeviceWaitIdle(device);
    gsfgEngine_.reset();
}

void VulkanRendererContext::setFrameGenTuning(float flowScale, float refreshHz) {
    fgFlowScale_.store(flowScale, std::memory_order_relaxed);
    // A zero here means the display could not be read, not "0 Hz". Keeping the
    // last good value stops the pacer's refresh ceiling flapping between 144
    // and unknown, which was visible in the logs as max= alternating.
    if (refreshHz > 1.0f) fgRefreshHz_.store(refreshHz, std::memory_order_relaxed);
    fgConfigDirty_.store(true, std::memory_order_relaxed);
}

void VulkanRendererContext::compositeExtentFor(uint32_t& w, uint32_t& h) const {
    // Reverted: this used to shrink the composite/generation target below the
    // panel resolution, which made the upscale filters (FSR/Lanczos/SGSR) render
    // below display resolution. Frame generation now always composites and
    // generates at the real swapchain resolution.
    w = swapchainExt.width;
    h = swapchainExt.height;
}

void VulkanRendererContext::recordCompositeToSwapchainTransfer(
    VkCommandBuffer cb, VkImage src, uint32_t imgIdx) {
    if (compositeW == swapchainExt.width && compositeH == swapchainExt.height) {
        VkImageCopy region{};
        region.srcSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
        region.dstSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
        region.extent={compositeW,compositeH,1};
        vk_.CmdCopyImage(cb, src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                         swapchainImages[imgIdx], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
        return;
    }

    VkImageBlit blit{};
    blit.srcSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
    blit.srcOffsets[1]={(int32_t)compositeW,(int32_t)compositeH,1};
    blit.dstSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
    blit.dstOffsets[1]={(int32_t)swapchainExt.width,(int32_t)swapchainExt.height,1};
    vk_.CmdBlitImage(cb, src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                     swapchainImages[imgIdx], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit,
                     gsfgCaps_.linearBlitOnSwapchainFormat ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
}

// ================== Native GSFG: per-present software cursor =================
// GSFG warps whatever it is handed along the estimated flow field. A cursor
// composited into the frame is therefore smeared across the generated frames,
// which is very visible on a Wine desktop. So while frame gen is armed the
// cursor is left OUT of the composite and drawn once into every presented
// image - real and generated alike - by a load-op pass that also performs the
// final transition to PRESENT_SRC.

bool VulkanRendererContext::cursorDrawnPerPresent() const {
    return compositeActive() && cursorOverlayRenderPass != VK_NULL_HANDLE;
}

bool VulkanRendererContext::createCursorOverlayRenderPass() {
    if (!cursorOverlayRenderPass)
        cursorOverlayRenderPass = createCompatibleRenderPass(VK_ATTACHMENT_LOAD_OP_LOAD,
            VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
    return cursorOverlayRenderPass != VK_NULL_HANDLE;
}

void VulkanRendererContext::recordCursorOverlay(VkCommandBuffer cb, uint32_t imgIdx) {
    if (!cursorDrawnPerPresent()) return;
    const CursorOverlay& c = cursorOverlay_;
    if (!c.draw || cursorDS == VK_NULL_HANDLE || imgIdx >= swapchainFBs.size()) return;

    // The copy left the image in TRANSFER_DST; the render pass wants
    // COLOR_ATTACHMENT_OPTIMAL and hands it on as PRESENT_SRC itself.
    VkImageMemoryBarrier toAttachment{};
    toAttachment.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toAttachment.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toAttachment.newLayout=VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    toAttachment.srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
    toAttachment.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
    toAttachment.image=swapchainImages[imgIdx];
    toAttachment.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    toAttachment.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
    toAttachment.dstAccessMask=VK_ACCESS_COLOR_ATTACHMENT_READ_BIT|VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, 0,nullptr, 0,nullptr, 1,&toAttachment);

    VkRenderPassBeginInfo rpi{}; rpi.sType=VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    rpi.renderPass=cursorOverlayRenderPass; rpi.framebuffer=swapchainFBs[imgIdx];
    rpi.renderArea={{0,0},swapchainExt};
    rpi.clearValueCount=0; rpi.pClearValues=nullptr;   // LOAD_OP_LOAD: nothing to clear
    vk_.CmdBeginRenderPass(cb,&rpi,VK_SUBPASS_CONTENTS_INLINE);

    VkViewport vp{0,0,(float)swapchainExt.width,(float)swapchainExt.height,0,1};
    VkRect2D   sc{{0,0},swapchainExt};
    vk_.CmdSetViewport(cb,0,1,&vp);
    vk_.CmdSetScissor(cb,0,1,&sc);
    // `pipeline` is the alpha-blended window pipeline, built against renderPass;
    // format compatibility is what makes it usable with the overlay pass too.
    vk_.CmdBindPipeline(cb,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline);
    vk_.CmdBindDescriptorSets(cb,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeLayout,0,1,&cursorDS,0,nullptr);

    const float cx=(float)((int)c.ptrX-c.hotX), cy=(float)((int)c.ptrY-c.hotY);
    WindowPushConstants pc{};
    pc.ndcX0=(c.ox+cx*c.sx)/c.cw*2.f-1.f;
    pc.ndcY0=(c.oy+cy*c.sy)/c.ch*2.f-1.f;
    pc.ndcX1=(c.ox+(cx+c.w)*c.sx)/c.cw*2.f-1.f;
    pc.ndcY1=(c.oy+(cy+c.h)*c.sy)/c.ch*2.f-1.f;
    pc.useTexAlpha=1;
    getPreRotationCosSin(pc.cosR, pc.sinR);
    vk_.CmdPushConstants(cb,pipeLayout,VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,
                         0,sizeof(pc),&pc);
    vk_.CmdDraw(cb,4,1,0,0);
    vk_.CmdEndRenderPass(cb);
}

void VulkanRendererContext::frameGenStats(float out[6]) const {
    out[0] = out[1] = out[2] = 0.0f;
    out[4] = -1.0f;
    // The presented rate is measured by the renderer itself, per present.
    out[3] = fgPresentedRate_;
    out[5] = fgChainMsPerGen_;
    if (!gsfgEngine_) return;
    out[0] = out[1] = (float)fgPlan_.generations;
    out[2] = gsfgEngine_->sourceRate();
}

void VulkanRendererContext::trackPresentedRate(uint32_t presents) {
    const auto now = std::chrono::steady_clock::now();
    if (!fgRateWindowOpen_) {
        fgRateWindowStart_ = now;
        fgRateWindowOpen_  = true;
        fgPresentAccum_    = 0;
        fgSourceAccum_     = 0;
    }
    fgPresentAccum_ += presents;
    fgSourceAccum_++;

    const float elapsed = std::chrono::duration<float>(now - fgRateWindowStart_).count();
    if (elapsed < 0.5f) return;                    // half-second window
    const float rate = (float)fgPresentAccum_ / elapsed;
    // Same smoothing shape the pacer uses, so the two numbers are comparable.
    fgPresentedRate_ = fgPresentedRate_ > 0.0f
        ? fgPresentedRate_ + (rate - fgPresentedRate_) * 0.25f
        : rate;
    const float sourceRate = (float)fgSourceAccum_ / elapsed;
    fgSourceRate_ = fgSourceRate_ > 0.0f
        ? fgSourceRate_ + (sourceRate - fgSourceRate_) * 0.25f
        : sourceRate;
    fgRateWindowStart_ = now;
    fgPresentAccum_    = 0;
    fgSourceAccum_     = 0;
}

void VulkanRendererContext::setFrameGenArmed(bool armed, int multiplier) {
    std::unique_lock<std::shared_mutex> frameLock(frameMutex);
    const bool was = fgArmed_.load(std::memory_order_relaxed);
    const int  wasMult = fgMultiplier_.load(std::memory_order_relaxed);
    fgMultiplier_.store(multiplier, std::memory_order_relaxed);
    // A multiplier change must reach the pacer, or it keeps capping at the
    // level it was built with.
    if (wasMult != multiplier) {
        fgConfigDirty_.store(true, std::memory_order_relaxed);
        RLOG("gsfg-native: multiplier %d -> %d (armed=%d)", wasMult, multiplier, (int)armed);
    }
    if (was == armed) return;

    fgArmed_.store(armed, std::memory_order_relaxed);
    RLOG("gsfg-native: frame gen %s (multiplier=%d) - recreating swapchain",
         armed ? "ARMED" : "disarmed", multiplier);
    // The swapchain's usage flags and image count both depend on this, so it
    // has to be rebuilt. The existing resize path already does that safely.
    fbResized.store(true);
    dirtyCV.notify_one();
}

bool VulkanRendererContext::compositeActive() const {
    // Every gate must hold, or we run the pre-GSFG path unchanged.
    if (!fgArmed_.load(std::memory_order_relaxed)) return false;
    if (!fgCapsOk()) return false;
    return compositeArmed;
}

VkRenderPass VulkanRendererContext::targetRenderPass() const {
    return compositeActive() ? compositeRenderPass : renderPass;
}

VkFramebuffer VulkanRendererContext::targetFramebuffer(uint32_t imgIdx) const {
    if (compositeActive() && compositeIndex < compositeTargets.size())
        return compositeTargets[compositeIndex].fb;
    return swapchainFBs[imgIdx];
}

void VulkanRendererContext::copyCompositeToSwapchain(VkCommandBuffer cb, uint32_t imgIdx) {
    if (!compositeActive() || compositeIndex >= compositeTargets.size()) return;
    const CompositeTarget& t = compositeTargets[compositeIndex];

    // composite: GENERAL (render pass left it there) -> TRANSFER_SRC
    // swapchain: UNDEFINED (nothing has touched it this frame) -> TRANSFER_DST
    VkImageMemoryBarrier pre[2]{};
    pre[0].sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    pre[0].oldLayout=VK_IMAGE_LAYOUT_GENERAL; pre[0].newLayout=VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    pre[0].srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED; pre[0].dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
    pre[0].image=t.img; pre[0].subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    pre[0].srcAccessMask=VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT|VK_ACCESS_SHADER_WRITE_BIT;
    pre[0].dstAccessMask=VK_ACCESS_TRANSFER_READ_BIT;

    pre[1].sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    pre[1].oldLayout=VK_IMAGE_LAYOUT_UNDEFINED; pre[1].newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    pre[1].srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED; pre[1].dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
    pre[1].image=swapchainImages[imgIdx]; pre[1].subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    pre[1].srcAccessMask=0; pre[1].dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;

    vk_.CmdPipelineBarrier(cb,
        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT|VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0,nullptr, 0,nullptr, 2,pre);

    recordCompositeToSwapchainTransfer(cb, t.img, imgIdx);

    // The cursor overlay, when it runs, takes the image from TRANSFER_DST to
    // PRESENT_SRC itself; only do it here when there is no overlay.
    if (cursorDrawnPerPresent() && cursorOverlay_.draw) {
        recordCursorOverlay(cb, imgIdx);
        return;
    }

    VkImageMemoryBarrier post{};
    post.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    post.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; post.newLayout=VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    post.srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED; post.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
    post.image=swapchainImages[imgIdx]; post.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    post.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT; post.dstAccessMask=0;
    vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0,nullptr, 0,nullptr, 1,&post);
}
