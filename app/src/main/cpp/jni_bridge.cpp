#include "metadata_parser.h"
#include "metadata_search.h"
#include "process_memory.h"
#include "runtime_metadata.h"

#include <android/log.h>
#include <jni.h>

#include <array>
#include <cstdint>
#include <exception>
#include <limits>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace il2cppmanager {
namespace {

constexpr jlong kInvalidLong = -1;
constexpr jlong kUnavailableFieldOffset = std::numeric_limits<jlong>::min();
constexpr jint kInvalidInt = -1;
constexpr char kNativeEngineClass[] = "dev/ruri/il2cppmanager/nativebridge/NativeEngine";
constexpr char kNativeSymbolSearchPageClass[] =
    "dev/ruri/il2cppmanager/nativebridge/NativeSymbolSearchPage";
constexpr char kNativeMethodReferencePageClass[] =
    "dev/ruri/il2cppmanager/nativebridge/NativeMethodReferencePage";
constexpr char kNativeInstructionPageClass[] =
    "dev/ruri/il2cppmanager/nativebridge/NativeInstructionPage";
constexpr char kLogTag[] = "Il2CppNative";
constexpr char kIl2CppModuleName[] = "libil2cpp.so";
constexpr jint kMaximumSymbolSearchPageSize = 32;
constexpr jint kMaximumAnalysisPageSize = 64;

template <typename Result, typename Operation>
Result guarded(Result fallback, Operation&& operation) noexcept {
    try {
        return operation();
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Native operation failed: %s", error.what());
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Native operation failed with an unknown error");
    }
    return fallback;
}

template <typename Operation>
void guardedVoid(Operation&& operation) noexcept {
    try {
        operation();
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Native operation failed: %s", error.what());
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Native operation failed with an unknown error");
    }
}

class MetadataRegistry final {
public:
    struct Entry {
        std::shared_ptr<const MetadataModel> model;
        std::shared_ptr<RuntimeMetadataResolver> runtime;
    };

    jlong insert(std::shared_ptr<const MetadataModel> model) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (nextHandle_ > static_cast<std::uint64_t>(std::numeric_limits<jlong>::max())) {
            return 0;
        }
        const auto handle = static_cast<jlong>(nextHandle_++);
        entries_.emplace(handle, Entry{std::move(model), nullptr});
        return handle;
    }

    std::shared_ptr<const MetadataModel> get(jlong handle) const {
        if (handle <= 0) {
            return nullptr;
        }
        std::lock_guard<std::mutex> lock(mutex_);
        const auto found = entries_.find(handle);
        return found == entries_.end() ? nullptr : found->second.model;
    }

    bool attachRuntime(jlong handle, std::shared_ptr<RuntimeMetadataResolver> runtime) {
        if (handle <= 0 || runtime == nullptr) {
            return false;
        }
        std::lock_guard<std::mutex> lock(mutex_);
        const auto found = entries_.find(handle);
        if (found == entries_.end()) {
            return false;
        }
        found->second.runtime = std::move(runtime);
        return true;
    }

    std::shared_ptr<RuntimeMetadataResolver> runtime(jlong handle) const {
        if (handle <= 0) {
            return nullptr;
        }
        std::lock_guard<std::mutex> lock(mutex_);
        const auto found = entries_.find(handle);
        return found == entries_.end() ? nullptr : found->second.runtime;
    }

    void erase(jlong handle) {
        std::lock_guard<std::mutex> lock(mutex_);
        entries_.erase(handle);
    }

private:
    mutable std::mutex mutex_;
    std::unordered_map<jlong, Entry> entries_;
    std::uint64_t nextHandle_ = 1;
};

MetadataRegistry& registry() {
    static MetadataRegistry instance;
    return instance;
}

bool appendCodePoint(std::vector<jchar>& result, std::uint32_t codePoint) {
    if (codePoint <= 0xFFFFU) {
        if (codePoint >= 0xD800U && codePoint <= 0xDFFFU) {
            return false;
        }
        result.push_back(static_cast<jchar>(codePoint));
        return true;
    }
    if (codePoint > 0x10FFFFU) {
        return false;
    }
    codePoint -= 0x10000U;
    result.push_back(static_cast<jchar>(0xD800U + (codePoint >> 10U)));
    result.push_back(static_cast<jchar>(0xDC00U + (codePoint & 0x3FFU)));
    return true;
}

std::optional<std::vector<jchar>> decodeUtf8(const std::string& value, bool replaceInvalid) {
    std::vector<jchar> result;
    result.reserve(value.size());
    const auto* bytes = reinterpret_cast<const std::uint8_t*>(value.data());
    std::size_t index = 0;
    while (index < value.size()) {
        const auto first = bytes[index];
        std::uint32_t codePoint = 0;
        std::size_t length = 0;
        if (first <= 0x7F) {
            codePoint = first;
            length = 1;
        } else if (first >= 0xC2 && first <= 0xDF && index + 1 < value.size() &&
                   (bytes[index + 1] & 0xC0U) == 0x80U) {
            codePoint = static_cast<std::uint32_t>(first & 0x1FU) << 6U |
                        static_cast<std::uint32_t>(bytes[index + 1] & 0x3FU);
            length = 2;
        } else if (first >= 0xE0 && first <= 0xEF && index + 2 < value.size() &&
                   (bytes[index + 1] & 0xC0U) == 0x80U &&
                   (bytes[index + 2] & 0xC0U) == 0x80U &&
                   !(first == 0xE0 && bytes[index + 1] < 0xA0) &&
                   !(first == 0xED && bytes[index + 1] >= 0xA0)) {
            codePoint = static_cast<std::uint32_t>(first & 0x0FU) << 12U |
                        static_cast<std::uint32_t>(bytes[index + 1] & 0x3FU) << 6U |
                        static_cast<std::uint32_t>(bytes[index + 2] & 0x3FU);
            length = 3;
        } else if (first >= 0xF0 && first <= 0xF4 && index + 3 < value.size() &&
                   (bytes[index + 1] & 0xC0U) == 0x80U &&
                   (bytes[index + 2] & 0xC0U) == 0x80U &&
                   (bytes[index + 3] & 0xC0U) == 0x80U &&
                   !(first == 0xF0 && bytes[index + 1] < 0x90) &&
                   !(first == 0xF4 && bytes[index + 1] >= 0x90)) {
            codePoint = static_cast<std::uint32_t>(first & 0x07U) << 18U |
                        static_cast<std::uint32_t>(bytes[index + 1] & 0x3FU) << 12U |
                        static_cast<std::uint32_t>(bytes[index + 2] & 0x3FU) << 6U |
                        static_cast<std::uint32_t>(bytes[index + 3] & 0x3FU);
            length = 4;
        } else {
            if (!replaceInvalid) {
                return std::nullopt;
            }
            result.push_back(static_cast<jchar>(0xFFFDU));
            ++index;
            continue;
        }
        if (!appendCodePoint(result, codePoint)) {
            return std::nullopt;
        }
        index += length;
    }
    return result;
}

jstring toJavaString(JNIEnv* environment, const std::string& value, bool replaceInvalid = false) {
    auto decoded = decodeUtf8(value, replaceInvalid);
    if (!decoded || decoded->size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        return nullptr;
    }
    return environment->NewString(decoded->data(), static_cast<jsize>(decoded->size()));
}

std::optional<std::string> fromJavaString(JNIEnv* environment, jstring value) {
    if (value == nullptr) {
        return std::nullopt;
    }
    const auto length = environment->GetStringLength(value);
    if (length < 0 || length > 4096) {
        return std::nullopt;
    }
    const auto* characters = environment->GetStringChars(value, nullptr);
    if (characters == nullptr) {
        return std::nullopt;
    }

    std::string result;
    result.reserve(static_cast<std::size_t>(length));
    bool valid = true;
    for (jsize index = 0; index < length && valid; ++index) {
        std::uint32_t codePoint = characters[index];
        if (codePoint >= 0xD800U && codePoint <= 0xDBFFU) {
            if (index + 1 >= length) {
                valid = false;
                break;
            }
            const auto low = static_cast<std::uint32_t>(characters[++index]);
            if (low < 0xDC00U || low > 0xDFFFU) {
                valid = false;
                break;
            }
            codePoint = 0x10000U + ((codePoint - 0xD800U) << 10U) + (low - 0xDC00U);
        } else if (codePoint >= 0xDC00U && codePoint <= 0xDFFFU) {
            valid = false;
            break;
        }

        if (codePoint <= 0x7FU) {
            result.push_back(static_cast<char>(codePoint));
        } else if (codePoint <= 0x7FFU) {
            result.push_back(static_cast<char>(0xC0U | (codePoint >> 6U)));
            result.push_back(static_cast<char>(0x80U | (codePoint & 0x3FU)));
        } else if (codePoint <= 0xFFFFU) {
            result.push_back(static_cast<char>(0xE0U | (codePoint >> 12U)));
            result.push_back(static_cast<char>(0x80U | ((codePoint >> 6U) & 0x3FU)));
            result.push_back(static_cast<char>(0x80U | (codePoint & 0x3FU)));
        } else {
            result.push_back(static_cast<char>(0xF0U | (codePoint >> 18U)));
            result.push_back(static_cast<char>(0x80U | ((codePoint >> 12U) & 0x3FU)));
            result.push_back(static_cast<char>(0x80U | ((codePoint >> 6U) & 0x3FU)));
            result.push_back(static_cast<char>(0x80U | (codePoint & 0x3FU)));
        }
    }
    environment->ReleaseStringChars(value, characters);
    return valid ? std::optional<std::string>(std::move(result)) : std::nullopt;
}

template <typename Value>
const Value* at(const std::vector<Value>& values, jint index) noexcept {
    if (index < 0 || static_cast<std::size_t>(index) >= values.size()) {
        return nullptr;
    }
    return &values[static_cast<std::size_t>(index)];
}

