#pragma once
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>
#include <array>
#include <mutex>

namespace noir {
// xHCI's trb_block_event_intr() always leaves the last TD unblocked. A one-packet
// URB therefore avoids intermediate BEI events without changing audio samples.
constexpr int kPackets = 1;
constexpr int kDepth = 128; // Legacy 15-second probe: 16 ms.
constexpr int kLiveDepth = 1024; // Player: 128 ms, still one packet per URB.
constexpr int kLiveBufferSeconds = 8;
static_assert(kPackets == 1, "The BEI compatibility path must use one packet per URB");
constexpr int kRate = 44100;
constexpr int kFrameBytes = 4;
class PacketPlan {
    int rate_, intervals_, phase_ = 0;
public:
    PacketPlan(int rate, int intervals): rate_(rate), intervals_(intervals) {
        if (rate <= 0 || rate > 384000 || intervals <= 0 || intervals > 8000) throw std::runtime_error("Invalid packet timing");
    }
    int next() { phase_ += rate_; const int result = phase_ / intervals_; phase_ %= intervals_; return result; }
};
struct Slot {
    usbdevfs_urb* urb = static_cast<usbdevfs_urb*>(std::calloc(1, sizeof(usbdevfs_urb) + kPackets * sizeof(usbdevfs_iso_packet_desc)));
    bool pending = false;
    std::array<uint8_t, 2048> payload{};
    uint64_t offset = 0;
    ~Slot() { std::free(urb); }
};
// Acknowledged data remains in the ring until USB completion, so Pause can
// cancel queued requests and resume from the first uncompleted PCM frame.
struct PcmSource {
    virtual ~PcmSource() = default;
    virtual int fill(Slot& slot, int wanted) = 0;
    virtual void acknowledge(const Slot& slot, int bytes) = 0;
    virtual bool ended() = 0;
    virtual bool live() const = 0;
};
class VectorPcmSource final : public PcmSource {
    std::vector<uint8_t>& pcm_; size_t cursor_ = 0;
public:
    explicit VectorPcmSource(std::vector<uint8_t>& pcm): pcm_(pcm) {}
    int fill(Slot& slot, int wanted) override {
        const auto size = std::min(static_cast<size_t>(wanted), pcm_.size() - cursor_);
        slot.urb->buffer = pcm_.data() + cursor_; cursor_ += size; return static_cast<int>(size);
    }
    void acknowledge(const Slot&, int) override {}
    bool ended() override { return cursor_ == pcm_.size(); }
    bool live() const override { return false; }
};
class LivePcmQueue final : public PcmSource {
    std::mutex mutex_;
    std::vector<uint8_t> ring_;
    uint64_t written_ = 0, read_ = 0, completed_ = 0;
    bool eos_ = false;
    int frameBytes_ = kFrameBytes;
public:
    explicit LivePcmQueue(size_t capacity = kRate * kFrameBytes * 2): ring_(capacity) {
        if (capacity < 1024 || capacity % kFrameBytes) throw std::runtime_error("Invalid ring capacity");
    }
    void configure(int rate, int frameBytes) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (written_ != 0 || rate < 44100 || rate > 384000 || (frameBytes != 4 && frameBytes != 6 && frameBytes != 8)) throw std::runtime_error("Invalid live configuration");
        frameBytes_ = frameBytes; ring_.resize(static_cast<size_t>(rate) * frameBytes * kLiveBufferSeconds);
    }
    int push(const uint8_t* data, int size) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (eos_ || size < 0 || size % frameBytes_) throw std::runtime_error("Invalid live PCM write");
        const auto count = std::min(static_cast<uint64_t>(size), ring_.size() - (written_ - completed_));
        const auto start = static_cast<size_t>(written_ % ring_.size());
        const auto first = std::min(static_cast<size_t>(count), ring_.size() - start);
        std::memcpy(ring_.data() + start, data, first);
        std::memcpy(ring_.data(), data + first, static_cast<size_t>(count) - first);
        written_ += count; return static_cast<int>(count);
    }
    int fill(Slot& slot, int wanted) override {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto available = written_ - read_;
        if (available < static_cast<uint64_t>(wanted) && !eos_) return 0;
        const auto size = std::min(available, static_cast<uint64_t>(wanted));
        if (size > slot.payload.size()) throw std::runtime_error("Live packet exceeds slot");
        slot.offset = read_;
        const auto start = static_cast<size_t>(read_ % ring_.size());
        const auto first = std::min(static_cast<size_t>(size), ring_.size() - start);
        std::memcpy(slot.payload.data(), ring_.data() + start, first);
        std::memcpy(slot.payload.data() + first, ring_.data(), static_cast<size_t>(size) - first);
        read_ += size; slot.urb->buffer = slot.payload.data(); return static_cast<int>(size);
    }
    void acknowledge(const Slot& slot, int bytes) override {
        std::lock_guard<std::mutex> lock(mutex_);
        if (slot.offset != completed_ || completed_ + bytes > read_) throw std::runtime_error("Out-of-order live USB completion");
        completed_ += bytes;
    }
    bool ended() override { std::lock_guard<std::mutex> lock(mutex_); return eos_ && read_ == written_; }
    bool live() const override { return true; }
    void finish() { std::lock_guard<std::mutex> lock(mutex_); eos_ = true; }
    void rewind() { std::lock_guard<std::mutex> lock(mutex_); read_ = completed_; }
    void clear() { std::lock_guard<std::mutex> lock(mutex_); written_ = read_ = completed_ = 0; eos_ = false; }
    uint64_t position() { std::lock_guard<std::mutex> lock(mutex_); return completed_; }
    uint64_t buffered() { std::lock_guard<std::mutex> lock(mutex_); return written_ - completed_; }
    uint64_t written() { std::lock_guard<std::mutex> lock(mutex_); return written_; }
};
/** Syscalls return their result, or negative errno. Tests use exactly the same queue loop. */
struct UsbIo {
    virtual ~UsbIo() = default;
    virtual int control(unsigned long request, void* argument) = 0;
    virtual int wait(int timeoutMs) = 0;
    virtual int64_t nowUs() = 0;
    virtual bool stopped() = 0;
};
struct StreamResult {
    struct Completion { int64_t ms; uint64_t bytes; int pending; };
    std::string outcome = "complete", problem;
    int submitted = 0, completed = 0, packets = 0, packetErrors = 0, pending = 0;
    int queueDepth = kDepth, schedulingGaps = 0;
    int64_t maxSchedulingGapUs = 0;
    int pollWake = 0, pollTimeout = 0, wouldBlock = 0;
    int discarded = 0, discardNotFound = 0, discardFailed = 0, cleanupReaped = 0;
    uint64_t cleanupBytes = 0;
    uint64_t bytes = 0;
    int64_t elapsedUs = 0, idleUs = 0, maxGapUs = 0, maxSubmitUs = 0, maxReapUs = 0, maxWaitUs = 0;
    std::vector<Completion> recent;
    std::vector<std::string> cleanup;
    std::string report(int intervals, size_t size, int rate = kRate, int frameBytes = kFrameBytes) const {
        std::ostringstream out;
        out << "Native USB: " << outcome << "\nTransfer mode: single-packet URBs (BEI compatibility experiment)"
            << "\nQueue: " << queueDepth << " x " << kPackets << " packets; planned " << (1000 * queueDepth * kPackets / intervals) << " ms"
            << "\nService intervals/s: " << intervals << "\nPCM: stereo S" << frameBytes * 4 << "LE / " << rate << " Hz"
            << "\nURB submitted: " << submitted << ", completed: " << completed << ", pending after cancel: " << pending
            << "\nPackets completed: " << packets << ", packet errors: " << packetErrors
            << "\nBytes completed without packet error: " << bytes << " / " << size
            << "\nAudio completed: " << bytes * 1000 / (rate * frameBytes) << " ms; wall elapsed: " << elapsedUs / 1000 << " ms"
            << "\nIdle before cleanup: " << idleUs / 1000 << " ms; max completion gap: " << maxGapUs / 1000 << " ms"
            << "\nCompletion gaps exceeding half queue: " << schedulingGaps << "; largest: " << maxSchedulingGapUs / 1000 << " ms"
            << "\nMax submit/reap/wait: " << maxSubmitUs << "/" << maxReapUs << "/" << maxWaitUs << " us"
            << "\nPoll wake/timeout: " << pollWake << "/" << pollTimeout << "; REAP EAGAIN: " << wouldBlock;
        if (!problem.empty()) out << "\n" << problem;
        out << "\nRecent completions (wall ms, bytes, pending):";
        for (const auto& row : recent) out << "\n  " << row.ms << ", " << row.bytes << ", " << row.pending;
        out << "\nCleanup: discard=" << discarded << ", already completed/not found=" << discardNotFound << ", errors=" << discardFailed
            << ", reaped=" << cleanupReaped << ", bytes with packet status 0=" << cleanupBytes;
        out << "\nFirst cleanup URBs (status, good/unlinked/other packets):";
        for (const auto& row : cleanup) out << "\n  " << row;
        return out.str();
    }
};

