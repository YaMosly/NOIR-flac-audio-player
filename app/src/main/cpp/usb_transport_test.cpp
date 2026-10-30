// Compiled into Debug only. Deterministic fault injection into the production runIso loop.
#include "usb_stream_core.h"
#include <jni.h>
#include <deque>
#include <functional>
#include <memory>

namespace {
using namespace noir;
constexpr int kObservedStallPackets = 1784; // 0.4.2 physical trace; fault injection, not an HC model.
constexpr int kObservedStallCompletions = kObservedStallPackets / kPackets;
struct FakeIo final : UsbIo {
    struct Item { usbdevfs_urb* urb; int64_t due; bool cancelled = false; };
    int mode, submitted = 0, completed = 0;
    int frameBytes = 4, scheduleGaps = 0;
    int64_t largestScheduleGapUs = 0;
    int64_t clock = 0, lastDue = 0;
    bool injected = false;
    size_t accepted = 0;
    std::vector<uint8_t>& pcm;
    std::deque<Item> queue;
    bool copied = false;
    std::function<void()> produce;
    FakeIo(int scenario, std::vector<uint8_t>& bytes, bool live = false): mode(scenario), pcm(bytes), copied(live) {}
    int control(unsigned long request, void* argument) override {
        if (request == USBDEVFS_SUBMITURB) {
            if (mode == 7 && submitted == 40) return -ENOMEM;
            if (mode == 2) clock += 50'000;
            auto* urb = static_cast<usbdevfs_urb*>(argument);
            if (urb->number_of_packets != 1 || (urb->flags & USBDEVFS_URB_NO_INTERRUPT)) throw std::runtime_error("BEI workaround requires one packet and interrupts enabled");
            if (!copied && urb->buffer != pcm.data() + accepted) throw std::runtime_error("PCM skipped or repeated in submitted URBs");
            int total = 0;
            for (int i = 0; i < urb->number_of_packets; ++i) {
                if (urb->iso_frame_desc[i].length % frameBytes != 0) throw std::runtime_error("Partial PCM frame");
                total += static_cast<int>(urb->iso_frame_desc[i].length);
            }
            if (total != urb->buffer_length || accepted + total > pcm.size()) throw std::runtime_error("Payload boundary error");
            if (copied && std::memcmp(urb->buffer, pcm.data() + accepted, static_cast<size_t>(total))) throw std::runtime_error("Live PCM bytes skipped, repeated or changed");
            accepted += total; ++submitted;
            if (submitted > 1 && clock > lastDue) { ++scheduleGaps; largestScheduleGapUs = std::max(largestScheduleGapUs, clock - lastDue); }
            lastDue = std::max(clock, lastDue) + urb->number_of_packets * 125;
            queue.push_back({urb, lastDue});
            return 0;
        }
        if (request == USBDEVFS_DISCARDURB) {
            for (auto& item : queue) if (item.urb == argument) {
                if (clock >= item.due && !(mode == 1 && completed >= kObservedStallCompletions)) return -EINVAL;
                item.cancelled = true; return 0;
            }
            return -EINVAL;
        }
        if (request == USBDEVFS_REAPURBNDELAY) {
            if (queue.empty()) return -EAGAIN;
            auto item = queue.front();
            if (!item.cancelled && ((mode == 1 && completed >= kObservedStallCompletions) || clock < item.due)) return -EAGAIN;
            queue.pop_front();
            item.urb->status = item.cancelled ? -ENOENT : 0;
            for (int i = 0; i < item.urb->number_of_packets; ++i) {
                item.urb->iso_frame_desc[i].actual_length = item.cancelled ? 0 : item.urb->iso_frame_desc[i].length;
                item.urb->iso_frame_desc[i].status = item.cancelled ? static_cast<unsigned int>(-ENOENT) : 0;
            }
            if (!item.cancelled && mode == 4 && completed == 19) item.urb->iso_frame_desc[0].status = static_cast<unsigned int>(-EILSEQ);
            if (!item.cancelled) ++completed;
            *static_cast<usbdevfs_urb**>(argument) = item.urb;
            return 0;
        }
        return -EINVAL;
    }
    int wait(int timeoutMs) override {
        if (produce) produce();
        if (mode == 6 && !injected) { injected = true; clock += 1000; return -EINTR; }
        if (mode == 12 && !injected && completed >= kObservedStallCompletions) { injected = true; clock += 80'000; }
        else if ((mode == 8 || mode == 9) && !injected && completed >= kObservedStallCompletions) { injected = true; clock += mode == 8 ? 12'000 : 2'100'000; }
        else clock += timeoutMs * 1000;
        if (mode == 5 && clock >= 1'500'000) return POLLHUP;
        return !queue.empty() && (queue.front().cancelled || (clock >= queue.front().due && !(mode == 1 && completed >= kObservedStallCompletions))) ? POLLOUT : 0;
    }
    int64_t nowUs() override { return clock; }
    bool stopped() override { return mode == 10 || (mode == 3 && clock >= 1'500'000); }
};
void require(bool passed, const char* message) { if (!passed) throw std::runtime_error(message); }
}

extern "C" JNIEXPORT jstring JNICALL Java_dev_noir_player_UsbTransportNativeTest_liveScenario(JNIEnv* env, jobject, jint mode) {
    try {
        require(mode >= 0 && mode <= 3, "Invalid live scenario");
        std::vector<uint8_t> pcm(kRate * kFrameBytes * (mode == 2 ? 1 : 45));
        for (size_t i = 0; i < pcm.size(); ++i) pcm[i] = static_cast<uint8_t>((i * 13 + i / 4096) & 255);
        LivePcmQueue input(65536);
        size_t supplied = 0;
        auto producer = [&] {
            while (supplied < pcm.size()) {
                const auto amount = static_cast<int>(std::min(pcm.size() - supplied, static_cast<size_t>(8192)));
                const auto accepted = input.push(pcm.data() + supplied, amount);
                supplied += accepted;
                if (!accepted) break;
            }
            if (supplied == pcm.size()) input.finish();
            require(input.buffered() <= 65536, "Ring exceeded bounded capacity");
        };
        producer();
        if (mode == 3) {
            Slot slot;
            require(input.fill(slot, 24) == 24, "Ring read failed");
            input.acknowledge(slot, 24);
            input.clear();
            supplied = 0; producer();
            require(input.position() == 0, "Flush did not reset position");
        }
        Slot slots[kDepth]; FakeIo io(mode == 1 ? 3 : 0, pcm, true);
        if (mode != 2) io.produce = producer;
        auto result = runIsoSource(slots, input, 4, 8000, 196, io);
        require(result.pending == 0 && io.queue.empty(), "Live cleanup left pending requests");
        if (mode == 2) {
            require(result.outcome == "failed" && result.problem.find("PCM underrun") != std::string::npos, "Starvation silently inserted data");
        } else {
            if (mode == 1) {
                require(result.outcome == "stopped" && input.position() > 0 && input.position() < pcm.size(), "Live pause failed");
                input.rewind();
                FakeIo resumed(0, pcm, true); resumed.accepted = static_cast<size_t>(input.position()); resumed.produce = producer;
                result = runIsoSource(slots, input, 4, 8000, 196, resumed);
                require(resumed.accepted == pcm.size() && resumed.queue.empty(), "Resume skipped or repeated PCM");
            }
            require(result.outcome == "complete" && input.position() == pcm.size() && input.buffered() == 0, "Long stream failed");
        }
        return env->NewStringUTF(result.report(8000, pcm.size()).c_str());
    } catch (const std::exception& ex) { env->ThrowNew(env->FindClass("java/lang/AssertionError"), ex.what()); return nullptr; }
}

extern "C" JNIEXPORT jstring JNICALL Java_dev_noir_player_UsbTransportNativeTest_scenario(JNIEnv* env, jobject, jint mode) {
    try {
        require(mode >= 0 && mode <= 11, "Invalid test scenario");
        std::vector<uint8_t> pcm(kRate * kFrameBytes * 15 - (mode == 11 ? 12 : 0));
        for (size_t i = 0; i < pcm.size(); ++i) pcm[i] = static_cast<uint8_t>(i * 17 + 3);
        const auto before = pcm;
        Slot slots[kDepth]; FakeIo io(mode, pcm);
        const auto result = runIso(slots, pcm, 4, 8000, 196, io);
        require(pcm == before, "PCM altered by queue loop");
        require(result.pending == 0 && io.queue.empty(), "Outstanding transfers survived cleanup");
        for (const auto& slot : slots) require(!slot.pending, "Slot still pending after cleanup");
        switch (mode) {
            case 0: case 6: case 8: case 9: case 11:
                require(result.outcome == "complete" && result.bytes == pcm.size() && io.accepted == pcm.size(), "Complete stream lost data");
                require(result.completed == (120000 + kPackets - 1) / kPackets && result.packets == 120000, "Wrong transfer count");
                if (mode == 8) require(result.maxWaitUs >= 12000, "Jitter was not injected");
                if (mode == 9) require(result.maxGapUs >= 2100000, "Long worker pause was not injected");
                break;
            case 1:
                require(result.outcome == "failed" && result.packets == kObservedStallPackets && result.bytes == 39336 && result.problem.find("idle timeout") != std::string::npos, "Stall not distinguished");
                require(result.cleanupReaped == kDepth && result.discarded == kDepth && result.cleanupBytes == 0, "Stalled cleanup not accounted for"); break;
            case 2:
                require(result.outcome == "failed" && result.completed > 251 && result.problem.find("overall deadline") != std::string::npos && result.maxSubmitUs == 50000, "Slow progress not distinguished");
                require(result.discardNotFound > 0 && result.cleanupBytes > 0, "Already completed cleanup not counted"); break;
            case 3: require(result.outcome == "stopped", "Stop treated as failure"); break;
            case 4: require(result.outcome == "failed" && result.packetErrors == 1 && result.problem.find("status=-84") != std::string::npos, "Packet corruption not rejected"); break;
            case 5: require(result.outcome == "failed" && result.problem.find("terminal events") != std::string::npos, "Disconnect not distinguished"); break;
            case 7: require(result.outcome == "failed" && result.submitted == 40 && result.problem.find("SUBMITURB errno=12") != std::string::npos, "Submit failure not handled"); break;
            case 10: require(result.outcome == "stopped" && result.submitted == 0 && io.accepted == 0, "Stopped input submitted audio"); break;
        }
        const auto report = result.report(8000, pcm.size()); return env->NewStringUTF(report.c_str());
    } catch (const std::exception& ex) { env->ThrowNew(env->FindClass("java/lang/AssertionError"), ex.what()); return nullptr; }
}

extern "C" JNIEXPORT jstring JNICALL Java_dev_noir_player_UsbTransportNativeTest_hiResScenario(JNIEnv* env, jobject, jint rate, jint frameBytes) {
    try {
        std::vector<uint8_t> pcm(static_cast<size_t>(rate) * frameBytes * 3);
        for (size_t i = 0; i < pcm.size(); ++i) pcm[i] = static_cast<uint8_t>((i * 13 + i / 4096) & 255);
        LivePcmQueue input; input.configure(rate, frameBytes);
        size_t supplied = 0;
        auto producer = [&] {
            while (supplied < pcm.size()) {
                int amount = static_cast<int>(std::min(pcm.size() - supplied, static_cast<size_t>(frameBytes * 2048)));
                int count = input.push(pcm.data() + supplied, amount); supplied += count; if (!count) break;
            }
            if (supplied == pcm.size()) input.finish();
            require(input.buffered() <= static_cast<uint64_t>(rate) * frameBytes * kLiveBufferSeconds, "Hi-Res ring overflow");
        };
        producer();
        auto storage = std::make_unique<std::array<Slot, kLiveDepth>>();
        auto& slots = *storage; FakeIo io(3, pcm, true); io.frameBytes = frameBytes; io.produce = producer;
        int maxPacket = frameBytes / 2 * 98;
        auto result = runIsoSource(slots, input, 4, 8000, maxPacket, io, rate, frameBytes);
        require(result.outcome == "stopped" && result.pending == 0 && input.position() > 0, "Hi-Res pause failed");
        input.rewind();
        FakeIo resumed(0, pcm, true); resumed.frameBytes = frameBytes; resumed.produce = producer; resumed.accepted = input.position();
        result = runIsoSource(slots, input, 4, 8000, maxPacket, resumed, rate, frameBytes);
        require(result.outcome == "complete" && result.pending == 0 && resumed.queue.empty() && input.position() == pcm.size() && resumed.accepted == pcm.size(), "Hi-Res stream lost/repeated PCM");
        return env->NewStringUTF(result.report(8000, pcm.size(), rate, frameBytes).c_str());
    } catch (const std::exception& ex) { env->ThrowNew(env->FindClass("java/lang/AssertionError"), ex.what()); return nullptr; }
}

extern "C" JNIEXPORT jstring JNICALL Java_dev_noir_player_UsbTransportNativeTest_backgroundScenario(JNIEnv* env, jobject) {
    try {
        constexpr int rate = 192000, frame = 6;
        std::vector<uint8_t> pcm(static_cast<size_t>(rate) * frame * 12);
        for (size_t i = 0; i < pcm.size(); ++i) pcm[i] = static_cast<uint8_t>(i * 37 + i / 4096);
        auto exercise = [&](auto& slots, bool producerDelay) {
            LivePcmQueue input; input.configure(rate, frame);
            size_t supplied = 0;
            FakeIo io(12, pcm, true); io.frameBytes = frame;
            auto produce = [&] {
                if (producerDelay && io.clock >= 1'000'000 && io.clock < 4'200'000) return;
                while (supplied < pcm.size()) {
                    const int amount = static_cast<int>(std::min(pcm.size() - supplied, static_cast<size_t>(frame * 4096)));
                    const int count = input.push(pcm.data() + supplied, amount); supplied += count; if (!count) break;
                }
                if (supplied == pcm.size()) input.finish();
            };
            produce(); io.produce = produce;
            const auto result = runIsoSource(slots, input, 4, 8000, 294, io, rate, frame);
            require(result.outcome == "complete" && result.bytes == pcm.size() && io.accepted == pcm.size() && result.pending == 0, "Background model lost PCM");
            require(io.injected && result.maxWaitUs >= 80000, "Worker delay was not injected");
            if (producerDelay) require(io.scheduleGaps == 0, "Deep queue ran dry during 80ms scheduling gap");
            else require(io.scheduleGaps > 0, "Regression control did not expose shallow queue starvation");
            return result.report(8000, pcm.size(), rate, frame);
        };
        auto shallow = std::make_unique<std::array<Slot, kDepth>>();
        exercise(*shallow, false);
        auto deep = std::make_unique<std::array<Slot, kLiveDepth>>();
        const auto report = exercise(*deep, true);
        return env->NewStringUTF((report + "\n80ms USB scheduling pause and 3200ms decoder pause: byte-exact; no modeled USB schedule gaps").c_str());
    } catch (const std::exception& ex) { env->ThrowNew(env->FindClass("java/lang/AssertionError"), ex.what()); return nullptr; }
}