template <typename Container>
jint javaCount(const Container* values) noexcept {
    if (values == nullptr ||
        values->size() > static_cast<std::size_t>(std::numeric_limits<jint>::max())) {
        return kInvalidInt;
    }
    return static_cast<jint>(values->size());
}

const NamespaceMetadata* getNamespace(
    const MetadataModel& model,
    jint assemblyIndex,
    jint namespaceIndex) noexcept {
    const auto* assembly = at(model.assemblies(), assemblyIndex);
    return assembly == nullptr ? nullptr : at(assembly->namespaces, namespaceIndex);
}

const TypeMetadata* getType(const MetadataModel& model, jint classIndex) noexcept {
    return at(model.types(), classIndex);
}

bool validMethodAnalysisRequest(
    const MetadataModel* model,
    jint classIndex,
    jint methodIndex,
    jint offset,
    jint limit) noexcept {
    const auto* type = model == nullptr ? nullptr : getType(*model, classIndex);
    return type != nullptr && at(type->methods, methodIndex) != nullptr &&
           offset >= 0 && limit > 0 && limit <= kMaximumAnalysisPageSize;
}

jintArray toJavaIntArray(JNIEnv* environment, const std::vector<jint>& values) {
    if (values.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        return nullptr;
    }
    const auto size = static_cast<jsize>(values.size());
    auto result = environment->NewIntArray(size);
    if (result == nullptr) {
        return nullptr;
    }
    if (size > 0) {
        environment->SetIntArrayRegion(result, 0, size, values.data());
    }
    return environment->ExceptionCheck() ? nullptr : result;
}

jlongArray toJavaLongArray(JNIEnv* environment, const std::vector<jlong>& values) {
    if (values.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        return nullptr;
    }
    const auto size = static_cast<jsize>(values.size());
    auto result = environment->NewLongArray(size);
    if (result == nullptr) {
        return nullptr;
    }
    if (size > 0) {
        environment->SetLongArrayRegion(result, 0, size, values.data());
    }
    return environment->ExceptionCheck() ? nullptr : result;
}

jbooleanArray toJavaBooleanArray(
    JNIEnv* environment,
    const std::vector<jboolean>& values) {
    if (values.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        return nullptr;
    }
    const auto size = static_cast<jsize>(values.size());
    auto result = environment->NewBooleanArray(size);
    if (result == nullptr) {
        return nullptr;
    }
    if (size > 0) {
        environment->SetBooleanArrayRegion(result, 0, size, values.data());
    }
    return environment->ExceptionCheck() ? nullptr : result;
}

jobjectArray toJavaStringArray(JNIEnv* environment, const std::vector<std::string>& values) {
    if (values.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        return nullptr;
    }
    const auto stringClass = environment->FindClass("java/lang/String");
    if (stringClass == nullptr) {
        return nullptr;
    }
    const auto size = static_cast<jsize>(values.size());
    auto result = environment->NewObjectArray(size, stringClass, nullptr);
    environment->DeleteLocalRef(stringClass);
    if (result == nullptr) {
        return nullptr;
    }
    for (jsize index = 0; index < size; ++index) {
        auto value = toJavaString(environment, values[static_cast<std::size_t>(index)]);
        if (value == nullptr) {
            return nullptr;
        }
        environment->SetObjectArrayElement(result, index, value);
        environment->DeleteLocalRef(value);
        if (environment->ExceptionCheck()) {
            return nullptr;
        }
    }
    return result;
}

jobject toJavaMethodReferencePage(
    JNIEnv* environment,
    const RuntimeMetadataResolver::MethodReferencePage& page) {
    if (page.totalCount > static_cast<std::size_t>(std::numeric_limits<jint>::max()) ||
        page.items.size() > static_cast<std::size_t>(kMaximumAnalysisPageSize) ||
        page.indirectCallCount < 0 || environment->PushLocalFrame(32) < 0) {
        return nullptr;
    }

    std::vector<jint> classIndices;
    std::vector<jint> methodIndices;
    std::vector<std::string> names;
    std::vector<std::string> ownerNames;
    std::vector<std::string> signatures;
    std::vector<jboolean> referenceResolved;
    std::vector<jboolean> signatureResolved;
    std::vector<jlong> addresses;
    std::vector<jlong> rvas;
    std::vector<jboolean> rvaResolved;
    std::vector<jlong> callSiteAddresses;
    std::vector<jlong> callSiteRvas;
    std::vector<jboolean> callSiteRvaResolved;
    std::vector<jint> callSiteInstructionIndices;
    classIndices.reserve(page.items.size());
    methodIndices.reserve(page.items.size());
    names.reserve(page.items.size());
    ownerNames.reserve(page.items.size());
    signatures.reserve(page.items.size());
    referenceResolved.reserve(page.items.size());
    signatureResolved.reserve(page.items.size());
    addresses.reserve(page.items.size());
    rvas.reserve(page.items.size());
    rvaResolved.reserve(page.items.size());
    callSiteAddresses.reserve(page.items.size());
    callSiteRvas.reserve(page.items.size());
    callSiteRvaResolved.reserve(page.items.size());
    callSiteInstructionIndices.reserve(page.items.size());

    for (const auto& item : page.items) {
        if (item.address == 0 || item.callSiteAddress == 0 ||
            item.callSiteInstructionIndex < 0 ||
            item.address > static_cast<std::uint64_t>(std::numeric_limits<jlong>::max()) ||
            item.callSiteAddress >
                static_cast<std::uint64_t>(std::numeric_limits<jlong>::max())) {
            environment->PopLocalFrame(nullptr);
            return nullptr;
        }
        const auto resolved = item.classIndex >= 0 && item.methodIndex >= 0;
        const auto resolvedRva = item.rva &&
            *item.rva <= static_cast<std::uint64_t>(std::numeric_limits<jlong>::max());
        const auto resolvedCallSiteRva = item.callSiteRva &&
            *item.callSiteRva <= static_cast<std::uint64_t>(std::numeric_limits<jlong>::max());
        classIndices.push_back(resolved ? item.classIndex : kInvalidInt);
        methodIndices.push_back(resolved ? item.methodIndex : kInvalidInt);
        names.push_back(resolved ? item.name : std::string{});
        ownerNames.push_back(resolved ? item.ownerName : std::string{});
        signatures.push_back(resolved && item.signature ? *item.signature : std::string{});
        referenceResolved.push_back(resolved ? JNI_TRUE : JNI_FALSE);
        signatureResolved.push_back(resolved && item.signature ? JNI_TRUE : JNI_FALSE);
        addresses.push_back(static_cast<jlong>(item.address));
        rvas.push_back(resolvedRva ? static_cast<jlong>(*item.rva) : 0);
        rvaResolved.push_back(resolvedRva ? JNI_TRUE : JNI_FALSE);
        callSiteAddresses.push_back(static_cast<jlong>(item.callSiteAddress));
        callSiteRvas.push_back(
            resolvedCallSiteRva ? static_cast<jlong>(*item.callSiteRva) : 0);
        callSiteRvaResolved.push_back(resolvedCallSiteRva ? JNI_TRUE : JNI_FALSE);
        callSiteInstructionIndices.push_back(item.callSiteInstructionIndex);
    }

    const auto pageClass = environment->FindClass(kNativeMethodReferencePageClass);
    const auto constructor = pageClass == nullptr
                                 ? nullptr
                                 : environment->GetMethodID(
                                       pageClass,
                                       "<init>",
                                       "(III[I[I[Ljava/lang/String;[Ljava/lang/String;"
                                       "[Ljava/lang/String;[Z[Z[J[J[Z[J[J[Z[I)V");
    const auto javaClassIndices = toJavaIntArray(environment, classIndices);
    const auto javaMethodIndices = toJavaIntArray(environment, methodIndices);
    const auto javaNames = toJavaStringArray(environment, names);
    const auto javaOwnerNames = toJavaStringArray(environment, ownerNames);
    const auto javaSignatures = toJavaStringArray(environment, signatures);
    const auto javaReferenceResolved = toJavaBooleanArray(environment, referenceResolved);
    const auto javaSignatureResolved = toJavaBooleanArray(environment, signatureResolved);
    const auto javaAddresses = toJavaLongArray(environment, addresses);
    const auto javaRvas = toJavaLongArray(environment, rvas);
    const auto javaRvaResolved = toJavaBooleanArray(environment, rvaResolved);
    const auto javaCallSiteAddresses = toJavaLongArray(environment, callSiteAddresses);
    const auto javaCallSiteRvas = toJavaLongArray(environment, callSiteRvas);
    const auto javaCallSiteRvaResolved = toJavaBooleanArray(environment, callSiteRvaResolved);
    const auto javaCallSiteInstructionIndices = toJavaIntArray(
        environment,
        callSiteInstructionIndices);
    if (constructor == nullptr || javaClassIndices == nullptr || javaMethodIndices == nullptr ||
        javaNames == nullptr || javaOwnerNames == nullptr || javaSignatures == nullptr ||
        javaReferenceResolved == nullptr || javaSignatureResolved == nullptr ||
        javaAddresses == nullptr || javaRvas == nullptr || javaRvaResolved == nullptr ||
        javaCallSiteAddresses == nullptr || javaCallSiteRvas == nullptr ||
        javaCallSiteRvaResolved == nullptr || javaCallSiteInstructionIndices == nullptr ||
        environment->ExceptionCheck()) {
        environment->PopLocalFrame(nullptr);
        return nullptr;
    }
    const auto result = environment->NewObject(
        pageClass,
        constructor,
        static_cast<jint>(page.totalCount),
        static_cast<jint>(page.status),
        page.indirectCallCount,
        javaClassIndices,
        javaMethodIndices,
        javaNames,
        javaOwnerNames,
        javaSignatures,
        javaReferenceResolved,
        javaSignatureResolved,
        javaAddresses,
        javaRvas,
        javaRvaResolved,
        javaCallSiteAddresses,
        javaCallSiteRvas,
        javaCallSiteRvaResolved,
        javaCallSiteInstructionIndices);
    return environment->PopLocalFrame(environment->ExceptionCheck() ? nullptr : result);
}

