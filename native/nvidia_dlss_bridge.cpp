#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <jni.h>
#include <vulkan/vulkan.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <exception>
#include <mutex>
#include <string>
#include <unordered_set>
#include <utility>
#include <vector>
#include "sl.h"
#include "sl_dlss.h"
#include "sl_nis.h"
#include "sl_helpers_vk.h"

namespace {
static_assert(SL_VERSION_MAJOR == 2, "Streamline major version must be 2");
static_assert(SL_VERSION_MINOR == 12, "Streamline minor version must be 12");
static_assert(SL_VERSION_PATCH == 0, "Streamline patch version must be 0");
static_assert(sl::kFeatureDLSS == 0, "Unexpected Streamline DLSS feature id");
static_assert(sl::kFeatureNIS == 2, "Unexpected Streamline NIS feature id");

HMODULE gModule{};
bool gInitialized{};
bool gCleanupUncertain{};
std::mutex gMessageMutex;
std::string gOperationMessage{"Nicht initialisiert"};
std::string gAsyncDiagnostic{};
std::mutex gViewportMutex;
std::unordered_set<uint32_t> gActiveDlssViewports;
std::unordered_set<uint32_t> gActiveNisViewports;
std::vector<uint8_t> gRequirementsSnapshot;

constexpr jint kCleanupUnconfirmed = INT32_MIN + 0xBF;
constexpr jint kNisNotRequested = INT32_MIN;
constexpr jint kRequirementsMalformed = -1004;
constexpr uint32_t kRequirementsTransportMagic = 0x42465352;
constexpr uint16_t kRequirementsTransportVersion = 1;
constexpr uint32_t kMaxRequirementEntries = 1024;
constexpr size_t kMaxRequirementStringBytes = 4096;
constexpr size_t kMaxRequirementsSnapshotBytes = 1024 * 1024;

PFun_slInit* gInit{};
PFun_slShutdown* gShutdown{};
PFun_slGetFeatureRequirements* gGetRequirements{};
PFun_slSetVulkanInfo* gSetVulkanInfo{};
PFun_slIsFeatureSupported* gIsFeatureSupported{};
PFun_slGetFeatureFunction* gGetFeatureFunction{};
PFun_slGetNewFrameToken* gGetNewFrameToken{};
PFun_slSetTagForFrame* gSetTagForFrame{};
PFun_slSetConstants* gSetConstants{};
PFun_slEvaluateFeature* gEvaluateFeature{};
PFun_slFreeResources* gFreeResources{};
PFun_slDLSSGetOptimalSettings* gGetOptimalSettings{};
PFun_slDLSSSetOptions* gSetOptions{};
PFun_slNISSetOptions* gNisSetOptions{};
PFN_vkGetDeviceProcAddr gGetDeviceProcAddrProxy{};
PFN_vkQueuePresentKHR gQueuePresentProxy{};

template<typename T> T* resolve(const char* name) {
    return reinterpret_cast<T*>(GetProcAddress(gModule, name));
}

enum class TrackedFeature {
    eDlss,
    eNis
};

void setOperationMessage(const std::string& value) {
    const std::lock_guard<std::mutex> lock(gMessageMutex);
    gOperationMessage = value;
}

std::string operationMessageSnapshot() {
    const std::lock_guard<std::mutex> lock(gMessageMutex);
    return gOperationMessage;
}

void setAsyncDiagnostic(const std::string& value) {
    const std::lock_guard<std::mutex> lock(gMessageMutex);
    gAsyncDiagnostic = value;
}

std::string asyncDiagnosticSnapshot() {
    const std::lock_guard<std::mutex> lock(gMessageMutex);
    return gAsyncDiagnostic;
}

std::unordered_set<uint32_t>& activeViewports(TrackedFeature feature) {
    return feature == TrackedFeature::eDlss
        ? gActiveDlssViewports
        : gActiveNisViewports;
}

bool isViewportActive(TrackedFeature feature, uint32_t viewportId) {
    const std::lock_guard<std::mutex> lock(gViewportMutex);
    const auto& viewports = activeViewports(feature);
    return viewports.find(viewportId) != viewports.end();
}

void recordEvaluateResult(
    TrackedFeature feature,
    uint32_t viewportId,
    bool succeeded
) {
    if (!succeeded) return;
    const std::lock_guard<std::mutex> lock(gViewportMutex);
    activeViewports(feature).insert(viewportId);
}

void recordFreeResult(
    TrackedFeature feature,
    uint32_t viewportId,
    bool succeeded
) {
    if (!succeeded) return;
    const std::lock_guard<std::mutex> lock(gViewportMutex);
    activeViewports(feature).erase(viewportId);
}

void clearActiveViewports() {
    const std::lock_guard<std::mutex> lock(gViewportMutex);
    gActiveDlssViewports.clear();
    gActiveNisViewports.clear();
}

std::vector<std::pair<TrackedFeature, uint32_t>>
activeFeatureViewportsSnapshot() {
    const std::lock_guard<std::mutex> lock(gViewportMutex);
    std::vector<std::pair<TrackedFeature, uint32_t>> snapshot;
    snapshot.reserve(
        gActiveDlssViewports.size() + gActiveNisViewports.size()
    );
    for (uint32_t viewportId : gActiveDlssViewports) {
        snapshot.emplace_back(TrackedFeature::eDlss, viewportId);
    }
    for (uint32_t viewportId : gActiveNisViewports) {
        snapshot.emplace_back(TrackedFeature::eNis, viewportId);
    }
    return snapshot;
}

void clearDeviceFunctions() {
    gGetOptimalSettings = nullptr;
    gSetOptions = nullptr;
    gNisSetOptions = nullptr;
    gQueuePresentProxy = nullptr;
}

void clearResolvedFunctions() {
    gRequirementsSnapshot.clear();
    gInit = nullptr;
    gShutdown = nullptr;
    gGetRequirements = nullptr;
    gSetVulkanInfo = nullptr;
    gIsFeatureSupported = nullptr;
    gGetFeatureFunction = nullptr;
    gGetNewFrameToken = nullptr;
    gSetTagForFrame = nullptr;
    gSetConstants = nullptr;
    gEvaluateFeature = nullptr;
    gFreeResources = nullptr;
    gGetDeviceProcAddrProxy = nullptr;
    clearDeviceFunctions();
}

void markCleanupUncertain() {
    gCleanupUncertain = true;
    gInitialized = false;
    clearResolvedFunctions();
}

bool releaseModuleAndReset(DWORD& releaseError) {
    HMODULE module = gModule;
    gInitialized = false;
    clearResolvedFunctions();
    if (!module) {
        gCleanupUncertain = false;
        clearActiveViewports();
        return true;
    }
    if (!FreeLibrary(module)) {
        releaseError = GetLastError();
        gCleanupUncertain = true;
        return false;
    }
    gModule = nullptr;
    gCleanupUncertain = false;
    clearActiveViewports();
    return true;
}

jint rollbackFailedBootstrap(jint result) {
    const std::string failureMessage = operationMessageSnapshot();
    DWORD releaseError{};
    if (!releaseModuleAndReset(releaseError)) {
        setOperationMessage(
            failureMessage
                + "; sl.interposer.dll konnte nach dem Bootstrap-Fehler nicht freigegeben werden (Windows-Code "
                + std::to_string(static_cast<unsigned long>(releaseError))
                + ")"
        );
        return kCleanupUnconfirmed;
    }
    setOperationMessage(failureMessage);
    return result;
}

void logCallback(sl::LogType type, const char* message) {
    if (type == sl::LogType::eError || type == sl::LogType::eWarn) {
        setAsyncDiagnostic(
            message ? message : "Unbekannter Streamline-Fehler"
        );
    }
}

std::wstring fromJava(JNIEnv* env, jstring value) {
    if (!value) return {};
    const jchar* chars = env->GetStringChars(value, nullptr);
    const jsize len = env->GetStringLength(value);
    std::wstring result(reinterpret_cast<const wchar_t*>(chars), static_cast<size_t>(len));
    env->ReleaseStringChars(value, chars);
    return result;
}

sl::DLSSMode toMode(jint mode) {
    switch (mode) {
        case 1: return sl::DLSSMode::eMaxQuality;
        case 2: return sl::DLSSMode::eBalanced;
        case 3: return sl::DLSSMode::eMaxPerformance;
        case 4: return sl::DLSSMode::eDLAA;
        case 5: return sl::DLSSMode::eUltraPerformance;
        default: return sl::DLSSMode::eOff;
    }
}

void configureOptions(sl::DLSSOptions& options, jint mode, uint32_t width, uint32_t height) {
    options.mode = toMode(mode);
    options.outputWidth = width;
    options.outputHeight = height;
    options.colorBuffersHDR = sl::Boolean::eFalse;
    options.useAutoExposure = sl::Boolean::eTrue;
    options.alphaUpscalingEnabled = sl::Boolean::eFalse;
    options.dlaaPreset = sl::DLSSPreset::ePresetK;
    options.qualityPreset = sl::DLSSPreset::ePresetK;
    options.balancedPreset = sl::DLSSPreset::ePresetK;
    options.performancePreset = sl::DLSSPreset::ePresetM;
    options.ultraPerformancePreset = sl::DLSSPreset::ePresetL;
}

void copyMatrix(JNIEnv* env, jfloatArray source, sl::float4x4& target) {
    std::memset(&target, 0, sizeof(target));
    if (!source || env->GetArrayLength(source) < 16) return;
    env->GetFloatArrayRegion(source, 0, 16, reinterpret_cast<jfloat*>(&target));
}

sl::Resource makeImage(jlong image, jlong view, uint32_t width, uint32_t height, uint32_t format, uint32_t usage) {
    sl::Resource resource(sl::ResourceType::eTex2d,
        reinterpret_cast<void*>(static_cast<uintptr_t>(image)), nullptr,
        reinterpret_cast<void*>(static_cast<uintptr_t>(view)), VK_IMAGE_LAYOUT_GENERAL);
    resource.width = width;
    resource.height = height;
    resource.nativeFormat = format;
    resource.mipLevels = 1;
    resource.arrayLayers = 1;
    resource.flags = 0;
    resource.usage = usage;
    return resource;
}

bool ok(sl::Result result, const char* operation) {
    if (result == sl::Result::eOk) return true;
    setOperationMessage(std::string(operation) + " fehlgeschlagen (SL-Code " + std::to_string(static_cast<int>(result)) + ")");
    return false;
}

jlong packEvaluationResults(jint dlssResult, jint nisResult) {
    static_assert(
        sizeof(jlong) == sizeof(uint64_t),
        "JNI long must hold two 32-bit Streamline results"
    );
    const uint64_t bits =
        (static_cast<uint64_t>(static_cast<uint32_t>(dlssResult)) << 32)
        | static_cast<uint32_t>(nisResult);
    jlong packed{};
    std::memcpy(&packed, &bits, sizeof(packed));
    return packed;
}

struct FeatureRequirementsCopy {
    sl::Feature feature{};
    uint32_t flags{};
    uint32_t maxNumCPUThreads{};
    uint32_t maxNumViewports{};
    std::vector<uint32_t> requiredTags;
    sl::Version osVersionDetected{};
    sl::Version osVersionRequired{};
    sl::Version driverVersionDetected{};
    sl::Version driverVersionRequired{};
    uint32_t vkNumComputeQueuesRequired{};
    uint32_t vkNumGraphicsQueuesRequired{};
    uint32_t vkNumOpticalFlowQueuesRequired{};
    std::vector<std::string> vkInstanceExtensions;
    std::vector<std::string> vkDeviceExtensions;
    std::vector<std::string> vkFeatures12;
    std::vector<std::string> vkFeatures13;
};

bool copyRequirementStrings(
    uint32_t count,
    const char** values,
    const char* featureName,
    const char* fieldName,
    std::vector<std::string>& destination
) {
    if (count > kMaxRequirementEntries) {
        setOperationMessage(
            std::string(featureName)
                + " meldet zu viele "
                + fieldName
                + " ("
                + std::to_string(count)
                + ")"
        );
        return false;
    }
    if (count != 0 && !values) {
        setOperationMessage(
            std::string(featureName)
                + " meldet "
                + fieldName
                + " ohne Array"
        );
        return false;
    }

    destination.clear();
    destination.reserve(count);
    for (uint32_t index = 0; index < count; ++index) {
        const char* value = values[index];
        if (!value) {
            setOperationMessage(
                std::string(featureName)
                    + " meldet einen Null-Eintrag in "
                    + fieldName
            );
            return false;
        }
        size_t length = 0;
        while (
            length < kMaxRequirementStringBytes
                && value[length] != '\0'
        ) {
            ++length;
        }
        if (length == 0 || length == kMaxRequirementStringBytes) {
            setOperationMessage(
                std::string(featureName)
                    + " meldet einen ungueltigen Eintrag in "
                    + fieldName
            );
            return false;
        }
        destination.emplace_back(value, length);
    }
    std::sort(destination.begin(), destination.end());
    return true;
}

bool copyRequiredTags(
    const sl::FeatureRequirements& requirements,
    const char* featureName,
    std::vector<uint32_t>& destination
) {
    if (requirements.numRequiredTags > kMaxRequirementEntries) {
        setOperationMessage(
            std::string(featureName)
                + " meldet zu viele erforderliche Resource-Tags ("
                + std::to_string(requirements.numRequiredTags)
                + ")"
        );
        return false;
    }
    if (
        requirements.numRequiredTags != 0
            && !requirements.requiredTags
    ) {
        setOperationMessage(
            std::string(featureName)
                + " meldet Resource-Tags ohne Array"
        );
        return false;
    }
    destination.clear();
    destination.reserve(requirements.numRequiredTags);
    for (
        uint32_t index = 0;
        index < requirements.numRequiredTags;
        ++index
    ) {
        destination.push_back(
            static_cast<uint32_t>(requirements.requiredTags[index])
        );
    }
    std::sort(destination.begin(), destination.end());
    return true;
}

bool copyFeatureRequirements(
    sl::Feature feature,
    const char* featureName,
    FeatureRequirementsCopy& destination,
    jint& failureCode
) {
    sl::FeatureRequirements requirements{};
    const sl::Result result = gGetRequirements(feature, requirements);
    if (result != sl::Result::eOk) {
        failureCode = static_cast<jint>(result);
        setOperationMessage(
            std::string("slGetFeatureRequirements(")
                + featureName
                + ") fehlgeschlagen (SL-Code "
                + std::to_string(static_cast<int>(result))
                + ")"
        );
        return false;
    }

    destination.feature = feature;
    destination.flags = static_cast<uint32_t>(requirements.flags);
    destination.maxNumCPUThreads = requirements.maxNumCPUThreads;
    destination.maxNumViewports = requirements.maxNumViewports;
    destination.osVersionDetected = requirements.osVersionDetected;
    destination.osVersionRequired = requirements.osVersionRequired;
    destination.driverVersionDetected = requirements.driverVersionDetected;
    destination.driverVersionRequired = requirements.driverVersionRequired;
    destination.vkNumComputeQueuesRequired =
        requirements.vkNumComputeQueuesRequired;
    destination.vkNumGraphicsQueuesRequired =
        requirements.vkNumGraphicsQueuesRequired;
    destination.vkNumOpticalFlowQueuesRequired =
        requirements.vkNumOpticalFlowQueuesRequired;

    return copyRequiredTags(
            requirements,
            featureName,
            destination.requiredTags
        )
        && copyRequirementStrings(
            requirements.vkNumInstanceExtensions,
            requirements.vkInstanceExtensions,
            featureName,
            "Vulkan-Instance-Erweiterungen",
            destination.vkInstanceExtensions
        )
        && copyRequirementStrings(
            requirements.vkNumDeviceExtensions,
            requirements.vkDeviceExtensions,
            featureName,
            "Vulkan-Device-Erweiterungen",
            destination.vkDeviceExtensions
        )
        && copyRequirementStrings(
            requirements.vkNumFeatures12,
            requirements.vkFeatures12,
            featureName,
            "Vulkan-1.2-Features",
            destination.vkFeatures12
        )
        && copyRequirementStrings(
            requirements.vkNumFeatures13,
            requirements.vkFeatures13,
            featureName,
            "Vulkan-1.3-Features",
            destination.vkFeatures13
        );
}

bool appendRequirementByte(
    std::vector<uint8_t>& destination,
    uint8_t value
) {
    if (destination.size() >= kMaxRequirementsSnapshotBytes) {
        return false;
    }
    destination.push_back(value);
    return true;
}

bool appendRequirementU16(
    std::vector<uint8_t>& destination,
    uint16_t value
) {
    return appendRequirementByte(
            destination,
            static_cast<uint8_t>((value >> 8) & 0xff)
        )
        && appendRequirementByte(
            destination,
            static_cast<uint8_t>(value & 0xff)
        );
}

bool appendRequirementU32(
    std::vector<uint8_t>& destination,
    uint32_t value
) {
    return appendRequirementByte(
            destination,
            static_cast<uint8_t>((value >> 24) & 0xff)
        )
        && appendRequirementByte(
            destination,
            static_cast<uint8_t>((value >> 16) & 0xff)
        )
        && appendRequirementByte(
            destination,
            static_cast<uint8_t>((value >> 8) & 0xff)
        )
        && appendRequirementByte(
            destination,
            static_cast<uint8_t>(value & 0xff)
        );
}

bool appendRequirementVersion(
    std::vector<uint8_t>& destination,
    const sl::Version& version
) {
    return appendRequirementU32(destination, version.major)
        && appendRequirementU32(destination, version.minor)
        && appendRequirementU32(destination, version.build);
}

bool appendRequirementTags(
    std::vector<uint8_t>& destination,
    const std::vector<uint32_t>& values
) {
    if (!appendRequirementU32(
        destination,
        static_cast<uint32_t>(values.size())
    )) {
        return false;
    }
    for (uint32_t value : values) {
        if (!appendRequirementU32(destination, value)) {
            return false;
        }
    }
    return true;
}

bool appendRequirementStrings(
    std::vector<uint8_t>& destination,
    const std::vector<std::string>& values
) {
    if (!appendRequirementU32(
        destination,
        static_cast<uint32_t>(values.size())
    )) {
        return false;
    }
    for (const std::string& value : values) {
        if (
            value.size() > UINT32_MAX
                || !appendRequirementU32(
                    destination,
                    static_cast<uint32_t>(value.size())
                )
        ) {
            return false;
        }
        for (unsigned char byte : value) {
            if (!appendRequirementByte(destination, byte)) {
                return false;
            }
        }
    }
    return true;
}

bool appendFeatureRequirements(
    std::vector<uint8_t>& destination,
    const FeatureRequirementsCopy& requirements
) {
    return appendRequirementU32(
            destination,
            static_cast<uint32_t>(requirements.feature)
        )
        && appendRequirementU32(destination, requirements.flags)
        && appendRequirementU32(
            destination,
            requirements.maxNumCPUThreads
        )
        && appendRequirementU32(
            destination,
            requirements.maxNumViewports
        )
        && appendRequirementVersion(
            destination,
            requirements.osVersionDetected
        )
        && appendRequirementVersion(
            destination,
            requirements.osVersionRequired
        )
        && appendRequirementVersion(
            destination,
            requirements.driverVersionDetected
        )
        && appendRequirementVersion(
            destination,
            requirements.driverVersionRequired
        )
        && appendRequirementU32(
            destination,
            requirements.vkNumComputeQueuesRequired
        )
        && appendRequirementU32(
            destination,
            requirements.vkNumGraphicsQueuesRequired
        )
        && appendRequirementU32(
            destination,
            requirements.vkNumOpticalFlowQueuesRequired
        )
        && appendRequirementTags(destination, requirements.requiredTags)
        && appendRequirementStrings(
            destination,
            requirements.vkInstanceExtensions
        )
        && appendRequirementStrings(
            destination,
            requirements.vkDeviceExtensions
        )
        && appendRequirementStrings(
            destination,
            requirements.vkFeatures12
        )
        && appendRequirementStrings(
            destination,
            requirements.vkFeatures13
        );
}

bool queryPinnedRequirements(
    std::vector<uint8_t>& destination,
    jint& failureCode
) {
    try {
        FeatureRequirementsCopy dlss{};
        FeatureRequirementsCopy nis{};
        if (
            !copyFeatureRequirements(
                sl::kFeatureDLSS,
                "DLSS",
                dlss,
                failureCode
            )
            || !copyFeatureRequirements(
                sl::kFeatureNIS,
                "NIS",
                nis,
                failureCode
            )
        ) {
            return false;
        }

        std::vector<uint8_t> encoded;
        encoded.reserve(1024);
        if (
            !appendRequirementU32(
                encoded,
                kRequirementsTransportMagic
            )
            || !appendRequirementU16(
                encoded,
                kRequirementsTransportVersion
            )
            || !appendRequirementU16(encoded, SL_VERSION_MAJOR)
            || !appendRequirementU16(encoded, SL_VERSION_MINOR)
            || !appendRequirementU16(encoded, SL_VERSION_PATCH)
            || !appendRequirementU16(encoded, 2)
            || !appendFeatureRequirements(encoded, dlss)
            || !appendFeatureRequirements(encoded, nis)
        ) {
            failureCode = kRequirementsMalformed;
            setOperationMessage(
                "Streamline-Anforderungen ueberschreiten das begrenzte JNI-Transportformat"
            );
            return false;
        }
        destination = std::move(encoded);
        return true;
    } catch (const std::exception& error) {
        failureCode = kRequirementsMalformed;
        setOperationMessage(
            std::string("Streamline-Anforderungen konnten nicht kopiert werden: ")
                + error.what()
        );
        return false;
    } catch (...) {
        failureCode = kRequirementsMalformed;
        setOperationMessage(
            "Streamline-Anforderungen konnten nicht kopiert werden"
        );
        return false;
    }
}

jint rollbackInitializedBootstrap(jint result) {
    const std::string failureMessage = operationMessageSnapshot();
    if (!gShutdown) {
        markCleanupUncertain();
        setOperationMessage(
            failureMessage
                + "; Streamline-Shutdown nach erfolgreichem slInit ist nicht aufloesbar"
        );
        return kCleanupUnconfirmed;
    }
    const sl::Result shutdownResult = gShutdown();
    if (shutdownResult != sl::Result::eOk) {
        markCleanupUncertain();
        setOperationMessage(
            failureMessage
                + "; Streamline-Shutdown nach Anforderungsfehler fehlgeschlagen (SL-Code "
                + std::to_string(static_cast<int>(shutdownResult))
                + ")"
        );
        return kCleanupUnconfirmed;
    }

    DWORD releaseError{};
    if (!releaseModuleAndReset(releaseError)) {
        setOperationMessage(
            failureMessage
                + "; sl.interposer.dll konnte nach Anforderungsfehler nicht freigegeben werden (Windows-Code "
                + std::to_string(static_cast<unsigned long>(releaseError))
                + ")"
        );
        return kCleanupUnconfirmed;
    }
    setOperationMessage(failureMessage);
    return result;
}
}