template <typename Slots>
inline StreamResult runIsoSource(Slots& slots, PcmSource& source, int endpoint, int intervals, int maxPacket, UsbIo& io, int rate = kRate, int frameBytes = kFrameBytes) {
    if (frameBytes != 4 && frameBytes != 6 && frameBytes != 8) throw std::runtime_error("Invalid PCM frame size");
    const auto depth = static_cast<int>(std::size(slots));
    PacketPlan plan(rate, intervals);
    if (((rate + intervals - 1) / intervals) * frameBytes > maxPacket) throw std::runtime_error("Endpoint packet capacity is insufficient");
    for (auto& slot : slots) if (!slot.urb) throw std::bad_alloc();
    StreamResult stats;
    stats.queueDepth = depth;
    stats.recent.reserve(9); // Numeric trace only: no per-packet string allocations.
    int pending = 0;
    const auto start = io.nowUs();
    auto lastCompletion = start;
    auto call = [&](unsigned long request, void* arg, int64_t& maximum) {
        const auto before = io.nowUs(); const int result = io.control(request, arg);
        maximum = std::max(maximum, io.nowUs() - before);
        return result;
    };
    auto submit = [&](Slot& slot) {
        auto* urb = slot.urb;
        std::memset(urb, 0, sizeof(usbdevfs_urb) + kPackets * sizeof(usbdevfs_iso_packet_desc));
        urb->type = USBDEVFS_URB_TYPE_ISO; urb->endpoint = static_cast<unsigned char>(endpoint);
        urb->flags = USBDEVFS_URB_ISO_ASAP;
        auto nextPlan = plan;
        const int bytes = source.fill(slot, nextPlan.next() * frameBytes);
        if (bytes == 0) return false;
        plan = nextPlan;
        urb->iso_frame_desc[0].length = static_cast<unsigned int>(bytes);
        urb->number_of_packets = 1; urb->buffer_length = bytes;
        const int result = call(USBDEVFS_SUBMITURB, urb, stats.maxSubmitUs);
        if (result < 0) throw std::runtime_error("SUBMITURB errno=" + std::to_string(-result));
        slot.pending = true; ++pending; ++stats.submitted;
        return true;
    };
    auto reap = [&](bool validate) {
        usbdevfs_urb* result = nullptr;
        const int status = call(USBDEVFS_REAPURBNDELAY, &result, stats.maxReapUs);
        if (status == -EAGAIN || status == -EINTR) { ++stats.wouldBlock; return false; }
        if (status < 0) throw std::runtime_error("REAPURB errno=" + std::to_string(-status));
        auto found = std::find_if(std::begin(slots), std::end(slots), [&](const Slot& slot) { return slot.urb == result && slot.pending; });
        if (found == std::end(slots)) throw std::runtime_error("Unexpected URB completion");
        found->pending = false; --pending;
        if (!validate) {
            ++stats.cleanupReaped;
            int good = 0, unlinked = 0, other = 0;
            for (int i = 0; i < result->number_of_packets; ++i) {
                const auto& packet = result->iso_frame_desc[i];
                if (packet.status == 0) { ++good; stats.cleanupBytes += packet.actual_length; }
                else if (static_cast<int>(packet.status) == -ENOENT || static_cast<int>(packet.status) == -ECONNRESET) ++unlinked;
                else ++other;
            }
            if (stats.cleanup.size() < 4) stats.cleanup.push_back(std::to_string(result->status) + ", " + std::to_string(good) + "/" + std::to_string(unlinked) + "/" + std::to_string(other));
            if (source.live() && good == 1 && result->iso_frame_desc[0].actual_length == result->iso_frame_desc[0].length) source.acknowledge(*found, result->buffer_length);
        } else {
            ++stats.completed;
            const auto now = io.nowUs();
            const auto gap = now - lastCompletion;
            stats.maxGapUs = std::max(stats.maxGapUs, gap);
            if (gap > static_cast<int64_t>(depth) * 1'000'000 / intervals / 2) { ++stats.schedulingGaps; stats.maxSchedulingGapUs = std::max(stats.maxSchedulingGapUs, gap); }
            lastCompletion = now;
            bool bad = result->status != 0 || result->error_count != 0;
            for (int i = 0; i < result->number_of_packets; ++i) {
                const auto& packet = result->iso_frame_desc[i]; ++stats.packets;
                if (packet.status != 0 || packet.actual_length != packet.length) {
                    ++stats.packetErrors; bad = true;
                    if (stats.problem.empty()) stats.problem = "Packet " + std::to_string(i) + " status=" + std::to_string(static_cast<int>(packet.status)) + " length=" + std::to_string(packet.actual_length) + "/" + std::to_string(packet.length);
                } else stats.bytes += packet.actual_length;
            }
            stats.recent.push_back({(now - start) / 1000, stats.bytes, pending});
            if (stats.recent.size() > 8) stats.recent.erase(stats.recent.begin());
            if (bad) throw std::runtime_error("ISO completion failed: URB status=" + std::to_string(result->status) + "; " + stats.problem);
            source.acknowledge(*found, result->buffer_length);
            if (!source.ended() && !io.stopped()) submit(*found);
        }
        return true;
    };
    auto guard = [&] {
        if (!source.live() && io.nowUs() - start >= 20'000'000) throw std::runtime_error("USB overall deadline exceeded: 20000 ms");
    };
    try {
        for (auto& slot : slots) { if (source.ended() || io.stopped()) break; if (!submit(slot)) break; }
        while (pending > 0 || !source.ended()) {
            if (io.stopped()) { stats.outcome = "stopped"; break; }
            guard();
            if (source.live()) {
                for (auto& slot : slots) if (!slot.pending && !source.ended() && !submit(slot)) break;
                if (pending == 0 && !source.ended()) throw std::runtime_error("PCM underrun: stopped instead of inserting silence");
                if (pending == 0) break;
            }
            // Consume every available completion before entering poll; refill immediately.
            if (reap(true)) continue;
            // A late worker can have completed URBs waiting in usbfs. Only an empty
            // completion queue can establish an idle timeout; inspect it before timing out.
            if (io.nowUs() - lastCompletion >= 2'000'000) throw std::runtime_error("USB idle timeout: no completed URB for 2000 ms");
            const auto before = io.nowUs(); const int events = io.wait(5);
            stats.maxWaitUs = std::max(stats.maxWaitUs, io.nowUs() - before);
            if (events == -EINTR) continue;
            if (events < 0) throw std::runtime_error("USB poll errno=" + std::to_string(-events));
            if (events & (POLLERR | POLLHUP | POLLNVAL)) throw std::runtime_error("USB poll terminal events=" + std::to_string(events));
            if (events == 0) ++stats.pollTimeout; else ++stats.pollWake;
        }
        if (io.stopped()) stats.outcome = "stopped";
    } catch (const std::exception& ex) { stats.outcome = "failed"; stats.problem = ex.what(); }
    stats.elapsedUs = io.nowUs() - start;
    stats.idleUs = io.nowUs() - lastCompletion;
    for (int i = depth - 1; i >= 0; --i) if (slots[i].pending) {
        const int result = io.control(USBDEVFS_DISCARDURB, slots[i].urb);
        if (result == 0) ++stats.discarded;
        else if (result == -EINVAL) ++stats.discardNotFound;
        else ++stats.discardFailed;
    }
    const auto drainStart = io.nowUs();
    while (pending > 0 && io.nowUs() - drainStart < 1'000'000) {
        try { if (!reap(false)) { const int events = io.wait(5); if (events < 0 && events != -EINTR) break; } }
        catch (...) { break; }
    }
    stats.pending = pending;
    return stats;
}
inline StreamResult runIso(Slot (&slots)[kDepth], std::vector<uint8_t>& pcm, int endpoint, int intervals, int maxPacket, UsbIo& io) {
    if (pcm.empty() || pcm.size() % kFrameBytes != 0 || pcm.size() > kRate * kFrameBytes * 15) throw std::runtime_error("Invalid PCM size");
    VectorPcmSource source(pcm);
    return runIsoSource(slots, source, endpoint, intervals, maxPacket, io);
}
}