jobject toJavaInstructionPage(
    JNIEnv* environment,
    const RuntimeMetadataResolver::InstructionPage& page) {
    if (page.totalCount > static_cast<std::size_t>(std::numeric_limits<jint>::max()) ||
        page.items.size() > static_cast<std::size_t>(kMaximumAnalysisPageSize) ||
        page.indirectCallCount < 0 || environment->PushLocalFrame(48) < 0) {
        return nullptr;
    }

    std::vector<jlong> addresses;
    std::vector<jlong> rvas;
    std::vector<jboolean> rvaResolved;
    std::vector<std::string> bytes;
    std::vector<std::string> mnemonics;
    std::vector<std::string> operands;
    std::vector<jint> flowKinds;
    std::vector<jint> targetInstructionIndices;
    std::vector<jint> targetClassIndices;
    std::vector<jint> targetMethodIndices;
    std::vector<std::string> targetNames;
    std::vector<std::string> targetOwnerNames;
    std::vector<std::string> targetSignatures;
    std::vector<jboolean> targetPresent;
    std::vector<jboolean> targetMethodResolved;
    std::vector<jboolean> targetSignatureResolved;
    std::vector<jlong> targetAddresses;
    std::vector<jlong> targetRvas;
    std::vector<jboolean> targetRvaResolved;
    addresses.reserve(page.items.size());
    rvas.reserve(page.items.size());
    rvaResolved.reserve(page.items.size());
    bytes.reserve(page.items.size());
    mnemonics.reserve(page.items.size());
    operands.reserve(page.items.size());
    flowKinds.reserve(page.items.size());
    targetInstructionIndices.reserve(page.items.size());
    targetClassIndices.reserve(page.items.size());
    targetMethodIndices.reserve(page.items.size());
    targetNames.reserve(page.items.size());
    targetOwnerNames.reserve(page.items.size());
    targetSignatures.reserve(page.items.size());
    targetPresent.reserve(page.items.size());
    targetMethodResolved.reserve(page.items.size());
    targetSignatureResolved.reserve(page.items.size());
    targetAddresses.reserve(page.items.size());
    targetRvas.reserve(page.items.size());
    targetRvaResolved.reserve(page.items.size());

    for (const auto& item : page.items) {
        if (item.address == 0 ||
            item.address > static_cast<std::uint64_t>(std::numeric_limits<jlong>::max()) ||
            item.bytes.empty() || item.mnemonic.empty() ||
            item.targetInstructionIndex < -1 ||
            (item.targetInstructionIndex >= 0 &&
             static_cast<std::size_t>(item.targetInstructionIndex) >= page.totalCount)) {
            environment->PopLocalFrame(nullptr);
            return nullptr;
        }
        const auto resolvedRva = item.rva &&
            *item.rva <= static_cast<std::uint64_t>(std::numeric_limits<jlong>::max());
        addresses.push_back(static_cast<jlong>(item.address));
        rvas.push_back(resolvedRva ? static_cast<jlong>(*item.rva) : 0);
        rvaResolved.push_back(resolvedRva ? JNI_TRUE : JNI_FALSE);
        bytes.push_back(item.bytes);
        mnemonics.push_back(item.mnemonic);
        operands.push_back(item.operands);
        flowKinds.push_back(static_cast<jint>(item.flow));
        targetInstructionIndices.push_back(item.targetInstructionIndex);
        const auto present = item.target.has_value();
        const auto resolved = present && item.target->classIndex >= 0 &&
            item.target->methodIndex >= 0;
        const auto resolvedTargetRva = present && item.target->rva &&
            *item.target->rva <= static_cast<std::uint64_t>(std::numeric_limits<jlong>::max());
        if (present && (item.target->address == 0 ||
                        item.target->address >
                            static_cast<std::uint64_t>(std::numeric_limits<jlong>::max()))) {
            environment->PopLocalFrame(nullptr);
            return nullptr;
        }
        targetClassIndices.push_back(resolved ? item.target->classIndex : kInvalidInt);
        targetMethodIndices.push_back(resolved ? item.target->methodIndex : kInvalidInt);
        targetNames.push_back(resolved ? item.target->name : std::string{});
        targetOwnerNames.push_back(resolved ? item.target->ownerName : std::string{});
        targetSignatures.push_back(
            resolved && item.target->signature ? *item.target->signature : std::string{});
        targetPresent.push_back(present ? JNI_TRUE : JNI_FALSE);
        targetMethodResolved.push_back(resolved ? JNI_TRUE : JNI_FALSE);
        targetSignatureResolved.push_back(
            resolved && item.target->signature ? JNI_TRUE : JNI_FALSE);
        targetAddresses.push_back(present ? static_cast<jlong>(item.target->address) : 0);
        targetRvas.push_back(
            resolvedTargetRva ? static_cast<jlong>(*item.target->rva) : 0);
        targetRvaResolved.push_back(resolvedTargetRva ? JNI_TRUE : JNI_FALSE);
    }

    const auto pageClass = environment->FindClass(kNativeInstructionPageClass);
    const auto constructor = pageClass == nullptr
                                 ? nullptr
                                 : environment->GetMethodID(
                                       pageClass,
                                       "<init>",
                                       "(III[J[J[Z[Ljava/lang/String;"
                                       "[Ljava/lang/String;[Ljava/lang/String;"
                                       "[I[I[I[I[Ljava/lang/String;[Ljava/lang/String;"
                                       "[Ljava/lang/String;[Z[Z[Z[J[J[Z)V");
    const auto javaAddresses = toJavaLongArray(environment, addresses);
    const auto javaRvas = toJavaLongArray(environment, rvas);
    const auto javaRvaResolved = toJavaBooleanArray(environment, rvaResolved);
    const auto javaBytes = toJavaStringArray(environment, bytes);
    const auto javaMnemonics = toJavaStringArray(environment, mnemonics);
    const auto javaOperands = toJavaStringArray(environment, operands);
    const auto javaFlowKinds = toJavaIntArray(environment, flowKinds);
    const auto javaTargetInstructionIndices = toJavaIntArray(
        environment,
        targetInstructionIndices);
    const auto javaTargetClassIndices = toJavaIntArray(environment, targetClassIndices);
    const auto javaTargetMethodIndices = toJavaIntArray(environment, targetMethodIndices);
    const auto javaTargetNames = toJavaStringArray(environment, targetNames);
    const auto javaTargetOwnerNames = toJavaStringArray(environment, targetOwnerNames);
    const auto javaTargetSignatures = toJavaStringArray(environment, targetSignatures);
    const auto javaTargetPresent = toJavaBooleanArray(environment, targetPresent);
    const auto javaTargetMethodResolved = toJavaBooleanArray(environment, targetMethodResolved);
    const auto javaTargetSignatureResolved = toJavaBooleanArray(
        environment,
        targetSignatureResolved);
    const auto javaTargetAddresses = toJavaLongArray(environment, targetAddresses);
    const auto javaTargetRvas = toJavaLongArray(environment, targetRvas);
    const auto javaTargetRvaResolved = toJavaBooleanArray(environment, targetRvaResolved);
    if (constructor == nullptr || javaAddresses == nullptr || javaRvas == nullptr ||
        javaRvaResolved == nullptr || javaBytes == nullptr || javaMnemonics == nullptr ||
        javaOperands == nullptr || javaFlowKinds == nullptr ||
        javaTargetInstructionIndices == nullptr || javaTargetClassIndices == nullptr ||
        javaTargetMethodIndices == nullptr || javaTargetNames == nullptr ||
        javaTargetOwnerNames == nullptr || javaTargetSignatures == nullptr ||
        javaTargetPresent == nullptr || javaTargetMethodResolved == nullptr ||
        javaTargetSignatureResolved == nullptr || javaTargetAddresses == nullptr ||
        javaTargetRvas == nullptr || javaTargetRvaResolved == nullptr ||
        environment->ExceptionCheck()) {
        environment->PopLocalFrame(nullptr);
        return nullptr;
    }
    const auto result = environment->NewObject(
        pageClass,
        constructor,
        static_cast<jint>(page.totalCount),
        static_cast<jint>(page.status),
        page.indirectCallCount,
        javaAddresses,
        javaRvas,
        javaRvaResolved,
        javaBytes,
        javaMnemonics,
        javaOperands,
        javaFlowKinds,
        javaTargetInstructionIndices,
        javaTargetClassIndices,
        javaTargetMethodIndices,
        javaTargetNames,
        javaTargetOwnerNames,
        javaTargetSignatures,
        javaTargetPresent,
        javaTargetMethodResolved,
        javaTargetSignatureResolved,
        javaTargetAddresses,
        javaTargetRvas,
        javaTargetRvaResolved);
    return environment->PopLocalFrame(environment->ExceptionCheck() ? nullptr : result);
}

const TypeMetadata* getTypeAt(
    const MetadataModel& model,
    jint assemblyIndex,
    jint namespaceIndex,
    jint index) noexcept {
    const auto* namespaze = getNamespace(model, assemblyIndex, namespaceIndex);
    if (namespaze == nullptr) {
        return nullptr;
    }
    const auto* classIndex = at(namespaze->typeIndices, index);
    return classIndex == nullptr ? nullptr : getType(model, *classIndex);
}

