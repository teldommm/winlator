// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Ported into Bannerlator's compositor from WinNative (GPL-3.0-or-later),
// whose LSFG port is credited to Camille LaVey / the Eden Emulator Project and
// follows upstream lsfg-vk. (The pacing logic below is unchanged LSFG-era code;
// only the identifiers were renamed when LSFG was replaced by GSFG.) Only the Vulkan dispatch differs: Bannerlator
// resolves entry points through the renderer's own table (see gsfg_vkd.h).
//
// GSFG addition: with a target rate every present lands on the output tick grid.
// Presents go out on uniform display ticks (FIFO), so frame k of a source
// interval has to SHOW the content of its tick: the plan gives the generated
// frames their tick times instead of spacing them evenly, and when the source
// frame itself is off the grid it is not presented - a frame generated at the
// last tick takes its place. (Evenly spaced frames ending on the source frame
// look right only when the target is an integer multiple of the source rate.)

#pragma once

#include <chrono>
#include <cstddef>
#include <cstdint>
#include <optional>

namespace gsfg {

constexpr size_t GSFG_MAX_MULTIPLIER = 4;

struct PacerConfig {
    uint32_t multiplier{2};
    uint32_t target_rate{};
    float refresh_rate{};
};

// Generated frames whose times are within this of evenly spaced are left evenly
// spaced: a fraction of a millisecond on screen, and the even case lets the
// generated frames share half of the flow estimate (cheaper).
constexpr float GSFG_TIME_SNAP = 0.02f;

// Target-rate pacing on the output tick grid (see above). false restores the
// previous behaviour: evenly spaced frames, source frame always shown last.
// Multiplier pacing (no target rate) is not affected either way.
constexpr bool GSFG_TICK_PACING = true;

struct PacerPlan {
    size_t generations{};
    bool warm{};
    bool timed{};                                 // times[] holds the frames' times (else evenly spaced)
    bool present_source{true};                    // false: the last generated frame replaces the source frame
    float times[GSFG_MAX_MULTIPLIER - 1]{};       // in (0, 1), strictly increasing
};

struct PacerStats {
    float source_rate{};
    float loop_rate{};
    float refresh_rate{};
    float target_rate{};
    float slots{};
    size_t limit{};
    bool rates_settled{};
    uint64_t last_drawn{};
    float last_elapsed{};
    uint64_t source_frames{};
};

class Pacer {
public:
    void SetConfig(const PacerConfig& config_) {
        config = config_;
    }

    [[nodiscard]] const PacerConfig& Config() const {
        return config;
    }

    [[nodiscard]] size_t MaxGenerations() const;

    [[nodiscard]] PacerPlan Plan(size_t capacity, uint64_t source_frames);

    [[nodiscard]] PacerStats Stats() const;

    void Reset();

private:
    using Clock = std::chrono::steady_clock;

    void TrackSourceRate(Clock::time_point now, uint64_t source_frames);
    void TrackLoopRate(float interval_seconds);
    [[nodiscard]] bool RatesSettled() const;
    [[nodiscard]] size_t HeadroomLimit() const;

    PacerConfig config;

    std::optional<Clock::time_point> last_frame;
    std::optional<Clock::time_point> last_source_sample;
    uint64_t last_source_frames{};
    float source_interval{};
    float source_frame_accum{};
    float source_time_accum{};
    float loop_interval{};
    uint32_t source_samples{};
    uint32_t loop_samples{};
    uint64_t last_drawn{};
    float last_elapsed{};
    float output_credit{};
    size_t limit{};
};

}
