#include "usb_stream_core.h"
#include <jni.h>
#include <linux/usb/ch9.h>
#include <unistd.h>
#include <chrono>
#include <sys/utsname.h>
#include <time.h>
#include <cstdio>
#include <atomic>
#include <memory>
#include <thread>

namespace {
using namespace noir;
struct Session {
    int fd;
    std::vector<uint8_t> pcm;
    LivePcmQueue live;
    int rate = kRate, frameBytes = kFrameBytes;
    Slot slots[kDepth];
    std::unique_ptr<std::array<Slot, kLiveDepth>> liveSlots;
    std::atomic_bool liveStopped{false};
    explicit Session(int source): fd(dup(source)) { if (fd < 0) throw std::runtime_error("USB fd duplication failed"); }
    ~Session() {
        // Java closes its connection first. Close the last fd before member buffers die.
        for (auto& slot : slots) if (slot.pending) ioctl(fd, USBDEVFS_DISCARDURB, slot.urb);
        if (liveSlots) for (auto& slot : *liveSlots) if (slot.pending) ioctl(fd, USBDEVFS_DISCARDURB, slot.urb);
        close(fd);
    }
};
Session* session(jlong handle) { return reinterpret_cast<Session*>(handle); }
void failure(JNIEnv* env, const char* message) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message); }
int speed(Session* state) { return ioctl(state->fd, USBDEVFS_GET_SPEED); }
int64_t threadCpuUs() {
    timespec value{};
    return clock_gettime(CLOCK_THREAD_CPUTIME_ID, &value) == 0 ? static_cast<int64_t>(value.tv_sec) * 1'000'000 + value.tv_nsec / 1000 : -1;
}
std::string platformInfo(int fd) {
    std::ostringstream out;
    utsname kernel{};
    if (uname(&kernel) == 0) out << "Kernel: " << kernel.release << " / " << kernel.machine;
    char path[64]; std::snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    char target[4096]{};
    const auto count = readlink(path, target, sizeof(target) - 1);
    unsigned int bus = 0, device = 0;
    if (count > 0 && std::sscanf(target, "/dev/bus/usb/%u/%u", &bus, &device) == 2 && bus > 0 && bus < 1000) {
        char driverPath[128]; std::snprintf(driverPath, sizeof(driverPath), "/sys/bus/usb/devices/usb%u/../driver", bus);
        char driver[4096]{};
        const auto length = readlink(driverPath, driver, sizeof(driver) - 1);
        if (length > 0) {
            const char* name = std::strrchr(driver, '/');
            out << "\nHost controller driver: " << (name ? name + 1 : driver);
        } else out << "\nHost controller driver: unavailable (errno=" << errno << ")";
    } else out << "\nHost controller driver: unavailable (fd path not exposed)";
    return out.str();
}
class KernelIo final : public UsbIo {
    int fd_; JNIEnv* env_ = nullptr; jobject stop_ = nullptr; jmethodID get_ = nullptr;
    std::atomic_bool* nativeStop_ = nullptr;
public:
    KernelIo(int fd, JNIEnv* env, jobject stop): fd_(fd), env_(env), stop_(stop), get_(env->GetMethodID(env->GetObjectClass(stop), "get", "()Z")) {}
    KernelIo(int fd, std::atomic_bool& stop): fd_(fd), nativeStop_(&stop) {}
    int control(unsigned long request, void* argument) override {
        const int result = ioctl(fd_, request, argument); return result < 0 ? -errno : result;
    }
    int wait(int timeoutMs) override {
        pollfd descriptor{fd_, POLLOUT, 0};
        const int result = poll(&descriptor, 1, timeoutMs);
        return result < 0 ? -errno : (result == 0 ? 0 : descriptor.revents);
    }
    int64_t nowUs() override {
        return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
    }
    bool stopped() override {
        if (nativeStop_) return nativeStop_->load(std::memory_order_relaxed);
        return env_->CallBooleanMethod(stop_, get_) == JNI_TRUE || env_->ExceptionCheck();
    }
};