std::optional<std::uint16_t> accessorFlags(
    const TypeMetadata* type,
    std::int32_t methodIndex) noexcept {
    const auto* method = type == nullptr ? nullptr : at(type->methods, methodIndex);
    return method == nullptr ? std::nullopt : std::optional(method->flags);
}

std::optional<std::int32_t> propertyTypeIndex(
    const TypeMetadata* type,
    const PropertyMetadata* property) noexcept {
    if (type == nullptr || property == nullptr) {
        return std::nullopt;
    }
    const auto* getter = at(type->methods, property->getterIndex);
    if (getter != nullptr) {
        return getter->returnTypeIndex;
    }
    const auto* setter = at(type->methods, property->setterIndex);
    return setter == nullptr || setter->parameters.empty()
               ? std::nullopt
               : std::optional(setter->parameters.back().typeIndex);
}

jintArray nativeScanProcessIds(JNIEnv* environment, jobject) {
    return guarded<jintArray>(nullptr, [&] {
        const auto pids = scanProcessIds();
        if (pids.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
            return static_cast<jintArray>(nullptr);
        }
        auto result = environment->NewIntArray(static_cast<jsize>(pids.size()));
        if (result == nullptr) {
            return static_cast<jintArray>(nullptr);
        }
        std::vector<jint> javaPids(pids.begin(), pids.end());
        environment->SetIntArrayRegion(result, 0, static_cast<jsize>(javaPids.size()), javaPids.data());
        return environment->ExceptionCheck() ? static_cast<jintArray>(nullptr) : result;
    });
}

jstring nativeProcessName(JNIEnv* environment, jobject, jint pid) {
    return guarded<jstring>(nullptr, [&] {
        const auto name = readProcessName(pid);
        return name ? toJavaString(environment, *name, true) : nullptr;
    });
}

jlong nativeProcessStartTicks(JNIEnv*, jobject, jint pid) {
    return guarded<jlong>(kInvalidLong, [pid] {
        const auto ticks = readProcessStartTicks(pid);
        return ticks ? static_cast<jlong>(*ticks) : kInvalidLong;
    });
}

jlong nativeModuleBase(JNIEnv* environment, jobject, jint pid, jstring moduleName) {
    return guarded<jlong>(0, [&] {
        const auto name = fromJavaString(environment, moduleName);
        if (!name) {
            return static_cast<jlong>(0);
        }
        const auto base = findModuleBase(pid, *name);
        return base ? static_cast<jlong>(*base) : static_cast<jlong>(0);
    });
}

jlong nativeOpenMetadata(
    JNIEnv* environment,
    jobject,
    jbyteArray bytes,
    jboolean allowErasedMagic) {
    return guarded<jlong>(0, [&] {
        if (bytes == nullptr) {
            return static_cast<jlong>(0);
        }
        const auto size = environment->GetArrayLength(bytes);
        if (size < 0 || static_cast<std::size_t>(size) > MetadataModel::kMaximumMetadataBytes) {
            return static_cast<jlong>(0);
        }
        std::vector<std::uint8_t> metadata(static_cast<std::size_t>(size));
        if (size > 0) {
            environment->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(metadata.data()));
            if (environment->ExceptionCheck()) {
                return static_cast<jlong>(0);
            }
        }
        auto parsed = parseMetadata(std::move(metadata), allowErasedMagic == JNI_TRUE);
        if (!parsed) {
            __android_log_print(
                ANDROID_LOG_ERROR,
                kLogTag,
                "Metadata parse failed: error=%d stage=%d index=%zu",
                static_cast<int>(parsed.error),
                static_cast<int>(parsed.stage),
                parsed.index);
        }
        return parsed ? registry().insert(std::move(parsed.model)) : static_cast<jlong>(0);
    });
}

jlong nativeProbeMetadataSize(
    JNIEnv* environment,
    jobject,
    jbyteArray headerBytes,
    jlong availableBytes,
    jboolean allowErasedMagic) {
    return guarded<jlong>(kInvalidLong, [&] {
        if (headerBytes == nullptr || availableBytes <= 0 ||
            static_cast<std::uint64_t>(availableBytes) >
                static_cast<std::uint64_t>(std::numeric_limits<std::size_t>::max())) {
            return kInvalidLong;
        }
        const auto size = environment->GetArrayLength(headerBytes);
        if (size <= 0) {
            return kInvalidLong;
        }
        std::vector<std::uint8_t> bytes(static_cast<std::size_t>(size));
        environment->GetByteArrayRegion(
            headerBytes,
            0,
            size,
            reinterpret_cast<jbyte*>(bytes.data()));
        if (environment->ExceptionCheck()) {
            return kInvalidLong;
        }
        const auto result = probeMetadataSize(
            bytes,
            static_cast<std::size_t>(availableBytes),
            allowErasedMagic == JNI_TRUE);
        return result && *result <= static_cast<std::size_t>(std::numeric_limits<jlong>::max())
                   ? static_cast<jlong>(*result)
                   : kInvalidLong;
    });
}

void nativeCloseMetadata(JNIEnv*, jobject, jlong handle) {
    guardedVoid([handle] { registry().erase(handle); });
}

jboolean nativeAttachRuntime(JNIEnv*, jobject, jlong handle, jint pid) {
    return guarded<jboolean>(JNI_FALSE, [handle, pid] {
        const auto model = registry().get(handle);
        if (model == nullptr) {
            return static_cast<jboolean>(JNI_FALSE);
        }
        auto runtime = RuntimeMetadataResolver::attach(pid, kIl2CppModuleName, model);
        return registry().attachRuntime(handle, std::move(runtime))
                   ? static_cast<jboolean>(JNI_TRUE)
                   : static_cast<jboolean>(JNI_FALSE);
    });
}

jint nativeAssemblyCount(JNIEnv*, jobject, jlong handle) {
    return guarded<jint>(kInvalidInt, [handle] {
        const auto model = registry().get(handle);
        return model ? javaCount(&model->assemblies()) : kInvalidInt;
    });
}

jstring nativeAssemblyName(JNIEnv* environment, jobject, jlong handle, jint index) {
    return guarded<jstring>(nullptr, [&] {
        const auto model = registry().get(handle);
        const auto* assembly = model ? at(model->assemblies(), index) : nullptr;
        return assembly ? toJavaString(environment, assembly->name) : nullptr;
    });
}

jintArray nativeAssemblyTypeIndices(
    JNIEnv* environment,
    jobject,
    jlong handle,
    jint assemblyIndex) {
    return guarded<jintArray>(nullptr, [&] {
        const auto model = registry().get(handle);
        const auto* assembly = model ? at(model->assemblies(), assemblyIndex) : nullptr;
        if (assembly == nullptr ||
            assembly->typeIndices.size() >
                static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
            return static_cast<jintArray>(nullptr);
        }
        const auto size = static_cast<jsize>(assembly->typeIndices.size());
        auto result = environment->NewIntArray(size);
        if (result == nullptr) {
            return static_cast<jintArray>(nullptr);
        }
        std::vector<jint> indices(assembly->typeIndices.begin(), assembly->typeIndices.end());
        if (size > 0) {
            environment->SetIntArrayRegion(result, 0, size, indices.data());
        }
        return environment->ExceptionCheck() ? static_cast<jintArray>(nullptr) : result;
    });
}

jobjectArray nativeAssemblyTypeNames(
    JNIEnv* environment,
    jobject,
    jlong handle,
    jint assemblyIndex) {
    return guarded<jobjectArray>(nullptr, [&] {
        const auto model = registry().get(handle);
        const auto* assembly = model ? at(model->assemblies(), assemblyIndex) : nullptr;
        if (assembly == nullptr ||
            assembly->typeIndices.size() >
                static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
            return static_cast<jobjectArray>(nullptr);
        }
        const auto stringClass = environment->FindClass("java/lang/String");
        if (stringClass == nullptr) {
            return static_cast<jobjectArray>(nullptr);
        }
        const auto size = static_cast<jsize>(assembly->typeIndices.size());
        auto result = environment->NewObjectArray(size, stringClass, nullptr);
        environment->DeleteLocalRef(stringClass);
        if (result == nullptr) {
            return static_cast<jobjectArray>(nullptr);
        }
        for (jsize index = 0; index < size; ++index) {
            const auto* type = getType(
                *model,
                assembly->typeIndices[static_cast<std::size_t>(index)]);
            auto name = type ? toJavaString(environment, type->name) : nullptr;
            if (name == nullptr) {
                return static_cast<jobjectArray>(nullptr);
            }
            environment->SetObjectArrayElement(result, index, name);
            environment->DeleteLocalRef(name);
            if (environment->ExceptionCheck()) {
                return static_cast<jobjectArray>(nullptr);
            }
        }
        return result;
    });
}

