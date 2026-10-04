// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Ported into Bannerlator's compositor from WinNative (GPL-3.0-or-later),
// whose LSFG port is credited to Camille LaVey / the Eden Emulator Project and
// follows upstream lsfg-vk. (The pacing logic below is unchanged LSFG-era code;
// only the identifiers were renamed when LSFG was replaced by GSFG.) Only the Vulkan dispatch differs: Bannerlator
// resolves entry points through the renderer's own table (see gsfg_vkd.h).

#include "gsfg_pacer.hpp"

#include <algorithm>
#include <cmath>

namespace gsfg {

namespace {

using Clock = std::chrono::steady_clock;

constexpr float INTERVAL_SMOOTHING = 0.25f;
constexpr float SOURCE_SMOOTHING = 0.15f;
constexpr float SOURCE_STALE_SECONDS = 0.5f;
constexpr float DISCONTINUITY_SECONDS = 0.25f;
constexpr float HEADROOM_EPSILON = 0.02f;
constexpr float CREDIT_EPSILON = 1.0e-4f;
constexpr float SOURCE_ACCUM_FLOOR = 0.01f;
constexpr uint32_t MIN_RATE_SAMPLES = 12;
constexpr float PHASE_PULL_RATIO = 0.02f;   // |ratio - integer| below this counts as an integer ratio
constexpr float PHASE_PULL_STEP = 0.03f;    // output periods the grid phase may move per source frame

}

size_t Pacer::MaxGenerations() const {
    if (config.multiplier < 2) return 0;
    if (config.target_rate != 0) return GSFG_MAX_MULTIPLIER - 1;
    return std::min<size_t>(config.multiplier, GSFG_MAX_MULTIPLIER) - 1;
}

void Pacer::TrackSourceRate(Clock::time_point now, uint64_t source_frames) {
    if (!last_source_sample) {
        last_source_sample = now;
        last_source_frames = source_frames;
        return;
    }

    const float elapsed = std::chrono::duration<float>(now - *last_source_sample).count();
    if (elapsed <= 0.0f) {
        return;
    }

    last_source_sample = now;
    const uint64_t drawn =
        source_frames > last_source_frames ? source_frames - last_source_frames : 0;
    last_source_frames = source_frames;

    if (elapsed > SOURCE_STALE_SECONDS) {
        source_frame_accum = 0.0f;
        source_time_accum = 0.0f;
        source_interval = 0.0f;
        source_samples = 0;
        return;
    }

    last_drawn = drawn;
    last_elapsed = elapsed;
    source_frame_accum += (static_cast<float>(drawn) - source_frame_accum) * SOURCE_SMOOTHING;
    source_time_accum += (elapsed - source_time_accum) * SOURCE_SMOOTHING;
    source_interval =
        source_frame_accum > SOURCE_ACCUM_FLOOR ? source_time_accum / source_frame_accum : 0.0f;
    if (source_samples < MIN_RATE_SAMPLES) ++source_samples;
}

void Pacer::TrackLoopRate(float interval_seconds) {
    loop_interval = loop_interval > 0.0f
                        ? loop_interval + (interval_seconds - loop_interval) * INTERVAL_SMOOTHING
                        : interval_seconds;
    if (loop_samples < MIN_RATE_SAMPLES) ++loop_samples;
}

bool Pacer::RatesSettled() const {
    return source_samples >= MIN_RATE_SAMPLES && loop_samples >= MIN_RATE_SAMPLES;
}

size_t Pacer::HeadroomLimit() const {
    if (config.refresh_rate <= 0.0f || source_interval <= 0.0f ||
        source_samples < MIN_RATE_SAMPLES) {
        return GSFG_MAX_MULTIPLIER - 1;
    }

    const float budget = std::ceil(config.refresh_rate * source_interval - HEADROOM_EPSILON);
    return budget < 2.0f ? 0 : static_cast<size_t>(budget) - 1;
}

PacerPlan Pacer::Plan(size_t capacity, uint64_t source_frames) {
    const size_t ceiling = std::min(capacity, MaxGenerations());
    if (ceiling == 0) {
        Reset();
        return {};
    }

    const Clock::time_point now = Clock::now();
    TrackSourceRate(now, source_frames);
    if (!last_frame) {
        last_frame = now;
        return {};
    }

    const float interval_seconds = std::chrono::duration<float>(now - *last_frame).count();
    last_frame = now;

    if (interval_seconds <= 0.0f || interval_seconds > DISCONTINUITY_SECONDS) {
        output_credit = 0.0f;
        return PacerPlan{0, true};
    }

    TrackLoopRate(interval_seconds);

    float target_rate = static_cast<float>(config.target_rate);
    if (target_rate > 0.0f && config.refresh_rate > 0.0f) {
        target_rate = std::min(target_rate, config.refresh_rate);
    }

    if (target_rate == 0.0f) {
        // No explicit target rate: the multiplier is the multiplier, full stop.
        // The panel-headroom clamp was here briefly and was removed at the
        // user's decision; this is also upstream's behaviour on this branch.
        //
        // What that means above the panel's refresh rate, for the record: the
        // display cannot show more frames than it refreshes, so under FIFO the
        // surplus presents queue and the render thread waits on acquire. Nothing
        // is displayed faster, and the compositor falls behind on guest
        // deliveries instead. Below the refresh rate - every source at or under
        // panel/4 - this branch behaves identically with or without the clamp.
        // To restore it: limit = std::min(ceiling, HeadroomLimit()).
        output_credit = 0.0f;
        limit = ceiling;
        return PacerPlan{limit, true};
    }

    const size_t allowed = std::min(ceiling, HeadroomLimit());
    const float desired_outputs = loop_interval * target_rate;
    if (allowed == 0 || desired_outputs <= 1.0f) {
        output_credit = 0.0f;
        limit = 0;
        return {};
    }

    if (!GSFG_TICK_PACING) {
        // Previous behaviour: evenly spaced frames, the source frame always shown last.
        output_credit += desired_outputs;
        const size_t outputs =
            std::max<size_t>(1, static_cast<size_t>(std::floor(output_credit + CREDIT_EPSILON)));
        const size_t generations = std::min(outputs - 1, allowed);
        output_credit -= static_cast<float>(generations + 1);
        if (output_credit < 0.0f) {
            output_credit = 0.0f;
        } else if (generations == allowed && output_credit >= 1.0f) {
            output_credit = std::fmod(output_credit, 1.0f);
        }
        limit = generations;
        return PacerPlan{generations, true};
    }

    // output_credit is the phase of the output tick grid: output periods elapsed
    // since the last tick, as of the previous source frame (time 0). This
    // interval's ticks fall at (k - phase) / desired_outputs, k = 1..outputs.
    const float phase = output_credit;
    output_credit += desired_outputs;
    // A tick that falls just after the source frame (within the snap) is counted
    // as the source frame's own tick; the negative remainder this leaves is
    // clamped below, which pulls the grid back onto the source frames - so at an
    // integer ratio, after any jitter, the source frames stay on the grid.
    const size_t outputs = std::max<size_t>(
        1, static_cast<size_t>(std::floor(output_credit + desired_outputs * GSFG_TIME_SNAP + CREDIT_EPSILON)));
    auto tick = [&](size_t k) { return (static_cast<float>(k) - phase) / desired_outputs; };

    // The source frame is shown when the last tick is (within the snap) on it;
    // otherwise a frame generated at that tick is shown instead.
    PacerPlan plan{0, true};
    plan.present_source = std::fabs(1.0f - tick(outputs)) <= GSFG_TIME_SNAP;
    size_t generations = plan.present_source ? outputs - 1 : outputs;

    // The headroom limit counts the presents next to the source frame; without
    // the source frame there is room for one more generated frame.
    const size_t room = plan.present_source ? allowed : std::min(ceiling, allowed + 1);
    if (generations > room) {
        // Not enough headroom for the grid: the old behaviour - source frame last,
        // as many evenly spaced frames before it as fit.
        plan.present_source = true;
        generations = std::min(outputs - 1, allowed);
    } else if (generations > 0) {
        bool even = plan.present_source;
        float prev = 0.0f;
        for (size_t k = 0; k < generations; k++) {
            float t = tick(k + 1);
            t = std::clamp(t, prev + 1.0e-3f, 0.999f - 1.0e-3f * static_cast<float>(generations - 1 - k));
            plan.times[k] = prev = t;
            even = even && std::fabs(t - static_cast<float>(k + 1) / static_cast<float>(generations + 1))
                               <= GSFG_TIME_SNAP;
        }
        plan.timed = !even;   // evenly spaced ending on the source frame: the cheaper shared-flow graph
    }
    plan.generations = generations;

    // Every tick of the interval was presented (or, capped, generations + 1 of them).
    output_credit -= static_cast<float>(plan.present_source ? generations + 1 : generations);
    // At an (almost) integer ratio the grid phase never moves by itself, so a
    // grid that started off the source frames would stay off them - and every
    // source frame would be replaced. Pull it back a little each interval (an
    // invisible fraction of a frame) until the source frames are on it.
    if (std::fabs(desired_outputs - std::round(desired_outputs)) < PHASE_PULL_RATIO) {
        const float off = output_credit - std::round(output_credit);
        output_credit -= std::clamp(off, -PHASE_PULL_STEP, PHASE_PULL_STEP);
    }
    if (output_credit < 0.0f) {
        output_credit = 0.0f;
    } else if (generations == allowed && output_credit >= 1.0f) {
        output_credit = std::fmod(output_credit, 1.0f);
    }

    limit = generations;
    return plan;
}

PacerStats Pacer::Stats() const {
    PacerStats stats;
    stats.source_rate = source_interval > 0.0f ? 1.0f / source_interval : 0.0f;
    stats.loop_rate = loop_interval > 0.0f ? 1.0f / loop_interval : 0.0f;
    stats.refresh_rate = config.refresh_rate;
    stats.target_rate = static_cast<float>(config.target_rate);
    stats.slots = config.refresh_rate * source_interval;
    stats.limit = limit;
    stats.rates_settled = RatesSettled();
    stats.last_drawn = last_drawn;
    stats.last_elapsed = last_elapsed;
    stats.source_frames = last_source_frames;
    return stats;
}

void Pacer::Reset() {
    last_frame.reset();
    last_source_sample.reset();
    last_source_frames = 0;
    source_interval = 0.0f;
    source_frame_accum = 0.0f;
    source_time_accum = 0.0f;
    loop_interval = 0.0f;
    source_samples = 0;
    loop_samples = 0;
    last_drawn = 0;
    last_elapsed = 0.0f;
    output_credit = 0.0f;
    limit = 0;
}

}