extern "C" JNIEXPORT jint JNICALL Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_bootstrap
  (JNIEnv* env, jclass, jstring interposerPath, jstring pluginPath, jstring logPath) {
    if (gInitialized) return 0;
    if (gCleanupUncertain) {
        setOperationMessage("Streamline-Bootstrap gesperrt: vorherige native Bereinigung ist unbestätigt");
        return kCleanupUnconfirmed;
    }
    setAsyncDiagnostic("");
    const std::wstring interposer = fromJava(env, interposerPath);
    const std::wstring plugins = fromJava(env, pluginPath);
    const std::wstring logs = fromJava(env, logPath);
    clearResolvedFunctions();
    if (!gModule) {
        gModule = LoadLibraryW(interposer.c_str());
        if (!gModule) {
            const int error = static_cast<int>(GetLastError());
            setOperationMessage("sl.interposer.dll konnte nicht geladen werden (Windows-Code " + std::to_string(error) + ")");
            return error == 0 ? -1000 : -error;
        }
    }

    gInit = resolve<PFun_slInit>("slInit");
    gShutdown = resolve<PFun_slShutdown>("slShutdown");
    gGetRequirements = resolve<PFun_slGetFeatureRequirements>("slGetFeatureRequirements");
    gSetVulkanInfo = resolve<PFun_slSetVulkanInfo>("slSetVulkanInfo");
    gIsFeatureSupported = resolve<PFun_slIsFeatureSupported>("slIsFeatureSupported");
    gGetFeatureFunction = resolve<PFun_slGetFeatureFunction>("slGetFeatureFunction");
    gGetNewFrameToken = resolve<PFun_slGetNewFrameToken>("slGetNewFrameToken");
    gSetTagForFrame = resolve<PFun_slSetTagForFrame>("slSetTagForFrame");
    gSetConstants = resolve<PFun_slSetConstants>("slSetConstants");
    gEvaluateFeature = resolve<PFun_slEvaluateFeature>("slEvaluateFeature");
    gFreeResources = resolve<PFun_slFreeResources>("slFreeResources");
    gGetDeviceProcAddrProxy = reinterpret_cast<PFN_vkGetDeviceProcAddr>(GetProcAddress(gModule, "vkGetDeviceProcAddr"));
    if (!gInit || !gShutdown || !gGetRequirements || !gSetVulkanInfo || !gIsFeatureSupported ||
        !gGetFeatureFunction || !gGetNewFrameToken || !gSetTagForFrame || !gSetConstants || !gEvaluateFeature ||
        !gFreeResources || !gGetDeviceProcAddrProxy) {
        setOperationMessage("Die Streamline-Exports sind unvollständig");
        return rollbackFailedBootstrap(-1001);
    }

    const wchar_t* paths[] = { plugins.c_str() };
    const sl::Feature features[] = { sl::kFeatureDLSS, sl::kFeatureNIS };
    sl::Preferences preferences{};
    preferences.pathsToPlugins = paths;
    preferences.numPathsToPlugins = 1;
    preferences.pathToLogsAndData = logs.empty() ? nullptr : logs.c_str();
    preferences.logMessageCallback = logCallback;
    preferences.logLevel = sl::LogLevel::eDefault;
    preferences.flags = sl::PreferenceFlags::eUseManualHooking |
        sl::PreferenceFlags::eUseFrameBasedResourceTagging |
        sl::PreferenceFlags::eDisableCLStateTracking |
        sl::PreferenceFlags::eDisableDebugText;
    preferences.featuresToLoad = features;
    preferences.numFeaturesToLoad = 2;
    preferences.engine = sl::EngineType::eCustom;
    preferences.engineVersion = "Minecraft-26.2-NeoForge";
    preferences.projectId = "af5a51cf-e16a-43bf-85b0-d0508cf6dba5";
    preferences.renderAPI = sl::RenderAPI::eVulkan;

    const sl::Result result = gInit(preferences, sl::kSDKVersion);
    if (!ok(result, "Streamline-Initialisierung")) {
        // slShutdown is only valid after a successful slInit. The failed
        // attempt owns only the LoadLibrary reference and releases it here.
        return rollbackFailedBootstrap(static_cast<jint>(result));
    }

    std::vector<uint8_t> requirementsSnapshot;
    jint requirementsResult = kRequirementsMalformed;
    if (!queryPinnedRequirements(requirementsSnapshot, requirementsResult)) {
        return rollbackInitializedBootstrap(requirementsResult);
    }
    gRequirementsSnapshot = std::move(requirementsSnapshot);
    gInitialized = true;
    setOperationMessage(
        "NVIDIA Streamline 2.12.0 initialisiert; DLSS- und NIS-Anforderungen kopiert"
    );
    return 0;
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_featureRequirements
  (JNIEnv* env, jclass) {
    if (!gInitialized || gRequirementsSnapshot.empty()) {
        setOperationMessage(
            "Streamline-Anforderungen sind nicht initialisiert"
        );
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(
        static_cast<jsize>(gRequirementsSnapshot.size())
    );
    if (!result) {
        return nullptr;
    }
    env->SetByteArrayRegion(
        result,
        0,
        static_cast<jsize>(gRequirementsSnapshot.size()),
        reinterpret_cast<const jbyte*>(gRequirementsSnapshot.data())
    );
    return result;
}

extern "C" JNIEXPORT jint JNICALL Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_setVulkanInfo
  (JNIEnv*, jclass, jlong instance, jlong physicalDevice, jlong device, jint graphicsFamily, jint graphicsIndex, jint computeFamily, jint computeIndex) {
    if (!gInitialized) return -1100;
    clearDeviceFunctions();
    sl::VulkanInfo info{};
    info.instance = reinterpret_cast<VkInstance>(static_cast<uintptr_t>(instance));
    info.physicalDevice = reinterpret_cast<VkPhysicalDevice>(static_cast<uintptr_t>(physicalDevice));
    info.device = reinterpret_cast<VkDevice>(static_cast<uintptr_t>(device));
    info.graphicsQueueFamily = static_cast<uint32_t>(graphicsFamily);
    info.graphicsQueueIndex = static_cast<uint32_t>(graphicsIndex);
    info.computeQueueFamily = static_cast<uint32_t>(computeFamily);
    info.computeQueueIndex = static_cast<uint32_t>(computeIndex);
    const sl::Result setResult = gSetVulkanInfo(info);
    if (!ok(setResult, "Vulkan-Übergabe")) return static_cast<jint>(setResult);
    gQueuePresentProxy = reinterpret_cast<PFN_vkQueuePresentKHR>(
        gGetDeviceProcAddrProxy(info.device, "vkQueuePresentKHR"));
    if (!gQueuePresentProxy) {
        setOperationMessage("Streamlines Vulkan-Present-Proxy ist nicht verfügbar");
        return -1101;
    }
    sl::AdapterInfo adapter{};
    adapter.vkPhysicalDevice = info.physicalDevice;
    const sl::Result support = gIsFeatureSupported(sl::kFeatureDLSS, adapter);
    if (!ok(support, "DLSS-Hardwareprüfung")) return static_cast<jint>(support);
    void* fn{};
    if (!ok(gGetFeatureFunction(sl::kFeatureDLSS, "slDLSSGetOptimalSettings", fn), "DLSS-Einstellungsfunktion")) return -1002;
    gGetOptimalSettings = reinterpret_cast<PFun_slDLSSGetOptimalSettings*>(fn);
    fn = nullptr;
    if (!ok(gGetFeatureFunction(sl::kFeatureDLSS, "slDLSSSetOptions", fn), "DLSS-Optionsfunktion")) return -1003;
    gSetOptions = reinterpret_cast<PFun_slDLSSSetOptions*>(fn);
    const sl::Result nisSupport = gIsFeatureSupported(sl::kFeatureNIS, adapter);
    if (nisSupport == sl::Result::eOk) {
        fn = nullptr;
        if (gGetFeatureFunction(sl::kFeatureNIS, "slNISSetOptions", fn) == sl::Result::eOk) {
            gNisSetOptions = reinterpret_cast<PFun_slNISSetOptions*>(fn);
        }
    }
    setOperationMessage(gNisSetOptions
        ? "DLSS und NVIDIA NIS werden von Streamline akzeptiert"
        : "DLSS wird unterstützt; NVIDIA NIS ist nicht verfügbar");
    return 0;
}

extern "C" JNIEXPORT jint JNICALL Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_queuePresent
  (JNIEnv*, jclass, jlong queue, jlong presentInfo) {
    if (!gInitialized || !gQueuePresentProxy || queue == 0 || presentInfo == 0) return INT32_MIN;
    return static_cast<jint>(gQueuePresentProxy(
        reinterpret_cast<VkQueue>(static_cast<uintptr_t>(queue)),
        reinterpret_cast<const VkPresentInfoKHR*>(static_cast<uintptr_t>(presentInfo))));
}

extern "C" JNIEXPORT jlong JNICALL Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_optimalSize
  (JNIEnv*, jclass, jint mode, jint outputWidth, jint outputHeight) {
    if (!gInitialized || !gGetOptimalSettings || mode == 0) return 0;
    if (mode == 4) return (static_cast<jlong>(outputWidth) << 32) | static_cast<uint32_t>(outputHeight);
    sl::DLSSOptions options{};
    configureOptions(options, mode, static_cast<uint32_t>(outputWidth), static_cast<uint32_t>(outputHeight));
    sl::DLSSOptimalSettings settings{};
    const sl::Result result = gGetOptimalSettings(options, settings);
    if (!ok(result, "Ermittlung der DLSS-Auflösung")) return 0;
    return (static_cast<jlong>(settings.optimalRenderWidth) << 32) | settings.optimalRenderHeight;
}

extern "C" JNIEXPORT jlong JNICALL Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_evaluate
  (JNIEnv* env, jclass, jint viewportId, jint frameIndex, jint mode,
   jint inputWidth, jint inputHeight, jint outputWidth, jint outputHeight,
   jlong dlssCommandBuffer, jlong nisCommandBuffer,
   jlong colorImage, jlong colorView, jlong depthImage, jlong depthView,
   jlong motionImage, jlong motionView, jlong historyBiasImage, jlong historyBiasView,
   jlong transparencyHintImage, jlong transparencyHintView,
   jlong outputImage, jlong outputView,
   jlong sharpenImage, jlong sharpenView, jfloat sharpness,
   jfloatArray projection, jfloatArray inverseProjection, jfloatArray clipToPrev, jfloatArray prevToClip,
   jfloat cameraX, jfloat cameraY, jfloat cameraZ,
   jfloat upX, jfloat upY, jfloat upZ,
   jfloat rightX, jfloat rightY, jfloat rightZ,
   jfloat forwardX, jfloat forwardY, jfloat forwardZ,
    jfloat nearPlane, jfloat farPlane, jfloat fov, jfloat aspect,
     jfloat jitterX, jfloat jitterY, jint auditHintMode, jboolean reset) {
    // Kept in the JNI ABI for compatibility with already compiled Java callers.
    // These resources are deliberately not exposed to Streamline.
    static_cast<void>(historyBiasImage);
    static_cast<void>(historyBiasView);
    if (!gInitialized || !gSetOptions) {
        return packEvaluationResults(-1200, kNisNotRequested);
    }
    if (dlssCommandBuffer == 0) {
        setOperationMessage(
            "DLSS-Auswertung erhielt keinen Commandbuffer"
        );
        return packEvaluationResults(-1205, kNisNotRequested);
    }
    sl::FrameToken* token{};
    const uint32_t frame = static_cast<uint32_t>(frameIndex);
    if (!ok(gGetNewFrameToken(token, &frame), "Frame-Token")) {
        return packEvaluationResults(-1201, kNisNotRequested);
    }
    const sl::ViewportHandle viewport(static_cast<uint32_t>(viewportId));
    sl::DLSSOptions options{};
    configureOptions(options, mode, static_cast<uint32_t>(outputWidth), static_cast<uint32_t>(outputHeight));
    if (!ok(gSetOptions(viewport, options), "DLSS-Modus")) {
        return packEvaluationResults(-1202, kNisNotRequested);
    }

    sl::Constants constants{};
    copyMatrix(env, projection, constants.cameraViewToClip);
    copyMatrix(env, inverseProjection, constants.clipToCameraView);
    copyMatrix(env, clipToPrev, constants.clipToPrevClip);
    copyMatrix(env, prevToClip, constants.prevClipToClip);
    constants.clipToLensClip = sl::float4x4{};
    constants.jitterOffset = {jitterX, jitterY};
    constants.mvecScale = {1.0f / inputWidth, 1.0f / inputHeight};
    constants.cameraPinholeOffset = {0.0f, 0.0f};
    constants.cameraPos = {cameraX, cameraY, cameraZ};
    constants.cameraUp = {upX, upY, upZ};
    constants.cameraRight = {rightX, rightY, rightZ};
    constants.cameraFwd = {forwardX, forwardY, forwardZ};
    constants.cameraNear = nearPlane;
    constants.cameraFar = farPlane;
    constants.cameraFOV = fov;
    constants.cameraAspectRatio = aspect;
    constants.depthInverted = sl::Boolean::eTrue;
    constants.cameraMotionIncluded = sl::Boolean::eTrue;
    constants.motionVectors3D = sl::Boolean::eFalse;
    constants.reset = reset ? sl::Boolean::eTrue : sl::Boolean::eFalse;
    constants.orthographicProjection = sl::Boolean::eFalse;
    constants.motionVectorsDilated = sl::Boolean::eFalse;
    constants.motionVectorsJittered = sl::Boolean::eFalse;

    sl::Resource color = makeImage(colorImage, colorView, inputWidth, inputHeight, VK_FORMAT_R8G8B8A8_UNORM,
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
    sl::Resource depth = makeImage(depthImage, depthView, inputWidth, inputHeight, VK_FORMAT_D32_SFLOAT,
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT);
    sl::Resource motion = makeImage(motionImage, motionView, inputWidth, inputHeight, VK_FORMAT_R16G16_SFLOAT,
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT);
    sl::Resource transparencyHint = makeImage(transparencyHintImage, transparencyHintView, inputWidth, inputHeight, VK_FORMAT_R8G8B8A8_UNORM,
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT);
    sl::Resource output = makeImage(outputImage, outputView, outputWidth, outputHeight, VK_FORMAT_R8G8B8A8_UNORM,
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
    const sl::Extent inputExtent{0, 0, static_cast<uint32_t>(inputWidth), static_cast<uint32_t>(inputHeight)};
    const sl::Extent outputExtent{0, 0, static_cast<uint32_t>(outputWidth), static_cast<uint32_t>(outputHeight)};
    sl::CommandBuffer* dlssCmd = reinterpret_cast<sl::CommandBuffer*>(
        static_cast<uintptr_t>(dlssCommandBuffer)
    );
    // Only mode 1 explicitly enables the supported transparency input. Every
    // other value fails closed so legacy diagnostic modes cannot retag it.
    const bool includeTransparencyHint = auditHintMode == 1;
    const char* temporalHintName = includeTransparencyHint ? "TransparencyHint" : "None";
    sl::ResourceTag tagsWithTransparency[] = {
        {&color, sl::kBufferTypeScalingInputColor, sl::ResourceLifecycle::eOnlyValidNow, &inputExtent},
        {&depth, sl::kBufferTypeDepth, sl::ResourceLifecycle::eOnlyValidNow, &inputExtent},
        {&motion, sl::kBufferTypeMotionVectors, sl::ResourceLifecycle::eOnlyValidNow, &inputExtent},
        {&transparencyHint, sl::kBufferTypeTransparencyHint, sl::ResourceLifecycle::eOnlyValidNow, &inputExtent},
        {&output, sl::kBufferTypeScalingOutputColor, sl::ResourceLifecycle::eOnlyValidNow, &outputExtent}
    };
    sl::ResourceTag tagsWithoutTransparency[] = {
        {&color, sl::kBufferTypeScalingInputColor, sl::ResourceLifecycle::eOnlyValidNow, &inputExtent},
        {&depth, sl::kBufferTypeDepth, sl::ResourceLifecycle::eOnlyValidNow, &inputExtent},
        {&motion, sl::kBufferTypeMotionVectors, sl::ResourceLifecycle::eOnlyValidNow, &inputExtent},
        {&output, sl::kBufferTypeScalingOutputColor, sl::ResourceLifecycle::eOnlyValidNow, &outputExtent}
    };
    const sl::Result tagResult = includeTransparencyHint
        ? gSetTagForFrame(*token, viewport, tagsWithTransparency, 5, dlssCmd)
        : gSetTagForFrame(*token, viewport, tagsWithoutTransparency, 4, dlssCmd);
    if (!ok(tagResult, "DLSS-Ressourcen")) {
        return packEvaluationResults(-1203, kNisNotRequested);
    }
    if (!ok(gSetConstants(constants, *token, viewport), "DLSS-Kameradaten")) {
        return packEvaluationResults(-1204, kNisNotRequested);
    }
    const sl::BaseStructure* inputs[] = {&viewport};
    const sl::Result result = gEvaluateFeature(sl::kFeatureDLSS,
        *token,
        inputs,
        1,
        dlssCmd
    );
    const bool dlssSucceeded = ok(result, "DLSS-Auswertung");
    recordEvaluateResult(
        TrackedFeature::eDlss,
        static_cast<uint32_t>(viewportId),
        dlssSucceeded
    );
    if (!dlssSucceeded) {
        return packEvaluationResults(
            static_cast<jint>(result),
            kNisNotRequested
        );
    }

    if (sharpness > 0.0f) {
        if (!gNisSetOptions || sharpenImage == 0 || sharpenView == 0) {
            setOperationMessage("NVIDIA NIS NVSharpen wurde angefordert, ist aber nicht verfügbar");
            return packEvaluationResults(0, -1210);
        }
        if (nisCommandBuffer == 0) {
            setOperationMessage(
                "NVIDIA NIS NVSharpen erhielt keinen isolierten Commandbuffer"
            );
            return packEvaluationResults(0, -1213);
        }
        sl::CommandBuffer* nisCmd = reinterpret_cast<sl::CommandBuffer*>(
            static_cast<uintptr_t>(nisCommandBuffer)
        );
        sl::NISOptions nisOptions{};
        nisOptions.mode = sl::NISMode::eSharpen;
        nisOptions.hdrMode = sl::NISHDR::eNone;
        nisOptions.sharpness = sharpness > 0.5f ? 0.5f : sharpness;
        if (!ok(gNisSetOptions(viewport, nisOptions), "NIS-NVSharpen-Optionen")) {
            return packEvaluationResults(0, -1211);
        }
        sl::Resource sharpen = makeImage(sharpenImage, sharpenView, outputWidth, outputHeight, VK_FORMAT_R8G8B8A8_UNORM,
            VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
        sl::ResourceTag nisTags[] = {
            {&output, sl::kBufferTypeScalingInputColor, sl::ResourceLifecycle::eOnlyValidNow, &outputExtent},
            {&sharpen, sl::kBufferTypeScalingOutputColor, sl::ResourceLifecycle::eOnlyValidNow, &outputExtent}
        };
        if (!ok(gSetTagForFrame(*token, viewport, nisTags, 2, nisCmd), "NIS-NVSharpen-Ressourcen")) {
            return packEvaluationResults(0, -1212);
        }
        const sl::Result nisResult = gEvaluateFeature(sl::kFeatureNIS,
            *token,
            inputs,
            1,
            nisCmd
        );
        const bool nisSucceeded = ok(
            nisResult,
            "NIS-NVSharpen-Auswertung"
        );
        recordEvaluateResult(
            TrackedFeature::eNis,
            static_cast<uint32_t>(viewportId),
            nisSucceeded
        );
        if (!nisSucceeded) {
            return packEvaluationResults(
                0,
                static_cast<jint>(nisResult)
            );
        }
    }
    const char* preset = mode == 3 ? "M" : mode == 5 ? "L" : "K";
    setOperationMessage(std::string("DLSS aktiv; Preset ") + preset +
        (sharpness > 0.0f ? "; NVIDIA NIS NVSharpen aktiv" : "; NVSharpen aus") +
        "; Temporal-Hinweis " + temporalHintName);
    return packEvaluationResults(
        0,
        sharpness > 0.0f ? 0 : kNisNotRequested
    );
}

extern "C" JNIEXPORT jint JNICALL Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_resetViewport
  (JNIEnv*, jclass, jint viewportId) {
    if (!gInitialized) {
        setOperationMessage(
            "Streamline-Viewport-Bereinigung ohne Initialisierung abgelehnt"
        );
        return -1220;
    }
    if (!gFreeResources) {
        setOperationMessage(
            "Streamline-Viewport-Bereinigung ist nicht auflösbar"
        );
        return -1221;
    }

    const uint32_t id = static_cast<uint32_t>(viewportId);
    const bool dlssActive = isViewportActive(TrackedFeature::eDlss, id);
    const bool nisActive = isViewportActive(TrackedFeature::eNis, id);
    if (!dlssActive && !nisActive) {
        setOperationMessage(
            "Streamline-Viewport hat keine aktiven Feature-Ressourcen"
        );
        return 0;
    }

    const sl::ViewportHandle viewport(id);
    jint firstFailure = 0;
    std::string failureMessage;
    if (dlssActive) {
        const sl::Result result = gFreeResources(
            sl::kFeatureDLSS,
            viewport
        );
        const bool succeeded = result == sl::Result::eOk;
        recordFreeResult(TrackedFeature::eDlss, id, succeeded);
        if (!succeeded) {
            firstFailure = static_cast<jint>(result);
            failureMessage =
                "DLSS-Viewport-Bereinigung fehlgeschlagen (SL-Code "
                    + std::to_string(static_cast<int>(result))
                    + ")";
        }
    }
    if (nisActive) {
        const sl::Result result = gFreeResources(
            sl::kFeatureNIS,
            viewport
        );
        const bool succeeded = result == sl::Result::eOk;
        recordFreeResult(TrackedFeature::eNis, id, succeeded);
        if (!succeeded) {
            if (firstFailure == 0) {
                firstFailure = static_cast<jint>(result);
            }
            if (!failureMessage.empty()) {
                failureMessage += "; ";
            }
            failureMessage +=
                "NIS-Viewport-Bereinigung fehlgeschlagen (SL-Code "
                    + std::to_string(static_cast<int>(result))
                    + ")";
        }
    }
    if (firstFailure != 0) {
        setOperationMessage(failureMessage);
        return firstFailure;
    }
    setOperationMessage(
        "Streamline-Viewport-Ressourcen sauber freigegeben"
    );
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_lastMessage
  (JNIEnv* env, jclass) {
    const std::string message = operationMessageSnapshot();
    return env->NewStringUTF(message.c_str());
}

extern "C" JNIEXPORT jstring JNICALL Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_lastDiagnostic
  (JNIEnv* env, jclass) {
    const std::string diagnostic = asyncDiagnosticSnapshot();
    return env->NewStringUTF(diagnostic.c_str());
}

extern "C" JNIEXPORT jint JNICALL Java_de_morau_nvidiadlss_nativebridge_NativeStreamline_shutdown
  (JNIEnv*, jclass) {
    if (gCleanupUncertain) return kCleanupUnconfirmed;
    if (gInitialized) {
        if (!gShutdown) {
            setOperationMessage("Streamline-Shutdown ist nicht auflösbar");
            markCleanupUncertain();
            return kCleanupUnconfirmed;
        }
        if (!gFreeResources) {
            setOperationMessage(
                "Streamline-Shutdown ohne slFreeResources abgelehnt"
            );
            markCleanupUncertain();
            return kCleanupUnconfirmed;
        }

        jint firstFreeFailure = 0;
        std::string freeFailureMessage;
        for (
            const auto& [feature, viewportId] :
            activeFeatureViewportsSnapshot()
        ) {
            const sl::Feature featureId =
                feature == TrackedFeature::eDlss
                    ? sl::kFeatureDLSS
                    : sl::kFeatureNIS;
            const sl::Result freeResult = gFreeResources(
                featureId,
                sl::ViewportHandle(viewportId)
            );
            const bool succeeded = freeResult == sl::Result::eOk;
            recordFreeResult(feature, viewportId, succeeded);
            if (!succeeded) {
                if (firstFreeFailure == 0) {
                    firstFreeFailure = static_cast<jint>(freeResult);
                }
                if (!freeFailureMessage.empty()) {
                    freeFailureMessage += "; ";
                }
                freeFailureMessage +=
                    (feature == TrackedFeature::eDlss ? "DLSS" : "NIS")
                    + std::string("-Viewport ")
                    + std::to_string(viewportId)
                    + " konnte vor Shutdown nicht freigegeben werden (SL-Code "
                    + std::to_string(static_cast<int>(freeResult))
                    + ")";
            }
        }

        const sl::Result shutdownResult = gShutdown();
        if (shutdownResult != sl::Result::eOk) {
            const std::string prefix = freeFailureMessage.empty()
                ? ""
                : freeFailureMessage + "; ";
            setOperationMessage(
                prefix
                    + "Streamline-Shutdown fehlgeschlagen (SL-Code "
                    + std::to_string(static_cast<int>(shutdownResult))
                    + ")"
            );
            markCleanupUncertain();
            return static_cast<jint>(shutdownResult);
        }
        if (firstFreeFailure != 0) {
            setOperationMessage(
                freeFailureMessage
                    + "; Streamline-Shutdown lief, die explizite Ressourcenfreigabe bleibt unbestaetigt"
            );
            markCleanupUncertain();
            return kCleanupUnconfirmed;
        }
    }

    DWORD releaseError{};
    if (!releaseModuleAndReset(releaseError)) {
        setOperationMessage(
            "sl.interposer.dll konnte nach dem Streamline-Shutdown nicht freigegeben werden (Windows-Code "
                + std::to_string(static_cast<unsigned long>(releaseError))
                + ")"
        );
        return kCleanupUnconfirmed;
    }
    setOperationMessage("NVIDIA Streamline Runtime sauber beendet");
    return 0;
}