jobject nativeSearchSymbols(
    JNIEnv* environment,
    jobject,
    jlong handle,
    jstring query,
    jboolean exactMatch,
    jboolean matchCase,
    jint offset,
    jint limit,
    jint knownTotalCount) {
    return guarded<jobject>(nullptr, [&] {
        if (offset < 0 || limit <= 0 || limit > kMaximumSymbolSearchPageSize ||
            knownTotalCount < -1) {
            return static_cast<jobject>(nullptr);
        }
        const auto model = registry().get(handle);
        const auto searchQuery = fromJavaString(environment, query);
        if (model == nullptr || !searchQuery || searchQuery->empty()) {
            return static_cast<jobject>(nullptr);
        }
        const auto runtime = registry().runtime(handle);
        const MethodAddressMatcher methodAddressMatcher =
            runtime == nullptr
                ? MethodAddressMatcher{}
                : MethodAddressMatcher{
                      [runtime](
                          const TypeMetadata& type,
                          const MethodMetadata& method,
                          std::uint64_t address) {
                          return runtime->methodMatchesAddress(type, method, address);
                      }};
        const auto page = searchMetadataSymbols(
            *model,
            *searchQuery,
            exactMatch == JNI_TRUE,
            matchCase == JNI_TRUE,
            static_cast<std::size_t>(offset),
            static_cast<std::size_t>(limit),
            knownTotalCount < 0,
            methodAddressMatcher);
        const auto totalCount = knownTotalCount >= 0
                                    ? static_cast<std::size_t>(knownTotalCount)
                                    : page.totalCount;
        if (totalCount > static_cast<std::size_t>(std::numeric_limits<jint>::max()) ||
            environment->PushLocalFrame(16) < 0) {
            return static_cast<jobject>(nullptr);
        }

        std::vector<jint> kinds;
        std::vector<jint> classIndices;
        std::vector<jint> memberIndices;
        std::vector<std::string> names;
        std::vector<std::string> assemblyNames;
        std::vector<std::string> ownerNames;
        kinds.reserve(page.items.size());
        classIndices.reserve(page.items.size());
        memberIndices.reserve(page.items.size());
        names.reserve(page.items.size());
        assemblyNames.reserve(page.items.size());
        ownerNames.reserve(page.items.size());
        for (const auto& item : page.items) {
            kinds.push_back(static_cast<jint>(item.kind));
            classIndices.push_back(item.typeIndex);
            memberIndices.push_back(item.memberIndex);
            names.push_back(item.name);
            assemblyNames.push_back(item.assemblyName);
            ownerNames.push_back(item.ownerName);
        }

        const auto pageClass = environment->FindClass(kNativeSymbolSearchPageClass);
        const auto constructor = pageClass == nullptr
                                     ? nullptr
                                     : environment->GetMethodID(
                                           pageClass,
                                           "<init>",
                                           "(I[I[I[I[Ljava/lang/String;[Ljava/lang/String;"
                                           "[Ljava/lang/String;)V");
        const auto javaKinds = toJavaIntArray(environment, kinds);
        const auto javaClassIndices = toJavaIntArray(environment, classIndices);
        const auto javaMemberIndices = toJavaIntArray(environment, memberIndices);
        const auto javaNames = toJavaStringArray(environment, names);
        const auto javaAssemblyNames = toJavaStringArray(environment, assemblyNames);
        const auto javaOwnerNames = toJavaStringArray(environment, ownerNames);
        if (constructor == nullptr || javaKinds == nullptr || javaClassIndices == nullptr ||
            javaMemberIndices == nullptr || javaNames == nullptr || javaAssemblyNames == nullptr ||
            javaOwnerNames == nullptr || environment->ExceptionCheck()) {
            environment->PopLocalFrame(nullptr);
            return static_cast<jobject>(nullptr);
        }
        const auto result = environment->NewObject(
            pageClass,
            constructor,
            static_cast<jint>(totalCount),
            javaKinds,
            javaClassIndices,
            javaMemberIndices,
            javaNames,
            javaAssemblyNames,
            javaOwnerNames);
        return environment->PopLocalFrame(environment->ExceptionCheck() ? nullptr : result);
    });
}

jint nativeNamespaceCount(JNIEnv*, jobject, jlong handle, jint assemblyIndex) {
    return guarded<jint>(kInvalidInt, [handle, assemblyIndex] {
        const auto model = registry().get(handle);
        const auto* assembly = model ? at(model->assemblies(), assemblyIndex) : nullptr;
        return assembly ? javaCount(&assembly->namespaces) : kInvalidInt;
    });
}

jstring nativeNamespaceName(
    JNIEnv* environment,
    jobject,
    jlong handle,
    jint assemblyIndex,
    jint index) {
    return guarded<jstring>(nullptr, [&] {
        const auto model = registry().get(handle);
        const auto* namespaze = model ? getNamespace(*model, assemblyIndex, index) : nullptr;
        return namespaze ? toJavaString(environment, namespaze->name) : nullptr;
    });
}

jint nativeClassCount(JNIEnv*, jobject, jlong handle, jint assemblyIndex, jint namespaceIndex) {
    return guarded<jint>(kInvalidInt, [handle, assemblyIndex, namespaceIndex] {
        const auto model = registry().get(handle);
        const auto* namespaze = model ? getNamespace(*model, assemblyIndex, namespaceIndex) : nullptr;
        return namespaze ? javaCount(&namespaze->typeIndices) : kInvalidInt;
    });
}

jstring nativeClassName(
    JNIEnv* environment,
    jobject,
    jlong handle,
    jint assemblyIndex,
    jint namespaceIndex,
    jint index) {
    return guarded<jstring>(nullptr, [&] {
        const auto model = registry().get(handle);
        const auto* type = model ? getTypeAt(*model, assemblyIndex, namespaceIndex, index) : nullptr;
        return type ? toJavaString(environment, type->name) : nullptr;
    });
}

jint nativeClassIndex(
    JNIEnv*,
    jobject,
    jlong handle,
    jint assemblyIndex,
    jint namespaceIndex,
    jint index) {
    return guarded<jint>(kInvalidInt, [handle, assemblyIndex, namespaceIndex, index] {
        const auto model = registry().get(handle);
        const auto* namespaze = model ? getNamespace(*model, assemblyIndex, namespaceIndex) : nullptr;
        const auto* classIndex = namespaze ? at(namespaze->typeIndices, index) : nullptr;
        return classIndex ? static_cast<jint>(*classIndex) : kInvalidInt;
    });
}

jlong nativeClassFlags(JNIEnv*, jobject, jlong handle, jint classIndex) {
    return guarded<jlong>(kInvalidLong, [handle, classIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        return type ? static_cast<jlong>(type->flags) : kInvalidLong;
    });
}

jlong nativeClassToken(JNIEnv*, jobject, jlong handle, jint classIndex) {
    return guarded<jlong>(kInvalidLong, [handle, classIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        return type ? static_cast<jlong>(type->token) : kInvalidLong;
    });
}

jlong nativeClassBitfield(JNIEnv*, jobject, jlong handle, jint classIndex) {
    return guarded<jlong>(kInvalidLong, [handle, classIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        return type ? static_cast<jlong>(type->bitfield) : kInvalidLong;
    });
}

jstring nativeClassDefinitionName(JNIEnv* environment, jobject, jlong handle, jint classIndex) {
    return guarded<jstring>(nullptr, [&] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        return type ? toJavaString(environment, type->name) : nullptr;
    });
}

jstring nativeClassNamespaceName(JNIEnv* environment, jobject, jlong handle, jint classIndex) {
    return guarded<jstring>(nullptr, [&] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        return type ? toJavaString(environment, type->namespaze) : nullptr;
    });
}

jstring nativeClassAssemblyName(JNIEnv* environment, jobject, jlong handle, jint classIndex) {
    return guarded<jstring>(nullptr, [&] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        return type ? toJavaString(environment, type->assemblyName) : nullptr;
    });
}

jint nativeClassAssemblyIndex(JNIEnv*, jobject, jlong handle, jint classIndex) {
    return guarded<jint>(kInvalidInt, [handle, classIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        return type ? static_cast<jint>(type->assemblyIndex) : kInvalidInt;
    });
}

