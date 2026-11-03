#pragma once
#include "usb_stream_core.h"

namespace noir {
// Explicit feedback is a rate estimate, never a resampler. Only the distribution
// of whole PCM frames over USB service intervals changes; PCM bytes stay intact.
class FeedbackPlan {
    static uint64_t nominal(int rate, int intervals) {
        if (rate < 44100 || rate > 384000 || intervals < 1000 || intervals > 8000) throw std::runtime_error("Invalid feedback timing");
        return (static_cast<uint64_t>(rate) << 32) / intervals;
    }
    uint64_t nominal_, rate_, phase_ = 0, proposedPhase_ = 0;
    int baseIntervals_, intervals_;
    int64_t started_, last_ = -1;
public:
    int accepted = 0, invalid = 0;
    FeedbackPlan(int rate, int intervals, bool highSpeed, int64_t now):
        nominal_(nominal(rate, intervals)), rate_(nominal_),
        baseIntervals_(highSpeed ? 8000 : 1000), intervals_(intervals), started_(now) {
        if (rate < 44100 || rate > 384000 || intervals < 1000 || intervals > baseIntervals_ || baseIntervals_ % intervals) throw std::runtime_error("Invalid feedback timing");
    }
    void update(const uint8_t* data, int size, int64_t now) {
        if ((size != 3 && size != 4) || (baseIntervals_ == 8000 && size != 4)) { reject(); return; }
        uint64_t raw = 0;
        for (int i = 0; i < size; ++i) raw |= static_cast<uint64_t>(data[i]) << (8 * i);
        // Some clocks report zero while locking. It is not a new rate and never
        // refreshes the 500 ms deadline; continue briefly at the last known rate.
        if (!raw) { ++invalid; return; }
        const uint64_t target = (raw << (size == 3 ? 18 : 16)) * (baseIntervals_ / intervals_);
        // Reject wrong units, runaway clocks and malformed rate reports. No guesses.
        const uint64_t tolerance = nominal_ / 100; // +/- 1%, bounded oscillator drift.
        if (target < nominal_ - tolerance || target > nominal_ + tolerance) { reject(); return; }
        rate_ = static_cast<uint64_t>(static_cast<int64_t>(rate_) + (static_cast<int64_t>(target) - static_cast<int64_t>(rate_)) / 8);
        last_ = now; ++accepted; consecutiveInvalid_ = 0;
    }
    int peek(int64_t now) {
        if (now - (last_ < 0 ? started_ : last_) > 500000) throw std::runtime_error("Explicit USB feedback missing or stale for 500 ms");
        const auto total = phase_ + rate_;
        proposedPhase_ = total & 0xffffffffULL;
        return static_cast<int>(total >> 32);
    }
    void commit() { phase_ = proposedPhase_; }
    double sampleRate() const { return static_cast<double>(rate_) * intervals_ / 4294967296.0; }
private:
    int consecutiveInvalid_ = 0;
    void reject() { ++invalid; if (++consecutiveInvalid_ >= 3) throw std::runtime_error("Three invalid explicit USB feedback packets"); }
};

using FeedbackSlots = std::array<Slot, 4>;
class FeedbackUsbIo final : public UsbIo {
    UsbIo& io_;
    FeedbackSlots& slots_;
    int endpoint_, packetBytes_;
    bool closing_ = false;
    void submit(Slot& slot) {
        auto* urb = slot.urb;
        if (!urb || slot.pending) throw std::runtime_error("Feedback URB unavailable");
        std::memset(urb, 0, sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc));
        urb->type = USBDEVFS_URB_TYPE_ISO; urb->endpoint = static_cast<unsigned char>(endpoint_);
        urb->flags = USBDEVFS_URB_ISO_ASAP;
        urb->buffer = slot.payload.data(); urb->buffer_length = packetBytes_;
        urb->number_of_packets = 1; urb->iso_frame_desc[0].length = static_cast<unsigned int>(packetBytes_);
        const int result = io_.control(USBDEVFS_SUBMITURB, urb);
        if (result < 0) throw std::runtime_error("Feedback SUBMITURB errno=" + std::to_string(-result));
        slot.pending = true;
    }
public:
    FeedbackPlan plan;
    FeedbackUsbIo(UsbIo& io, FeedbackSlots& slots, int endpoint, int packetBytes, int rate, int intervals, bool highSpeed):
        io_(io), slots_(slots), endpoint_(endpoint), packetBytes_(packetBytes), plan(rate, intervals, highSpeed, io.nowUs()) {
        if (endpoint < 0x81 || endpoint > 0x8f || (packetBytes != 3 && packetBytes != 4) || (highSpeed && packetBytes != 4)) throw std::runtime_error("Invalid explicit feedback endpoint");
        for (const auto& slot : slots_) if (slot.pending) throw std::runtime_error("Previous feedback transfers still pending");
    }
    int control(unsigned long request, void* argument) override { return io_.control(request, argument); }
    int wait(int timeoutMs) override { return io_.wait(timeoutMs); }
    int64_t nowUs() override { return io_.nowUs(); }
    bool stopped() override { return io_.stopped(); }
    void startAuxiliary() override { for (auto& slot : slots_) submit(slot); }
    bool consumeAuxiliary(usbdevfs_urb* result, bool validate) override {
        for (auto& slot : slots_) if (slot.urb == result) {
            if (!slot.pending) throw std::runtime_error("Duplicate feedback completion");
            slot.pending = false;
            if (validate && !closing_) {
                const auto& packet = result->iso_frame_desc[0];
                if (result->status || result->error_count || packet.status || packet.actual_length > static_cast<unsigned int>(packetBytes_)) throw std::runtime_error("Explicit feedback transfer failed");
                plan.update(slot.payload.data(), static_cast<int>(packet.actual_length), nowUs());
                if (!stopped()) submit(slot);
            }
            return true;
        }
        return false;
    }
    void cancelAuxiliary() override {
        closing_ = true;
        for (auto& slot : slots_) if (slot.pending) io_.control(USBDEVFS_DISCARDURB, slot.urb);
    }
    bool auxiliaryPending() override {
        return std::any_of(slots_.begin(), slots_.end(), [](const Slot& slot) { return slot.pending; });
    }
    int packetFrames(PacketPlan&) override { return plan.peek(nowUs()); }
    void packetSubmitted() override { plan.commit(); }
    std::string report() const {
        return "\nExplicit feedback: accepted=" + std::to_string(plan.accepted) + ", invalid=" + std::to_string(plan.invalid) + "; estimated Hz=" + std::to_string(plan.sampleRate());
    }
};
}