std::string stream(JNIEnv* env, Session* state, int endpoint, int interval, int maxPacket, jbyteArray input, jobject stop) {
    if (!state || endpoint != 4 || interval < 1 || interval > 4 || maxPacket < 24 || maxPacket > 1024) throw std::runtime_error("Unsupported USB stream parameters");
    if (!state->pcm.empty()) throw std::runtime_error("USB session cannot be reused");
    const int busSpeed = speed(state);
    if (busSpeed != USB_SPEED_HIGH && busSpeed != USB_SPEED_FULL) throw std::runtime_error("USB speed is unknown or unsupported");
    const int intervals = (busSpeed == USB_SPEED_HIGH ? 8000 : 1000) / (1 << (interval - 1));
    const auto size = env->GetArrayLength(input);
    if (size <= 0 || size > kRate * kFrameBytes * 15 || size % kFrameBytes != 0) throw std::runtime_error("Invalid PCM size");
    state->pcm.resize(static_cast<size_t>(size));
    env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(state->pcm.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("PCM copy failed");
    // usbfs normally prevents runtime suspend while open; record the explicit request
    // for vendor kernels. Closing this session releases its PM reference; do not ALLOW
    // suspend early on the shared Java file description.
    const int forbid = ioctl(state->fd, USBDEVFS_FORBID_SUSPEND);
    const int forbidError = forbid < 0 ? errno : 0;
    KernelIo io(state->fd, env, stop);
    const auto cpuBefore = threadCpuUs();
    const auto result = runIso(state->slots, state->pcm, endpoint, intervals, maxPacket, io);
    const auto cpuAfter = threadCpuUs();
    std::ostringstream report;
    report << result.report(intervals, state->pcm.size())
           << "\nNative thread CPU: " << (cpuBefore >= 0 && cpuAfter >= cpuBefore ? std::to_string((cpuAfter - cpuBefore) / 1000) + " ms" : "unavailable")
           << "\nUSB speed: " << busSpeed << (busSpeed == USB_SPEED_HIGH ? " (high-speed)" : " (full-speed)")
           << "\nUSB forbid suspend: " << (forbid == 0 ? "confirmed" : ("not confirmed, errno=" + std::to_string(forbidError)))
           << "\n" << platformInfo(state->fd);
    return report.str();
}
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_noir_player_NativeUsb_create(JNIEnv* env, jobject, jint fd) {
    try { return reinterpret_cast<jlong>(new Session(fd)); } catch (const std::exception& ex) { failure(env, ex.what()); return 0; }
}
extern "C" JNIEXPORT jint JNICALL Java_dev_noir_player_NativeUsb_speed(JNIEnv*, jobject, jlong handle) { return handle ? speed(session(handle)) : -1; }
extern "C" JNIEXPORT jstring JNICALL Java_dev_noir_player_NativeUsb_stream(JNIEnv* env, jobject, jlong handle, jint endpoint, jint interval, jint maxPacket, jbyteArray pcm, jobject stop) {
    try { const auto report = stream(env, session(handle), endpoint, interval, maxPacket, pcm, stop); return env->NewStringUTF(report.c_str()); }
    catch (const std::exception& ex) { failure(env, ex.what()); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_dev_noir_player_NativeUsb_destroy(JNIEnv*, jobject, jlong handle) { delete session(handle); }
extern "C" JNIEXPORT jint JNICALL Java_dev_noir_player_NativeUsb_feedLive(JNIEnv* env, jobject, jlong handle, jobject buffer, jint offset, jint size) {
    try {
        const auto capacity = env->GetDirectBufferCapacity(buffer);
        const auto* data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
        if (!handle || !data || offset < 0 || size < 0 || static_cast<int64_t>(offset) + size > capacity) throw std::runtime_error("Invalid direct PCM buffer");
        return session(handle)->live.push(data + offset, size);
    } catch (const std::exception& ex) { failure(env, ex.what()); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_dev_noir_player_NativeUsb_finishLive(JNIEnv*, jobject, jlong handle) { session(handle)->live.finish(); }
extern "C" JNIEXPORT void JNICALL Java_dev_noir_player_NativeUsb_resetLive(JNIEnv* env, jobject, jlong handle) {
    auto* state = session(handle);
    if (std::any_of(std::begin(state->slots), std::end(state->slots), [](const Slot& slot) { return slot.pending; })) { failure(env, "Transfers still pending during reset"); return; }
    if (state->liveSlots && std::any_of(state->liveSlots->begin(), state->liveSlots->end(), [](const Slot& slot) { return slot.pending; })) { failure(env, "Live transfers pending during reset"); return; }
    state->live.clear();
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_noir_player_NativeUsb_livePosition(JNIEnv*, jobject, jlong handle) { return static_cast<jlong>(session(handle)->live.position()); }
extern "C" JNIEXPORT jlong JNICALL Java_dev_noir_player_NativeUsb_liveBuffered(JNIEnv*, jobject, jlong handle) { return static_cast<jlong>(session(handle)->live.buffered()); }
extern "C" JNIEXPORT jstring JNICALL Java_dev_noir_player_NativeUsb_streamLive(JNIEnv* env, jobject, jlong handle, jint endpoint, jint interval, jint maxPacket) {
    try {
        if (!handle || endpoint != 4 || interval != 1 || speed(session(handle)) != USB_SPEED_HIGH || maxPacket < 24 || maxPacket > 1024) throw std::runtime_error("Unsupported live USB profile");
        auto* state = session(handle);
        if (!state->liveSlots) throw std::runtime_error("Live USB not configured");
        if (std::any_of(state->liveSlots->begin(), state->liveSlots->end(), [](const Slot& slot) { return slot.pending; })) throw std::runtime_error("Previous transfers still pending");
        const int forbid = ioctl(state->fd, USBDEVFS_FORBID_SUSPEND);
        const int forbidError = forbid < 0 ? errno : 0;
        state->live.rewind();
        KernelIo io(state->fd, state->liveStopped);
        const auto cpu = threadCpuUs();
        const auto result = runIsoSource(*state->liveSlots, state->live, endpoint, 8000, maxPacket, io, state->rate, state->frameBytes);
        std::string report = result.report(8000, static_cast<size_t>(state->live.written()), state->rate, state->frameBytes) + "\nLive PCM ring: " + std::to_string(state->rate * state->frameBytes * kLiveBufferSeconds) + " bytes / 8 seconds; no duration limit\nNative thread CPU: " + std::to_string((threadCpuUs() - cpu) / 1000) + " ms\n" + platformInfo(state->fd);
        report += "\nStop checks: native atomic; no Java callbacks during live USB";
        report += "\nUSB forbid suspend: " + (forbid == 0 ? std::string("confirmed") : "not confirmed, errno=" + std::to_string(forbidError));
        if (result.pending != 0) report += "\nLive error: pending transfers survived cleanup";
        return env->NewStringUTF(report.c_str());
    } catch (const std::exception& ex) { failure(env, ex.what()); return nullptr; }
}
extern "C" JNIEXPORT jintArray JNICALL Java_dev_noir_player_NativeUsb_packetPlan(JNIEnv* env, jobject, jint rate, jint intervals, jint count) {
    try {
        if (count < 1 || count > 120000) throw std::runtime_error("Invalid packet count");
        PacketPlan plan(rate, intervals);
        std::vector<jint> values(static_cast<size_t>(count)); for (auto& value : values) value = plan.next();
        auto result = env->NewIntArray(count); env->SetIntArrayRegion(result, 0, count, values.data()); return result;
    } catch (const std::exception& ex) { failure(env, ex.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL Java_dev_noir_player_NativeUsb_configureLive(JNIEnv* env, jobject, jlong handle, jint rate, jint frameBytes) {
    try {
        if (!handle) throw std::runtime_error("Missing USB session");
        const std::array<int, 8> rates{44100,48000,88200,96000,176400,192000,352800,384000};
        if (std::find(rates.begin(), rates.end(), rate) == rates.end()) throw std::runtime_error("Unsupported USB rate");
        auto* state = session(handle);
        if (std::any_of(std::begin(state->slots), std::end(state->slots), [](const Slot& slot) { return slot.pending; })) throw std::runtime_error("Transfers pending during configure");
        if (state->liveSlots && std::any_of(state->liveSlots->begin(), state->liveSlots->end(), [](const Slot& slot) { return slot.pending; })) throw std::runtime_error("Live transfers pending during configure");
        if (!state->liveSlots) state->liveSlots = std::make_unique<std::array<Slot, kLiveDepth>>();
        state->live.configure(rate, frameBytes); state->rate = rate; state->frameBytes = frameBytes;
    } catch (const std::exception& ex) { failure(env, ex.what()); }
}

extern "C" JNIEXPORT void JNICALL Java_dev_noir_player_NativeUsb_setLiveStopped(JNIEnv* env, jobject, jlong handle, jboolean stopped) {
    if (!handle) { failure(env, "Missing USB session"); return; }
    session(handle)->liveStopped.store(stopped == JNI_TRUE, std::memory_order_relaxed);
}
#ifndef NDEBUG
// Real Session atomic flag and wall time; no Java calls while waiting.
extern "C" JNIEXPORT jlong JNICALL Java_dev_noir_player_UsbTransportNativeTest_waitForNativeStop(JNIEnv*, jobject, jlong handle) {
    auto* state = session(handle);
    KernelIo io(state->fd, state->liveStopped);
    const auto start = io.nowUs();
    while (!io.stopped() && io.nowUs() - start < 5'000'000) std::this_thread::sleep_for(std::chrono::microseconds(200));
    return io.stopped() ? (io.nowUs() - start) / 1000 : -1;
}
#endif