jstring nativeClassParentTypeName(JNIEnv* environment, jobject, jlong handle, jint classIndex) {
    return guarded<jstring>(nullptr, [&] {
        const auto model = registry().get(handle);
        const auto runtime = registry().runtime(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto name = runtime && type && type->parentTypeIndex >= 0
                              ? runtime->referencedTypeName(type->parentTypeIndex)
                              : std::nullopt;
        return name ? toJavaString(environment, *name) : nullptr;
    });
}

jint nativeClassParentTypeIndex(JNIEnv*, jobject, jlong handle, jint classIndex) {
    return guarded<jint>(kInvalidInt, [handle, classIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        return type ? static_cast<jint>(type->parentTypeIndex) : kInvalidInt;
    });
}

jint nativeClassParentDefinitionIndex(JNIEnv*, jobject, jlong handle, jint classIndex) {
    return guarded<jint>(kInvalidInt, [handle, classIndex] {
        const auto model = registry().get(handle);
        const auto runtime = registry().runtime(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        if (type == nullptr || type->parentTypeIndex < 0) {
            return kInvalidInt;
        }
        const auto direct = model->typeDefinitionIndexForTypeIndex(type->parentTypeIndex);
        const auto resolved = direct ? direct : runtime
            ? runtime->referencedTypeDefinitionIndex(type->parentTypeIndex)
            : std::nullopt;
        return resolved ? static_cast<jint>(*resolved) : kInvalidInt;
    });
}

jstring nativeClassDeclaringTypeName(JNIEnv* environment, jobject, jlong handle, jint classIndex) {
    return guarded<jstring>(nullptr, [&] {
        const auto model = registry().get(handle);
        const auto runtime = registry().runtime(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto name = runtime && type && type->declaringTypeIndex >= 0
                              ? runtime->referencedTypeName(type->declaringTypeIndex)
                              : std::nullopt;
        return name ? toJavaString(environment, *name) : nullptr;
    });
}

jint nativeClassDeclaringTypeIndex(JNIEnv*, jobject, jlong handle, jint classIndex) {
    return guarded<jint>(kInvalidInt, [handle, classIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        return type ? static_cast<jint>(type->declaringTypeIndex) : kInvalidInt;
    });
}

jint nativeClassDeclaringDefinitionIndex(JNIEnv*, jobject, jlong handle, jint classIndex) {
    return guarded<jint>(kInvalidInt, [handle, classIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        return type ? static_cast<jint>(type->declaringTypeDefinitionIndex) : kInvalidInt;
    });
}

jlongArray nativeClassTypeSizes(JNIEnv* environment, jobject, jlong handle, jint classIndex) {
    return guarded<jlongArray>(nullptr, [&] {
        const auto model = registry().get(handle);
        const auto runtime = registry().runtime(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto sizes = runtime && type ? runtime->typeSizes(classIndex) : std::nullopt;
        if (!sizes) {
            return static_cast<jlongArray>(nullptr);
        }
        const std::array<jlong, 4> values{
            static_cast<jlong>(sizes->instanceSize),
            static_cast<jlong>(sizes->nativeSize),
            static_cast<jlong>(sizes->staticFieldsSize),
            static_cast<jlong>(sizes->threadStaticFieldsSize),
        };
        auto result = environment->NewLongArray(static_cast<jsize>(values.size()));
        if (result == nullptr) {
            return static_cast<jlongArray>(nullptr);
        }
        environment->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
        return environment->ExceptionCheck() ? static_cast<jlongArray>(nullptr) : result;
    });
}

jint nativeNestedTypeCount(JNIEnv*, jobject, jlong handle, jint classIndex) {
    return guarded<jint>(kInvalidInt, [handle, classIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        return type ? javaCount(&type->nestedTypeIndices) : kInvalidInt;
    });
}

jint nativeNestedTypeIndex(JNIEnv*, jobject, jlong handle, jint classIndex, jint nestedIndex) {
    return guarded<jint>(kInvalidInt, [handle, classIndex, nestedIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* nestedTypeIndex = type ? at(type->nestedTypeIndices, nestedIndex) : nullptr;
        return nestedTypeIndex ? static_cast<jint>(*nestedTypeIndex) : kInvalidInt;
    });
}

jstring nativeNestedTypeName(
    JNIEnv* environment,
    jobject,
    jlong handle,
    jint classIndex,
    jint nestedIndex) {
    return guarded<jstring>(nullptr, [&] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* nestedTypeIndex = type ? at(type->nestedTypeIndices, nestedIndex) : nullptr;
        const auto* nestedType = nestedTypeIndex ? getType(*model, *nestedTypeIndex) : nullptr;
        return nestedType ? toJavaString(environment, nestedType->name) : nullptr;
    });
}

jint nativeInterfaceCount(JNIEnv*, jobject, jlong handle, jint classIndex) {
    return guarded<jint>(kInvalidInt, [handle, classIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        return type ? javaCount(&type->interfaceTypeIndices) : kInvalidInt;
    });
}

jstring nativeInterfaceTypeName(
    JNIEnv* environment,
    jobject,
    jlong handle,
    jint classIndex,
    jint interfaceIndex) {
    return guarded<jstring>(nullptr, [&] {
        const auto model = registry().get(handle);
        const auto runtime = registry().runtime(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* typeIndex = type ? at(type->interfaceTypeIndices, interfaceIndex) : nullptr;
        const auto name = runtime && typeIndex
                              ? runtime->referencedTypeName(*typeIndex)
                              : std::nullopt;
        return name ? toJavaString(environment, *name) : nullptr;
    });
}

jint nativeInterfaceDefinitionIndex(
    JNIEnv*,
    jobject,
    jlong handle,
    jint classIndex,
    jint interfaceIndex) {
    return guarded<jint>(kInvalidInt, [handle, classIndex, interfaceIndex] {
        const auto model = registry().get(handle);
        const auto runtime = registry().runtime(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* typeIndex = type ? at(type->interfaceTypeIndices, interfaceIndex) : nullptr;
        if (typeIndex == nullptr) {
            return kInvalidInt;
        }
        const auto direct = model->typeDefinitionIndexForTypeIndex(*typeIndex);
        const auto resolved = direct ? direct : runtime
            ? runtime->referencedTypeDefinitionIndex(*typeIndex)
            : std::nullopt;
        return resolved ? static_cast<jint>(*resolved) : kInvalidInt;
    });
}

jint nativeInterfaceTypeIndex(
    JNIEnv*,
    jobject,
    jlong handle,
    jint classIndex,
    jint interfaceIndex) {
    return guarded<jint>(kInvalidInt, [handle, classIndex, interfaceIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* typeIndex = type ? at(type->interfaceTypeIndices, interfaceIndex) : nullptr;
        return typeIndex ? static_cast<jint>(*typeIndex) : kInvalidInt;
    });
}

jint nativeFieldCount(JNIEnv*, jobject, jlong handle, jint classIndex) {
    return guarded<jint>(kInvalidInt, [handle, classIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        return type ? javaCount(&type->fields) : kInvalidInt;
    });
}

jstring nativeFieldName(JNIEnv* environment, jobject, jlong handle, jint classIndex, jint fieldIndex) {
    return guarded<jstring>(nullptr, [&] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* field = type ? at(type->fields, fieldIndex) : nullptr;
        return field ? toJavaString(environment, field->name) : nullptr;
    });
}

jstring nativeFieldTypeName(JNIEnv* environment, jobject, jlong handle, jint classIndex, jint fieldIndex) {
    return guarded<jstring>(nullptr, [&] {
        const auto model = registry().get(handle);
        const auto runtime = registry().runtime(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* field = type ? at(type->fields, fieldIndex) : nullptr;
        const auto name = runtime && field ? runtime->fieldTypeName(*field) : std::nullopt;
        return name ? toJavaString(environment, *name) : nullptr;
    });
}

jint nativeFieldTypeIndex(JNIEnv*, jobject, jlong handle, jint classIndex, jint fieldIndex) {
    return guarded<jint>(kInvalidInt, [handle, classIndex, fieldIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* field = type ? at(type->fields, fieldIndex) : nullptr;
        return field ? static_cast<jint>(field->typeIndex) : kInvalidInt;
    });
}

jlong nativeFieldOffset(JNIEnv*, jobject, jlong handle, jint classIndex, jint fieldIndex) {
    return guarded<jlong>(kUnavailableFieldOffset, [handle, classIndex, fieldIndex] {
        const auto model = registry().get(handle);
        const auto runtime = registry().runtime(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* field = type ? at(type->fields, fieldIndex) : nullptr;
        const auto offset = runtime && field
                                ? runtime->fieldOffset(classIndex, static_cast<std::size_t>(fieldIndex))
                                : std::nullopt;
        return offset ? static_cast<jlong>(*offset) : kUnavailableFieldOffset;
    });
}

jint nativeFieldFlags(JNIEnv*, jobject, jlong handle, jint classIndex, jint fieldIndex) {
    return guarded<jint>(kInvalidInt, [handle, classIndex, fieldIndex] {
        const auto model = registry().get(handle);
        const auto runtime = registry().runtime(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* field = type ? at(type->fields, fieldIndex) : nullptr;
        const auto flags = runtime && field ? runtime->fieldFlags(*field) : std::nullopt;
        return flags ? static_cast<jint>(*flags) : kInvalidInt;
    });
}

jint nativePropertyCount(JNIEnv*, jobject, jlong handle, jint classIndex) {
    return guarded<jint>(kInvalidInt, [handle, classIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        return type ? javaCount(&type->properties) : kInvalidInt;
    });
}

jstring nativePropertyName(JNIEnv* environment, jobject, jlong handle, jint classIndex, jint propertyIndex) {
    return guarded<jstring>(nullptr, [&] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* property = type ? at(type->properties, propertyIndex) : nullptr;
        return property ? toJavaString(environment, property->name) : nullptr;
    });
}

jstring nativePropertyTypeName(
    JNIEnv* environment,
    jobject,
    jlong handle,
    jint classIndex,
    jint propertyIndex) {
    return guarded<jstring>(nullptr, [&] {
        const auto model = registry().get(handle);
        const auto runtime = registry().runtime(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* property = type ? at(type->properties, propertyIndex) : nullptr;
        const auto name = runtime && type && property
                              ? runtime->propertyTypeName(*type, *property)
                              : std::nullopt;
        return name ? toJavaString(environment, *name) : nullptr;
    });
}

jint nativePropertyTypeIndex(JNIEnv*, jobject, jlong handle, jint classIndex, jint propertyIndex) {
    return guarded<jint>(kInvalidInt, [handle, classIndex, propertyIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* property = type ? at(type->properties, propertyIndex) : nullptr;
        const auto typeIndex = propertyTypeIndex(type, property);
        return typeIndex ? static_cast<jint>(*typeIndex) : kInvalidInt;
    });
}

jint nativePropertyGetterFlags(JNIEnv*, jobject, jlong handle, jint classIndex, jint propertyIndex) {
    return guarded<jint>(kInvalidInt, [handle, classIndex, propertyIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* property = type ? at(type->properties, propertyIndex) : nullptr;
        const auto flags = property ? accessorFlags(type, property->getterIndex) : std::nullopt;
        return flags ? static_cast<jint>(*flags) : kInvalidInt;
    });
}

jint nativePropertySetterFlags(JNIEnv*, jobject, jlong handle, jint classIndex, jint propertyIndex) {
    return guarded<jint>(kInvalidInt, [handle, classIndex, propertyIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* property = type ? at(type->properties, propertyIndex) : nullptr;
        const auto flags = property ? accessorFlags(type, property->setterIndex) : std::nullopt;
        return flags ? static_cast<jint>(*flags) : kInvalidInt;
    });
}

jlong nativePropertyAttributes(JNIEnv*, jobject, jlong handle, jint classIndex, jint propertyIndex) {
    return guarded<jlong>(kInvalidLong, [handle, classIndex, propertyIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* property = type ? at(type->properties, propertyIndex) : nullptr;
        return property ? static_cast<jlong>(property->attributes) : kInvalidLong;
    });
}

jlong nativePropertyToken(JNIEnv*, jobject, jlong handle, jint classIndex, jint propertyIndex) {
    return guarded<jlong>(kInvalidLong, [handle, classIndex, propertyIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* property = type ? at(type->properties, propertyIndex) : nullptr;
        return property ? static_cast<jlong>(property->token) : kInvalidLong;
    });
}

jint nativeEventCount(JNIEnv*, jobject, jlong handle, jint classIndex) {
    return guarded<jint>(kInvalidInt, [handle, classIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        return type ? javaCount(&type->events) : kInvalidInt;
    });
}

jstring nativeEventName(JNIEnv* environment, jobject, jlong handle, jint classIndex, jint eventIndex) {
    return guarded<jstring>(nullptr, [&] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* event = type ? at(type->events, eventIndex) : nullptr;
        return event ? toJavaString(environment, event->name) : nullptr;
    });
}

jstring nativeEventTypeName(
    JNIEnv* environment,
    jobject,
    jlong handle,
    jint classIndex,
    jint eventIndex) {
    return guarded<jstring>(nullptr, [&] {
        const auto model = registry().get(handle);
        const auto runtime = registry().runtime(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* event = type ? at(type->events, eventIndex) : nullptr;
        const auto name = runtime && event ? runtime->eventTypeName(*event) : std::nullopt;
        return name ? toJavaString(environment, *name) : nullptr;
    });
}

jint nativeEventTypeIndex(JNIEnv*, jobject, jlong handle, jint classIndex, jint eventIndex) {
    return guarded<jint>(kInvalidInt, [handle, classIndex, eventIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* event = type ? at(type->events, eventIndex) : nullptr;
        return event ? static_cast<jint>(event->typeIndex) : kInvalidInt;
    });
}

jint nativeEventAddFlags(JNIEnv*, jobject, jlong handle, jint classIndex, jint eventIndex) {
    return guarded<jint>(kInvalidInt, [handle, classIndex, eventIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* event = type ? at(type->events, eventIndex) : nullptr;
        const auto flags = event ? accessorFlags(type, event->addIndex) : std::nullopt;
        return flags ? static_cast<jint>(*flags) : kInvalidInt;
    });
}

jint nativeEventRemoveFlags(JNIEnv*, jobject, jlong handle, jint classIndex, jint eventIndex) {
    return guarded<jint>(kInvalidInt, [handle, classIndex, eventIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* event = type ? at(type->events, eventIndex) : nullptr;
        const auto flags = event ? accessorFlags(type, event->removeIndex) : std::nullopt;
        return flags ? static_cast<jint>(*flags) : kInvalidInt;
    });
}

jint nativeEventRaiseFlags(JNIEnv*, jobject, jlong handle, jint classIndex, jint eventIndex) {
    return guarded<jint>(kInvalidInt, [handle, classIndex, eventIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* event = type ? at(type->events, eventIndex) : nullptr;
        const auto flags = event ? accessorFlags(type, event->raiseIndex) : std::nullopt;
        return flags ? static_cast<jint>(*flags) : kInvalidInt;
    });
}

jlong nativeEventToken(JNIEnv*, jobject, jlong handle, jint classIndex, jint eventIndex) {
    return guarded<jlong>(kInvalidLong, [handle, classIndex, eventIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* event = type ? at(type->events, eventIndex) : nullptr;
        return event ? static_cast<jlong>(event->token) : kInvalidLong;
    });
}

jint nativeMethodCount(JNIEnv*, jobject, jlong handle, jint classIndex) {
    return guarded<jint>(kInvalidInt, [handle, classIndex] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        return type ? javaCount(&type->methods) : kInvalidInt;
    });
}

jstring nativeMethodName(JNIEnv* environment, jobject, jlong handle, jint classIndex, jint methodIndex) {
    return guarded<jstring>(nullptr, [&] {
        const auto model = registry().get(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* method = type ? at(type->methods, methodIndex) : nullptr;
        return method ? toJavaString(environment, method->name) : nullptr;
    });
}

jstring nativeMethodSignature(JNIEnv* environment, jobject, jlong handle, jint classIndex, jint methodIndex) {
    return guarded<jstring>(nullptr, [&] {
        const auto model = registry().get(handle);
        const auto runtime = registry().runtime(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* method = type ? at(type->methods, methodIndex) : nullptr;
        if (method == nullptr) {
            return static_cast<jstring>(nullptr);
        }
        const auto signature = runtime ? runtime->methodSignature(*method) : std::nullopt;
        return signature ? toJavaString(environment, *signature) : nullptr;
    });
}

jlong nativeMethodAddress(JNIEnv*, jobject, jlong handle, jint classIndex, jint methodIndex) {
    return guarded<jlong>(kInvalidLong, [handle, classIndex, methodIndex] {
        const auto model = registry().get(handle);
        const auto runtime = registry().runtime(handle);
        const auto* type = model ? getType(*model, classIndex) : nullptr;
        const auto* method = type ? at(type->methods, methodIndex) : nullptr;
        const auto address = runtime && type && method
                                 ? runtime->methodAddress(*type, *method)
                                 : std::nullopt;
        return address && *address <= static_cast<std::uint64_t>(std::numeric_limits<jlong>::max())
                   ? static_cast<jlong>(*address)
                   : kInvalidLong;
    });
}

template <typename Page, typename Loader, typename Converter>
jobject nativeMethodAnalysisPage(
    JNIEnv* environment,
    jlong handle,
    jint classIndex,
    jint methodIndex,
    jint offset,
    jint limit,
    Loader&& loader,
    Converter&& converter) {
    return guarded<jobject>(nullptr, [&] {
        const auto model = registry().get(handle);
        if (!validMethodAnalysisRequest(
                model.get(),
                classIndex,
                methodIndex,
                offset,
                limit)) {
            return static_cast<jobject>(nullptr);
        }
        const auto runtime = registry().runtime(handle);
        const auto page = runtime == nullptr
                              ? Page{}
                              : loader(
                                    *runtime,
                                    static_cast<std::size_t>(offset),
                                    static_cast<std::size_t>(limit));
        return converter(environment, page);
    });
}

jobject nativeMethodCalls(
    JNIEnv* environment,
    jobject,
    jlong handle,
    jint classIndex,
    jint methodIndex,
    jint offset,
    jint limit) {
    return nativeMethodAnalysisPage<RuntimeMetadataResolver::MethodReferencePage>(
        environment,
        handle,
        classIndex,
        methodIndex,
        offset,
        limit,
        [classIndex, methodIndex](
            const RuntimeMetadataResolver& runtime,
            std::size_t pageOffset,
            std::size_t pageLimit) {
            return runtime.methodCalls(
                classIndex,
                methodIndex,
                pageOffset,
                pageLimit);
        },
        toJavaMethodReferencePage);
}

jobject nativeMethodCallers(
    JNIEnv* environment,
    jobject,
    jlong handle,
    jint classIndex,
    jint methodIndex,
    jint offset,
    jint limit) {
    return nativeMethodAnalysisPage<RuntimeMetadataResolver::MethodReferencePage>(
        environment,
        handle,
        classIndex,
        methodIndex,
        offset,
        limit,
        [classIndex, methodIndex](
            const RuntimeMetadataResolver& runtime,
            std::size_t pageOffset,
            std::size_t pageLimit) {
            return runtime.methodCallers(
                classIndex,
                methodIndex,
                pageOffset,
                pageLimit);
        },
        toJavaMethodReferencePage);
}

jobject nativeMethodInstructions(
    JNIEnv* environment,
    jobject,
    jlong handle,
    jint classIndex,
    jint methodIndex,
    jint offset,
    jint limit) {
    return nativeMethodAnalysisPage<RuntimeMetadataResolver::InstructionPage>(
        environment,
        handle,
        classIndex,
        methodIndex,
        offset,
        limit,
        [classIndex, methodIndex](
            const RuntimeMetadataResolver& runtime,
            std::size_t pageOffset,
            std::size_t pageLimit) {
            return runtime.methodInstructions(
                classIndex,
                methodIndex,
                pageOffset,
                pageLimit);
        },
        toJavaInstructionPage);
}

jboolean nativeIsExecutableModuleAddress(JNIEnv*, jobject, jlong handle, jlong address) {
    return guarded<jboolean>(JNI_FALSE, [handle, address] {
        if (address <= 0) {
            return static_cast<jboolean>(JNI_FALSE);
        }
        const auto runtime = registry().runtime(handle);
        return static_cast<jboolean>(
            runtime && runtime->isExecutableModuleAddress(static_cast<std::uint64_t>(address))
                ? JNI_TRUE
                : JNI_FALSE);
    });
}

jbyteArray nativeReadMemory(JNIEnv* environment, jobject, jint pid, jlong address, jint size) {
    return guarded<jbyteArray>(nullptr, [&] {
        if (address <= 0 || size < 0 || static_cast<std::size_t>(size) > kMaximumMemoryTransferBytes) {
            return static_cast<jbyteArray>(nullptr);
        }
        std::vector<std::uint8_t> bytes(static_cast<std::size_t>(size));
        if (size > 0 && !readProcessMemory(pid, static_cast<std::uint64_t>(address), bytes.data(), bytes.size())) {
            return static_cast<jbyteArray>(nullptr);
        }
        auto result = environment->NewByteArray(size);
        if (result == nullptr) {
            return static_cast<jbyteArray>(nullptr);
        }
        if (size > 0) {
            environment->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte*>(bytes.data()));
            if (environment->ExceptionCheck()) {
                return static_cast<jbyteArray>(nullptr);
            }
        }
        return result;
    });
}

jint nativeWriteMemory(JNIEnv* environment, jobject, jint pid, jlong address, jbyteArray bytes) {
    return guarded<jint>(kInvalidInt, [&] {
        if (address <= 0 || bytes == nullptr) {
            return kInvalidInt;
        }
        const auto size = environment->GetArrayLength(bytes);
        if (size < 0 || static_cast<std::size_t>(size) > kMaximumMemoryTransferBytes) {
            return kInvalidInt;
        }
        std::vector<std::uint8_t> source(static_cast<std::size_t>(size));
        if (size > 0) {
            environment->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(source.data()));
            if (environment->ExceptionCheck() ||
                !writeProcessMemory(pid, static_cast<std::uint64_t>(address), source.data(), source.size())) {
                return kInvalidInt;
            }
        }
        return size;
    });
}

const JNINativeMethod kMethods[] = {
    {"scanProcessIds", "()[I", reinterpret_cast<void*>(nativeScanProcessIds)},
    {"processName", "(I)Ljava/lang/String;", reinterpret_cast<void*>(nativeProcessName)},
    {"processStartTicks", "(I)J", reinterpret_cast<void*>(nativeProcessStartTicks)},
    {"moduleBase", "(ILjava/lang/String;)J", reinterpret_cast<void*>(nativeModuleBase)},
    {"openMetadata", "([BZ)J", reinterpret_cast<void*>(nativeOpenMetadata)},
    {"probeMetadataSize", "([BJZ)J", reinterpret_cast<void*>(nativeProbeMetadataSize)},
    {"closeMetadata", "(J)V", reinterpret_cast<void*>(nativeCloseMetadata)},
    {"attachRuntime", "(JI)Z", reinterpret_cast<void*>(nativeAttachRuntime)},
    {"assemblyCount", "(J)I", reinterpret_cast<void*>(nativeAssemblyCount)},
    {"assemblyName", "(JI)Ljava/lang/String;", reinterpret_cast<void*>(nativeAssemblyName)},
    {"assemblyTypeIndices", "(JI)[I", reinterpret_cast<void*>(nativeAssemblyTypeIndices)},
    {"assemblyTypeNames", "(JI)[Ljava/lang/String;", reinterpret_cast<void*>(nativeAssemblyTypeNames)},
    {"searchSymbols", "(JLjava/lang/String;ZZIII)Ldev/ruri/il2cppmanager/nativebridge/NativeSymbolSearchPage;", reinterpret_cast<void*>(nativeSearchSymbols)},
    {"namespaceCount", "(JI)I", reinterpret_cast<void*>(nativeNamespaceCount)},
    {"namespaceName", "(JII)Ljava/lang/String;", reinterpret_cast<void*>(nativeNamespaceName)},
    {"classCount", "(JII)I", reinterpret_cast<void*>(nativeClassCount)},
    {"className", "(JIII)Ljava/lang/String;", reinterpret_cast<void*>(nativeClassName)},
    {"classIndex", "(JIII)I", reinterpret_cast<void*>(nativeClassIndex)},
    {"classFlags", "(JI)J", reinterpret_cast<void*>(nativeClassFlags)},
    {"classToken", "(JI)J", reinterpret_cast<void*>(nativeClassToken)},
    {"classBitfield", "(JI)J", reinterpret_cast<void*>(nativeClassBitfield)},
    {"classDefinitionName", "(JI)Ljava/lang/String;", reinterpret_cast<void*>(nativeClassDefinitionName)},
    {"classNamespaceName", "(JI)Ljava/lang/String;", reinterpret_cast<void*>(nativeClassNamespaceName)},
    {"classAssemblyName", "(JI)Ljava/lang/String;", reinterpret_cast<void*>(nativeClassAssemblyName)},
    {"classAssemblyIndex", "(JI)I", reinterpret_cast<void*>(nativeClassAssemblyIndex)},
    {"classParentTypeIndex", "(JI)I", reinterpret_cast<void*>(nativeClassParentTypeIndex)},
    {"classParentTypeName", "(JI)Ljava/lang/String;", reinterpret_cast<void*>(nativeClassParentTypeName)},
    {"classParentDefinitionIndex", "(JI)I", reinterpret_cast<void*>(nativeClassParentDefinitionIndex)},
    {"classDeclaringTypeIndex", "(JI)I", reinterpret_cast<void*>(nativeClassDeclaringTypeIndex)},
    {"classDeclaringTypeName", "(JI)Ljava/lang/String;", reinterpret_cast<void*>(nativeClassDeclaringTypeName)},
    {"classDeclaringDefinitionIndex", "(JI)I", reinterpret_cast<void*>(nativeClassDeclaringDefinitionIndex)},
    {"classTypeSizes", "(JI)[J", reinterpret_cast<void*>(nativeClassTypeSizes)},
    {"nestedTypeCount", "(JI)I", reinterpret_cast<void*>(nativeNestedTypeCount)},
    {"nestedTypeIndex", "(JII)I", reinterpret_cast<void*>(nativeNestedTypeIndex)},
    {"nestedTypeName", "(JII)Ljava/lang/String;", reinterpret_cast<void*>(nativeNestedTypeName)},
    {"interfaceCount", "(JI)I", reinterpret_cast<void*>(nativeInterfaceCount)},
    {"interfaceTypeIndex", "(JII)I", reinterpret_cast<void*>(nativeInterfaceTypeIndex)},
    {"interfaceTypeName", "(JII)Ljava/lang/String;", reinterpret_cast<void*>(nativeInterfaceTypeName)},
    {"interfaceDefinitionIndex", "(JII)I", reinterpret_cast<void*>(nativeInterfaceDefinitionIndex)},
    {"fieldCount", "(JI)I", reinterpret_cast<void*>(nativeFieldCount)},
    {"fieldName", "(JII)Ljava/lang/String;", reinterpret_cast<void*>(nativeFieldName)},
    {"fieldTypeIndex", "(JII)I", reinterpret_cast<void*>(nativeFieldTypeIndex)},
    {"fieldTypeName", "(JII)Ljava/lang/String;", reinterpret_cast<void*>(nativeFieldTypeName)},
    {"fieldOffset", "(JII)J", reinterpret_cast<void*>(nativeFieldOffset)},
    {"fieldFlags", "(JII)I", reinterpret_cast<void*>(nativeFieldFlags)},
    {"propertyCount", "(JI)I", reinterpret_cast<void*>(nativePropertyCount)},
    {"propertyName", "(JII)Ljava/lang/String;", reinterpret_cast<void*>(nativePropertyName)},
    {"propertyTypeIndex", "(JII)I", reinterpret_cast<void*>(nativePropertyTypeIndex)},
    {"propertyTypeName", "(JII)Ljava/lang/String;", reinterpret_cast<void*>(nativePropertyTypeName)},
    {"propertyGetterFlags", "(JII)I", reinterpret_cast<void*>(nativePropertyGetterFlags)},
    {"propertySetterFlags", "(JII)I", reinterpret_cast<void*>(nativePropertySetterFlags)},
    {"propertyAttributes", "(JII)J", reinterpret_cast<void*>(nativePropertyAttributes)},
    {"propertyToken", "(JII)J", reinterpret_cast<void*>(nativePropertyToken)},
    {"eventCount", "(JI)I", reinterpret_cast<void*>(nativeEventCount)},
    {"eventName", "(JII)Ljava/lang/String;", reinterpret_cast<void*>(nativeEventName)},
    {"eventTypeIndex", "(JII)I", reinterpret_cast<void*>(nativeEventTypeIndex)},
    {"eventTypeName", "(JII)Ljava/lang/String;", reinterpret_cast<void*>(nativeEventTypeName)},
    {"eventAddFlags", "(JII)I", reinterpret_cast<void*>(nativeEventAddFlags)},
    {"eventRemoveFlags", "(JII)I", reinterpret_cast<void*>(nativeEventRemoveFlags)},
    {"eventRaiseFlags", "(JII)I", reinterpret_cast<void*>(nativeEventRaiseFlags)},
    {"eventToken", "(JII)J", reinterpret_cast<void*>(nativeEventToken)},
    {"methodCount", "(JI)I", reinterpret_cast<void*>(nativeMethodCount)},
    {"methodName", "(JII)Ljava/lang/String;", reinterpret_cast<void*>(nativeMethodName)},
    {"methodSignature", "(JII)Ljava/lang/String;", reinterpret_cast<void*>(nativeMethodSignature)},
    {"methodAddress", "(JII)J", reinterpret_cast<void*>(nativeMethodAddress)},
    {"methodCalls", "(JIIII)Ldev/ruri/il2cppmanager/nativebridge/NativeMethodReferencePage;", reinterpret_cast<void*>(nativeMethodCalls)},
    {"methodCallers", "(JIIII)Ldev/ruri/il2cppmanager/nativebridge/NativeMethodReferencePage;", reinterpret_cast<void*>(nativeMethodCallers)},
    {"methodInstructions", "(JIIII)Ldev/ruri/il2cppmanager/nativebridge/NativeInstructionPage;", reinterpret_cast<void*>(nativeMethodInstructions)},
    {"isExecutableModuleAddress", "(JJ)Z", reinterpret_cast<void*>(nativeIsExecutableModuleAddress)},
    {"readMemory", "(IJI)[B", reinterpret_cast<void*>(nativeReadMemory)},
    {"writeMemory", "(IJ[B)I", reinterpret_cast<void*>(nativeWriteMemory)},
};

}
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* virtualMachine, void*) {
    JNIEnv* environment = nullptr;
    if (virtualMachine == nullptr ||
        virtualMachine->GetEnv(reinterpret_cast<void**>(&environment), JNI_VERSION_1_6) != JNI_OK ||
        environment == nullptr) {
        return JNI_ERR;
    }

    const auto nativeEngine = environment->FindClass(il2cppmanager::kNativeEngineClass);
    if (nativeEngine == nullptr) {
        return JNI_ERR;
    }
    const auto status = environment->RegisterNatives(
        nativeEngine,
        il2cppmanager::kMethods,
        static_cast<jint>(sizeof(il2cppmanager::kMethods) / sizeof(il2cppmanager::kMethods[0])));
    environment->DeleteLocalRef(nativeEngine);
    return status == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}
