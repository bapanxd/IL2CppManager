#include "complemented_counted_metadata.h"

#include <algorithm>
#include <array>
#include <limits>
#include <utility>
#include <vector>

namespace il2cppmanager {
namespace {

constexpr std::uint32_t kMetadataMagic = 0xFAB11BAFU;
constexpr std::int32_t kNormalizedVersion = 29;
constexpr std::size_t kDirectorySectionCount = 31;
constexpr std::size_t kDirectoryEntrySize = 12;
constexpr std::size_t kHeaderPrefixSize = 8;
constexpr std::size_t kHeaderSize =
    kHeaderPrefixSize + kDirectorySectionCount * kDirectoryEntrySize;
constexpr std::size_t kMaximumMetadataBytes = 128U * 1024U * 1024U;
constexpr std::uint8_t kNoDirectoryIndex = std::numeric_limits<std::uint8_t>::max();

constexpr CountedMetadataLayout kLayout{
    {2, 2, 2, 4, 4, 4, 4, 4, 4, 4, 4},
    {
        76,
        8,
        10,
        12,
        14,
        16,
        20,
        24,
        28,
        32,
        36,
        40,
        44,
        48,
        52,
        54,
        56,
        58,
        60,
        62,
        64,
        66,
        68,
        72,
    },
    {30, 4, 6, 12, 16, 18, 22, 28},
    {10, 4, 6},
    {10, 4, 8},
    {20, 4, 8, 12, 16},
    {22, 4, 6, 10, 14, 18},
    {14, 0, 2, 6, 8, 10, 12},
    {16, 4, 4, 8, 4, 12},
    {36, 8, 10},
    {68, 20},
    {6, 0, 2},
    4,
    2,
    4,
    2,
    true,
    true,
};

class Reader final {
public:
    Reader(const std::uint8_t* bytes, std::size_t size) noexcept
        : bytes_(bytes), size_(size) {}

    bool contains(std::size_t offset, std::size_t length) const noexcept {
        return offset <= size_ && length <= size_ - offset;
    }

    bool readU16(std::size_t offset, std::uint16_t& value) const noexcept {
        if (!contains(offset, sizeof(value))) {
            return false;
        }
        value = static_cast<std::uint16_t>(bytes_[offset]) |
                static_cast<std::uint16_t>(bytes_[offset + 1]) << 8U;
        return true;
    }

    bool readI16(std::size_t offset, std::int16_t& value) const noexcept {
        std::uint16_t raw = 0;
        if (!readU16(offset, raw)) {
            return false;
        }
        if (raw <= static_cast<std::uint16_t>(std::numeric_limits<std::int16_t>::max())) {
            value = static_cast<std::int16_t>(raw);
        } else {
            value = static_cast<std::int16_t>(
                -1 - static_cast<std::int32_t>(
                    std::numeric_limits<std::uint16_t>::max() - raw));
        }
        return true;
    }

    bool readU32(std::size_t offset, std::uint32_t& value) const noexcept {
        if (!contains(offset, sizeof(value))) {
            return false;
        }
        value = static_cast<std::uint32_t>(bytes_[offset]) |
                static_cast<std::uint32_t>(bytes_[offset + 1]) << 8U |
                static_cast<std::uint32_t>(bytes_[offset + 2]) << 16U |
                static_cast<std::uint32_t>(bytes_[offset + 3]) << 24U;
        return true;
    }

    bool readI32(std::size_t offset, std::int32_t& value) const noexcept {
        std::uint32_t raw = 0;
        if (!readU32(offset, raw)) {
            return false;
        }
        if (raw <= static_cast<std::uint32_t>(std::numeric_limits<std::int32_t>::max())) {
            value = static_cast<std::int32_t>(raw);
        } else {
            value = -1 - static_cast<std::int32_t>(
                std::numeric_limits<std::uint32_t>::max() - raw);
        }
        return true;
    }

    bool readIndex(
        std::size_t offset,
        std::size_t width,
        std::int32_t& value) const noexcept {
        if (width == 2) {
            std::uint16_t raw = 0;
            if (!readU16(offset, raw)) {
                return false;
            }
            value = raw == std::numeric_limits<std::uint16_t>::max()
                ? -1
                : static_cast<std::int32_t>(raw);
            return true;
        }
        if (width == 4) {
            return readI32(offset, value);
        }
        return false;
    }

    const std::uint8_t* at(std::size_t offset) const noexcept {
        return bytes_ + offset;
    }

private:
    const std::uint8_t* bytes_;
    std::size_t size_;
};

struct Directory {
    std::array<CountedMetadataSection, kDirectorySectionCount> sections{};
    ComplementedCountedMetadataProbe probe;
};

bool checkedAdd(std::size_t left, std::size_t right, std::size_t& result) noexcept {
    if (right > std::numeric_limits<std::size_t>::max() - left) {
        return false;
    }
    result = left + right;
    return true;
}

bool alignDirectoryOffset(std::size_t value, std::size_t& aligned) noexcept {
    std::size_t padded = 0;
    if (!checkedAdd(value, 3, padded)) {
        return false;
    }
    aligned = padded & ~std::size_t{3};
    return true;
}

std::optional<Directory> parseDirectory(
    const std::uint8_t* bytes,
    std::size_t headerBytesSize,
    std::size_t availableBytes) noexcept {
    if (bytes == nullptr || headerBytesSize < kHeaderSize ||
        availableBytes < kHeaderSize || availableBytes > kMaximumMetadataBytes) {
        return std::nullopt;
    }

    const Reader reader(bytes, headerBytesSize);
    std::uint32_t magic = 0;
    std::uint32_t rawVersion = 0;
    if (!reader.readU32(0, magic) || magic != kMetadataMagic ||
        !reader.readU32(sizeof(std::uint32_t), rawVersion) ||
        (rawVersion & 0x80000000U) == 0 ||
        ~rawVersion != static_cast<std::uint32_t>(kNormalizedVersion)) {
        return std::nullopt;
    }

    Directory directory;
    std::size_t previousEnd = kHeaderSize;
    bool sawNonEmpty = false;
    for (std::size_t index = 0; index < kDirectorySectionCount; ++index) {
        const auto descriptor = kHeaderPrefixSize + index * kDirectoryEntrySize;
        std::int32_t signedOffset = 0;
        std::int32_t signedSize = 0;
        std::int32_t signedCount = 0;
        if (!reader.readI32(descriptor, signedOffset) ||
            !reader.readI32(descriptor + 4, signedSize) ||
            !reader.readI32(descriptor + 8, signedCount) ||
            signedOffset < 0 || signedSize < 0 || signedCount < 0) {
            return std::nullopt;
        }

        const auto offset = static_cast<std::size_t>(signedOffset);
        const auto size = static_cast<std::size_t>(signedSize);
        const auto count = static_cast<std::size_t>(signedCount);
        std::size_t expectedOffset = 0;
        std::size_t end = 0;
        if (!alignDirectoryOffset(previousEnd, expectedOffset) || offset != expectedOffset ||
            !checkedAdd(offset, size, end) || end > availableBytes ||
            ((size == 0) != (count == 0)) || count > size ||
            (size > 0 && offset < kHeaderSize)) {
            return std::nullopt;
        }

        directory.sections[index] = {
            static_cast<std::uint8_t>(index),
            offset,
            size,
            count,
        };
        sawNonEmpty = sawNonEmpty || size > 0;
        previousEnd = end;
    }

    if (!sawNonEmpty || previousEnd > kMaximumMetadataBytes) {
        return std::nullopt;
    }
    directory.probe = {kNormalizedVersion, previousEnd};
    return directory;
}

CountedMetadataSection emptySection() noexcept {
    return {kNoDirectoryIndex, 0, 0, 0};
}

bool isShape(
    const CountedMetadataSection& section,
    std::size_t count,
    std::size_t recordSize) noexcept {
    return count > 0 && section.count == count && section.size % count == 0 &&
           section.size / count == recordSize;
}

std::optional<CountedMetadataSection> selectUniqueSection(
    const Directory& directory,
    std::size_t count,
    std::size_t recordSize,
    std::array<bool, kDirectorySectionCount>& used) noexcept {
    if (count == 0) {
        return emptySection();
    }
    const CountedMetadataSection* accepted = nullptr;
    for (const auto& section : directory.sections) {
        if (used[section.directoryIndex] || !isShape(section, count, recordSize)) {
            continue;
        }
        if (accepted != nullptr) {
            return std::nullopt;
        }
        accepted = &section;
    }
    if (accepted == nullptr) {
        return std::nullopt;
    }
    used[accepted->directoryIndex] = true;
    return *accepted;
}

struct OwnedRange {
    std::size_t start;
    std::size_t count;
    std::int32_t owner;
};

struct RangeStats {
    std::size_t claimedCount = 0;
    std::size_t maximumEnd = 0;
};

bool validateRanges(
    std::vector<OwnedRange> ranges,
    std::size_t total,
    bool allowHoles,
    std::vector<std::int32_t>* owners = nullptr) {
    std::sort(
        ranges.begin(),
        ranges.end(),
        [](const OwnedRange& left, const OwnedRange& right) {
            return left.start < right.start;
        });
    if (total > 0 && (ranges.empty() || ranges.front().start != 0)) {
        return false;
    }
    std::size_t cursor = 0;
    if (owners != nullptr) {
        owners->assign(total, -1);
    }
    for (const auto& range : ranges) {
        std::size_t end = 0;
        if (range.start > total || range.count > total - range.start ||
            !checkedAdd(range.start, range.count, end) ||
            range.start < cursor || (!allowHoles && range.start != cursor)) {
            return false;
        }
        if (owners != nullptr) {
            std::fill(
                owners->begin() + static_cast<std::ptrdiff_t>(range.start),
                owners->begin() + static_cast<std::ptrdiff_t>(end),
                range.owner);
        }
        cursor = end;
    }
    return cursor == total;
}

}

std::optional<ComplementedCountedMetadataProbe> probeComplementedCountedMetadata(
    const std::uint8_t* headerBytes,
    std::size_t headerBytesSize,
    std::size_t availableBytes) noexcept {
    try {
        const auto directory = parseDirectory(headerBytes, headerBytesSize, availableBytes);
        return directory ? std::optional(directory->probe) : std::nullopt;
    } catch (...) {
        return std::nullopt;
    }
}

}

namespace il2cppmanager {
namespace {

enum class TypeRangeRole : std::size_t {
    Field,
    Method,
    Event,
    Property,
    NestedType,
    Interface,
    VtableMethod,
    InterfaceOffset,
    Count,
};

constexpr std::array<std::size_t, static_cast<std::size_t>(TypeRangeRole::Count)>
    kTypeRangeStarts{{20, 24, 28, 32, 36, 40, 44, 48}};
constexpr std::array<std::size_t, static_cast<std::size_t>(TypeRangeRole::Count)>
    kTypeRangeCounts{{56, 52, 58, 54, 60, 64, 62, 66}};

struct GenericReference {
    std::int32_t index;
    std::int32_t owner;
    bool isMethod;
};

struct TypeFacts {
    std::int32_t byValueTypeIndex;
    std::int32_t declaringTypeIndex;
    std::int32_t genericContainerIndex;
    std::uint16_t methodCount;
};

struct TypeAnalysis {
    std::array<RangeStats, static_cast<std::size_t>(TypeRangeRole::Count)> ranges{};
    std::vector<TypeFacts> facts;
    std::vector<std::int32_t> stringReferences;
    std::vector<GenericReference> genericReferences;
};

bool recordOffset(
    const CountedMetadataSection& section,
    std::size_t index,
    std::size_t recordSize,
    std::size_t& offset) noexcept {
    if (index >= section.count || recordSize == 0 ||
        index > std::numeric_limits<std::size_t>::max() / recordSize) {
        return false;
    }
    const auto relative = index * recordSize;
    return relative <= section.size && recordSize <= section.size - relative &&
           checkedAdd(section.offset, relative, offset);
}

bool addCount(std::size_t value, std::size_t& total) noexcept {
    std::size_t result = 0;
    if (!checkedAdd(total, value, result)) {
        return false;
    }
    total = result;
    return true;
}

bool accumulateRange(
    std::int32_t start,
    std::size_t count,
    RangeStats& stats) noexcept {
    if (count == 0) {
        return start >= -1;
    }
    if (start < 0) {
        return false;
    }
    const auto unsignedStart = static_cast<std::size_t>(start);
    std::size_t end = 0;
    return checkedAdd(unsignedStart, count, end) &&
           addCount(count, stats.claimedCount) &&
           (stats.maximumEnd = std::max(stats.maximumEnd, end), true);
}

std::optional<TypeAnalysis> analyzeTypes(
    const Reader& reader,
    const CountedMetadataSection& section) {
    if (!isShape(section, section.count, kLayout.type.size) || section.count == 0 ||
        section.count > std::numeric_limits<std::uint16_t>::max()) {
        return std::nullopt;
    }

    TypeAnalysis analysis;
    analysis.facts.reserve(section.count);
    analysis.stringReferences.reserve(section.count * 2U);
    std::vector<std::uint8_t> usedByValueTypeIndices(
        static_cast<std::size_t>(std::numeric_limits<std::uint16_t>::max()) + 1U);

    for (std::size_t index = 0; index < section.count; ++index) {
        std::size_t offset = 0;
        std::int32_t nameIndex = 0;
        std::int32_t namespaceIndex = 0;
        std::int32_t byValueTypeIndex = -1;
        std::int32_t declaringTypeIndex = -1;
        std::int32_t parentTypeIndex = -1;
        std::int32_t genericContainerIndex = -1;
        if (!recordOffset(section, index, kLayout.type.size, offset) ||
            !reader.readI32(offset, nameIndex) || nameIndex < 0 ||
            !reader.readI32(offset + 4, namespaceIndex) || namespaceIndex < 0 ||
            !reader.readIndex(
                offset + kLayout.type.byValueTypeIndex,
                kLayout.widths.type,
                byValueTypeIndex) ||
            !reader.readIndex(
                offset + kLayout.type.declaringTypeIndex,
                kLayout.widths.type,
                declaringTypeIndex) ||
            !reader.readIndex(
                offset + kLayout.type.parentTypeIndex,
                kLayout.widths.type,
                parentTypeIndex) ||
            !reader.readIndex(
                offset + kLayout.type.genericContainerIndex,
                kLayout.widths.genericContainer,
                genericContainerIndex) ||
            byValueTypeIndex < 0 || declaringTypeIndex < -1 || parentTypeIndex < -1 ||
            genericContainerIndex < -1 ||
            usedByValueTypeIndices[static_cast<std::size_t>(byValueTypeIndex)] != 0) {
            return std::nullopt;
        }
        usedByValueTypeIndices[static_cast<std::size_t>(byValueTypeIndex)] = 1;

        std::uint16_t methodCount = 0;
        for (std::size_t role = 0; role < kTypeRangeStarts.size(); ++role) {
            std::int32_t start = 0;
            std::uint16_t count = 0;
            if (!reader.readI32(offset + kTypeRangeStarts[role], start) ||
                !reader.readU16(offset + kTypeRangeCounts[role], count) ||
                !accumulateRange(start, count, analysis.ranges[role])) {
                return std::nullopt;
            }
            if (role == static_cast<std::size_t>(TypeRangeRole::Method)) {
                methodCount = count;
            }
        }

        analysis.stringReferences.push_back(nameIndex);
        analysis.stringReferences.push_back(namespaceIndex);
        analysis.facts.push_back({
            byValueTypeIndex,
            declaringTypeIndex,
            genericContainerIndex,
            methodCount,
        });
        if (genericContainerIndex >= 0) {
            analysis.genericReferences.push_back({
                genericContainerIndex,
                static_cast<std::int32_t>(index),
                false,
            });
        }
    }
    return analysis;
}

bool validateTypeRanges(
    const Reader& reader,
    const CountedMetadataSection& typeSection,
    TypeRangeRole role,
    std::size_t total,
    bool allowHoles,
    std::vector<std::int32_t>* owners = nullptr) {
    const auto roleIndex = static_cast<std::size_t>(role);
    std::vector<OwnedRange> ranges;
    ranges.reserve(typeSection.count);
    for (std::size_t index = 0; index < typeSection.count; ++index) {
        std::size_t offset = 0;
        std::int32_t start = 0;
        std::uint16_t count = 0;
        if (!recordOffset(typeSection, index, kLayout.type.size, offset) ||
            !reader.readI32(offset + kTypeRangeStarts[roleIndex], start) ||
            !reader.readU16(offset + kTypeRangeCounts[roleIndex], count)) {
            return false;
        }
        if (count == 0) {
            if (start < -1 || (start >= 0 && static_cast<std::size_t>(start) > total)) {
                return false;
            }
            continue;
        }
        if (start < 0) {
            return false;
        }
        ranges.push_back({
            static_cast<std::size_t>(start),
            count,
            static_cast<std::int32_t>(index),
        });
    }
    return validateRanges(std::move(ranges), total, allowHoles, owners);
}

bool validRelativeMethodIndex(std::int32_t index, std::size_t count) noexcept {
    return index == -1 || (index >= 0 && static_cast<std::size_t>(index) < count);
}

struct MethodAnalysis {
    RangeStats parameters;
    std::vector<std::int32_t> stringReferences;
    std::vector<GenericReference> genericReferences;
};

std::optional<MethodAnalysis> analyzeMethods(
    const Reader& reader,
    const CountedMetadataSection& section,
    const std::vector<std::int32_t>& owners) {
    if (owners.size() != section.count ||
        !isShape(section, section.count, kLayout.method.size)) {
        return std::nullopt;
    }
    MethodAnalysis analysis;
    analysis.stringReferences.reserve(section.count);
    for (std::size_t index = 0; index < section.count; ++index) {
        std::size_t offset = 0;
        std::int32_t nameIndex = 0;
        std::int32_t declaringTypeIndex = -1;
        std::int32_t returnTypeIndex = -1;
        std::int32_t parameterStart = -1;
        std::int32_t genericContainerIndex = -1;
        std::uint16_t parameterCount = 0;
        if (!recordOffset(section, index, kLayout.method.size, offset) ||
            !reader.readI32(offset, nameIndex) || nameIndex < 0 ||
            !reader.readIndex(
                offset + kLayout.method.declaringTypeIndex,
                kLayout.widths.typeDefinition,
                declaringTypeIndex) ||
            declaringTypeIndex != owners[index] ||
            !reader.readIndex(
                offset + kLayout.method.returnTypeIndex,
                kLayout.widths.type,
                returnTypeIndex) || returnTypeIndex < 0 ||
            !reader.readIndex(
                offset + kLayout.method.parameterStart,
                kLayout.widths.parameter,
                parameterStart) ||
            !reader.readIndex(
                offset + kLayout.method.genericContainerIndex,
                kLayout.widths.genericContainer,
                genericContainerIndex) || genericContainerIndex < -1 ||
            !reader.readU16(offset + kLayout.method.parameterCount, parameterCount) ||
            !accumulateRange(parameterStart, parameterCount, analysis.parameters)) {
            return std::nullopt;
        }
        analysis.stringReferences.push_back(nameIndex);
        if (genericContainerIndex >= 0) {
            analysis.genericReferences.push_back({
                genericContainerIndex,
                static_cast<std::int32_t>(index),
                true,
            });
        }
    }
    return analysis;
}

bool validateMethodParameterRanges(
    const Reader& reader,
    const CountedMetadataSection& methodSection,
    std::size_t total,
    std::vector<std::int32_t>& owners) {
    owners.assign(total, -1);
    std::size_t cursor = 0;
    bool sawClaimedRange = false;
    for (std::size_t index = 0; index < methodSection.count; ++index) {
        std::size_t offset = 0;
        std::int32_t start = 0;
        std::uint16_t count = 0;
        if (!recordOffset(methodSection, index, kLayout.method.size, offset) ||
            !reader.readI32(offset + kLayout.method.parameterStart, start) ||
            !reader.readU16(offset + kLayout.method.parameterCount, count)) {
            return false;
        }
        if (count == 0) {
            if (start != -1 && start != 0) {
                return false;
            }
            continue;
        }
        if (start < 0) {
            return false;
        }
        const auto unsignedStart = static_cast<std::size_t>(start);
        std::size_t end = 0;
        if ((!sawClaimedRange && unsignedStart != 0) ||
            (sawClaimedRange && unsignedStart < cursor) ||
            unsignedStart > total || count > total - unsignedStart ||
            !checkedAdd(unsignedStart, count, end)) {
            return false;
        }
        std::fill(
            owners.begin() + static_cast<std::ptrdiff_t>(unsignedStart),
            owners.begin() + static_cast<std::ptrdiff_t>(end),
            static_cast<std::int32_t>(index));
        cursor = end;
        sawClaimedRange = true;
    }
    return cursor == total && (total == 0 || sawClaimedRange);
}

}
}

namespace il2cppmanager {
namespace {

void appendReferences(
    std::vector<std::int32_t>& destination,
    const std::vector<std::int32_t>& source) {
    destination.insert(destination.end(), source.begin(), source.end());
}

bool validateFields(
    const Reader& reader,
    const CountedMetadataSection& section,
    const std::vector<std::int32_t>& owners,
    std::vector<std::int32_t>& strings) {
    if (owners.size() != section.count) {
        return false;
    }
    for (std::size_t index = 0; index < section.count; ++index) {
        std::size_t offset = 0;
        std::int32_t nameIndex = -1;
        std::int32_t typeIndex = -1;
        std::uint32_t token = 0;
        if (!recordOffset(section, index, kLayout.field.size, offset) ||
            !reader.readI32(offset, nameIndex) || nameIndex < 0 ||
            !reader.readIndex(
                offset + kLayout.field.typeIndex,
                kLayout.widths.type,
                typeIndex) || typeIndex < 0 ||
            !reader.readU32(offset + kLayout.field.token, token) ||
            (owners[index] >= 0 &&
             ((token & 0xFF000000U) != 0x04000000U ||
              (token & 0x00FFFFFFU) == 0)) ||
            (owners[index] < 0 && (typeIndex != 0 || token != 0))) {
            return false;
        }
        strings.push_back(nameIndex);
    }
    return true;
}

bool validateParameters(
    const Reader& reader,
    const CountedMetadataSection& section,
    const std::vector<std::int32_t>& owners,
    std::vector<std::int32_t>& strings) {
    if (owners.size() != section.count) {
        return false;
    }
    for (std::size_t index = 0; index < section.count; ++index) {
        std::size_t offset = 0;
        std::int32_t nameIndex = -1;
        std::int32_t typeIndex = -1;
        std::uint32_t token = 0;
        if (!recordOffset(section, index, kLayout.parameter.size, offset) ||
            !reader.readI32(offset, nameIndex) || nameIndex < 0 ||
            !reader.readU32(offset + kLayout.parameter.token, token) ||
            (token & 0xFF000000U) != 0x08000000U ||
            !reader.readIndex(
                offset + kLayout.parameter.typeIndex,
                kLayout.widths.type,
                typeIndex) || typeIndex < 0) {
            return false;
        }
        strings.push_back(nameIndex);
    }
    return true;
}

bool validateProperties(
    const Reader& reader,
    const CountedMetadataSection& section,
    const std::vector<std::int32_t>& owners,
    const std::vector<TypeFacts>& types,
    std::vector<std::int32_t>& strings) {
    if (owners.size() != section.count) {
        return false;
    }
    for (std::size_t index = 0; index < section.count; ++index) {
        std::size_t offset = 0;
        std::int32_t nameIndex = -1;
        std::int32_t getterIndex = -1;
        std::int32_t setterIndex = -1;
        const auto owner = owners[index];
        if (owner < 0 || static_cast<std::size_t>(owner) >= types.size() ||
            !recordOffset(section, index, kLayout.property.size, offset) ||
            !reader.readI32(offset, nameIndex) || nameIndex < 0 ||
            !reader.readIndex(
                offset + kLayout.property.getterIndex,
                kLayout.widths.method,
                getterIndex) ||
            !reader.readIndex(
                offset + kLayout.property.setterIndex,
                kLayout.widths.method,
                setterIndex) ||
            !validRelativeMethodIndex(getterIndex, types[static_cast<std::size_t>(owner)].methodCount) ||
            !validRelativeMethodIndex(setterIndex, types[static_cast<std::size_t>(owner)].methodCount)) {
            return false;
        }
        strings.push_back(nameIndex);
    }
    return true;
}

bool validateEvents(
    const Reader& reader,
    const CountedMetadataSection& section,
    const std::vector<std::int32_t>& owners,
    const std::vector<TypeFacts>& types,
    std::vector<std::int32_t>& strings) {
    if (owners.size() != section.count) {
        return false;
    }
    for (std::size_t index = 0; index < section.count; ++index) {
        std::size_t offset = 0;
        std::int32_t nameIndex = -1;
        std::int32_t typeIndex = -1;
        std::array<std::int32_t, 3> methods{};
        const auto owner = owners[index];
        if (owner < 0 || static_cast<std::size_t>(owner) >= types.size() ||
            !recordOffset(section, index, kLayout.event.size, offset) ||
            !reader.readI32(offset, nameIndex) || nameIndex < 0 ||
            !reader.readIndex(offset + kLayout.event.typeIndex, kLayout.widths.type, typeIndex) ||
            typeIndex < 0 ||
            !reader.readIndex(offset + kLayout.event.addIndex, kLayout.widths.method, methods[0]) ||
            !reader.readIndex(offset + kLayout.event.removeIndex, kLayout.widths.method, methods[1]) ||
            !reader.readIndex(offset + kLayout.event.raiseIndex, kLayout.widths.method, methods[2])) {
            return false;
        }
        const auto methodCount = types[static_cast<std::size_t>(owner)].methodCount;
        if (std::any_of(methods.begin(), methods.end(), [methodCount](std::int32_t method) {
                return !validRelativeMethodIndex(method, methodCount);
            })) {
            return false;
        }
        strings.push_back(nameIndex);
    }
    return true;
}

bool validateNestedTypes(
    const Reader& reader,
    const CountedMetadataSection& section,
    const std::vector<std::int32_t>& owners,
    const std::vector<TypeFacts>& types) {
    if (owners.size() != section.count) {
        return false;
    }
    std::vector<std::int32_t> parentByDefinition(types.size(), -1);
    std::vector<std::int32_t> definitionByTypeIndex(
        static_cast<std::size_t>(std::numeric_limits<std::uint16_t>::max()) + 1U,
        -1);
    for (std::size_t index = 0; index < types.size(); ++index) {
        definitionByTypeIndex[static_cast<std::size_t>(types[index].byValueTypeIndex)] =
            static_cast<std::int32_t>(index);
    }
    for (std::size_t index = 0; index < section.count; ++index) {
        std::size_t offset = 0;
        std::int32_t nestedType = -1;
        const auto owner = owners[index];
        if (owner < 0 || static_cast<std::size_t>(owner) >= types.size() ||
            !recordOffset(section, index, kLayout.nestedTypeRecordSize, offset) ||
            !reader.readI32(offset, nestedType) || nestedType < 0 ||
            static_cast<std::size_t>(nestedType) >= types.size() || nestedType == owner ||
            parentByDefinition[static_cast<std::size_t>(nestedType)] != -1 ||
            types[static_cast<std::size_t>(nestedType)].declaringTypeIndex !=
                types[static_cast<std::size_t>(owner)].byValueTypeIndex) {
            return false;
        }
        parentByDefinition[static_cast<std::size_t>(nestedType)] = owner;
    }
    for (std::size_t index = 0; index < types.size(); ++index) {
        const auto declaringType = types[index].declaringTypeIndex;
        if (declaringType < 0) {
            if (parentByDefinition[index] >= 0) {
                return false;
            }
            continue;
        }
        const auto expected = definitionByTypeIndex[static_cast<std::size_t>(declaringType)];
        if (expected < 0 || parentByDefinition[index] != expected) {
            return false;
        }
    }
    return true;
}

bool validateCompactIndexRecords(
    const Reader& reader,
    const CountedMetadataSection& section,
    std::size_t recordSize,
    std::size_t indexOffset) noexcept {
    for (std::size_t index = 0; index < section.count; ++index) {
        std::size_t offset = 0;
        std::int32_t typeIndex = -1;
        if (!recordOffset(section, index, recordSize, offset) ||
            !reader.readIndex(offset + indexOffset, kLayout.widths.type, typeIndex) ||
            typeIndex < 0) {
            return false;
        }
    }
    return true;
}

struct GenericOwner {
    std::int32_t owner = -1;
    bool isMethod = false;
    bool present = false;
};

bool mapGenerics(
    const Reader& reader,
    const Directory& directory,
    const std::vector<GenericReference>& references,
    std::array<bool, kDirectorySectionCount>& used,
    CountedMetadataSections& sections,
    std::vector<std::int32_t>& strings) {
    if (references.empty()) {
        sections.genericContainers = emptySection();
        sections.genericParameters = emptySection();
        sections.genericParameterConstraints = emptySection();
        return true;
    }

    const auto containerSection = selectUniqueSection(
        directory,
        references.size(),
        kLayout.genericContainer.size,
        used);
    if (!containerSection) {
        return false;
    }
    sections.genericContainers = *containerSection;
    std::vector<GenericOwner> containerOwners(containerSection->count);
    for (const auto& reference : references) {
        if (reference.index < 0 ||
            static_cast<std::size_t>(reference.index) >= containerOwners.size() ||
            containerOwners[static_cast<std::size_t>(reference.index)].present) {
            return false;
        }
        containerOwners[static_cast<std::size_t>(reference.index)] = {
            reference.owner,
            reference.isMethod,
            true,
        };
    }
    if (std::any_of(containerOwners.begin(), containerOwners.end(), [](const GenericOwner& owner) {
            return !owner.present;
        })) {
        return false;
    }

    RangeStats parameterStats;
    std::vector<OwnedRange> parameterRanges;
    parameterRanges.reserve(containerSection->count);
    for (std::size_t index = 0; index < containerSection->count; ++index) {
        std::size_t offset = 0;
        std::int32_t owner = -1;
        std::int32_t parameterCount = -1;
        std::int32_t isMethod = -1;
        std::int32_t parameterStart = -1;
        if (!recordOffset(*containerSection, index, kLayout.genericContainer.size, offset) ||
            !reader.readI32(offset, owner) ||
            !reader.readI32(offset + kLayout.genericContainer.parameterCount, parameterCount) ||
            !reader.readI32(offset + kLayout.genericContainer.isMethod, isMethod) ||
            !reader.readI32(offset + kLayout.genericContainer.parameterStart, parameterStart) ||
            parameterCount < 0 || (isMethod != 0 && isMethod != 1) ||
            owner != containerOwners[index].owner ||
            (isMethod != 0) != containerOwners[index].isMethod ||
            !accumulateRange(
                parameterStart,
                static_cast<std::size_t>(parameterCount),
                parameterStats)) {
            return false;
        }
        if (parameterCount > 0) {
            parameterRanges.push_back({
                static_cast<std::size_t>(parameterStart),
                static_cast<std::size_t>(parameterCount),
                static_cast<std::int32_t>(index),
            });
        }
    }
    if (parameterStats.claimedCount != parameterStats.maximumEnd) {
        return false;
    }

    const auto genericParameterSection = selectUniqueSection(
        directory,
        parameterStats.maximumEnd,
        kLayout.genericParameter.size,
        used);
    if (!genericParameterSection) {
        return false;
    }
    sections.genericParameters = *genericParameterSection;
    std::vector<std::int32_t> parameterOwners;
    if (!validateRanges(
            std::move(parameterRanges),
            genericParameterSection->count,
            false,
            &parameterOwners)) {
        return false;
    }

    RangeStats constraintStats;
    std::vector<OwnedRange> constraintRanges;
    constraintRanges.reserve(genericParameterSection->count);
    std::vector<std::uint16_t> nextNumber(containerSection->count);
    for (std::size_t index = 0; index < genericParameterSection->count; ++index) {
        std::size_t offset = 0;
        std::int32_t owner = -1;
        std::int32_t nameIndex = -1;
        std::int16_t constraintStart = -1;
        std::int16_t constraintCount = -1;
        std::uint16_t number = 0;
        if (!recordOffset(*genericParameterSection, index, kLayout.genericParameter.size, offset) ||
            !reader.readIndex(
                offset + kLayout.genericParameter.ownerIndex,
                kLayout.widths.genericContainer,
                owner) || owner != parameterOwners[index] || owner < 0 ||
            !reader.readI32(offset + kLayout.genericParameter.nameIndex, nameIndex) ||
            nameIndex < 0 ||
            !reader.readI16(
                offset + kLayout.genericParameter.constraintsStart,
                constraintStart) ||
            !reader.readI16(
                offset + kLayout.genericParameter.constraintsCount,
                constraintCount) || constraintCount < 0 ||
            !reader.readU16(offset + kLayout.genericParameter.number, number) ||
            number != nextNumber[static_cast<std::size_t>(owner)] ||
            !accumulateRange(
                constraintStart,
                static_cast<std::size_t>(constraintCount),
                constraintStats)) {
            return false;
        }
        ++nextNumber[static_cast<std::size_t>(owner)];
        strings.push_back(nameIndex);
        if (constraintCount > 0) {
            constraintRanges.push_back({
                static_cast<std::size_t>(constraintStart),
                static_cast<std::size_t>(constraintCount),
                static_cast<std::int32_t>(index),
            });
        }
    }
    if (constraintStats.claimedCount != constraintStats.maximumEnd) {
        return false;
    }
    const auto constraintSection = selectUniqueSection(
        directory,
        constraintStats.maximumEnd,
        kLayout.genericParameterConstraintRecordSize,
        used);
    if (!constraintSection ||
        !validateRanges(
            std::move(constraintRanges),
            constraintSection->count,
            false) ||
        !validateCompactIndexRecords(
            reader,
            *constraintSection,
            kLayout.genericParameterConstraintRecordSize,
            0)) {
        return false;
    }
    sections.genericParameterConstraints = *constraintSection;
    return true;
}

struct ImagePair {
    CountedMetadataSection images;
    CountedMetadataSection assemblies;
    std::vector<std::int32_t> strings;
};

std::optional<ImagePair> validateImagePair(
    const Reader& reader,
    const CountedMetadataSection& images,
    const CountedMetadataSection& assemblies,
    std::size_t typeCount) {
    if (images.count == 0 || images.count != assemblies.count ||
        !isShape(images, images.count, kLayout.image.size) ||
        !isShape(assemblies, assemblies.count, kLayout.assembly.size)) {
        return std::nullopt;
    }
    std::vector<std::int32_t> imageAssemblyIndices(images.count, -1);
    std::vector<OwnedRange> typeRanges;
    std::vector<std::int32_t> strings;
    typeRanges.reserve(images.count);
    strings.reserve(images.count * 2U);
    for (std::size_t index = 0; index < images.count; ++index) {
        std::size_t offset = 0;
        std::int32_t nameIndex = -1;
        std::int32_t assemblyIndex = -1;
        std::int32_t typeStart = -1;
        std::uint32_t currentTypeCount = 0;
        if (!recordOffset(images, index, kLayout.image.size, offset) ||
            !reader.readI32(offset, nameIndex) || nameIndex < 0 ||
            !reader.readI32(offset + 4, assemblyIndex) || assemblyIndex < 0 ||
            static_cast<std::size_t>(assemblyIndex) >= assemblies.count ||
            !reader.readIndex(
                offset + kLayout.image.typeStart,
                kLayout.widths.typeDefinition,
                typeStart) ||
            !reader.readU32(offset + kLayout.image.typeCount, currentTypeCount)) {
            return std::nullopt;
        }
        if (currentTypeCount == 0) {
            if (typeStart < -1 || (typeStart >= 0 && static_cast<std::size_t>(typeStart) > typeCount)) {
                return std::nullopt;
            }
        } else {
            if (typeStart < 0) {
                return std::nullopt;
            }
            typeRanges.push_back({
                static_cast<std::size_t>(typeStart),
                currentTypeCount,
                static_cast<std::int32_t>(index),
            });
        }
        imageAssemblyIndices[index] = assemblyIndex;
        strings.push_back(nameIndex);
    }
    if (!validateRanges(std::move(typeRanges), typeCount, false)) {
        return std::nullopt;
    }

    std::vector<std::uint8_t> claimedImages(images.count);
    for (std::size_t index = 0; index < assemblies.count; ++index) {
        std::size_t offset = 0;
        std::int32_t imageIndex = -1;
        std::int32_t nameIndex = -1;
        if (!recordOffset(assemblies, index, kLayout.assembly.size, offset) ||
            !reader.readI32(offset, imageIndex) || imageIndex < 0 ||
            static_cast<std::size_t>(imageIndex) >= images.count ||
            claimedImages[static_cast<std::size_t>(imageIndex)] != 0 ||
            !reader.readI32(offset + kLayout.assembly.nameIndex, nameIndex) || nameIndex < 0 ||
            imageAssemblyIndices[static_cast<std::size_t>(imageIndex)] !=
                static_cast<std::int32_t>(index)) {
            return std::nullopt;
        }
        claimedImages[static_cast<std::size_t>(imageIndex)] = 1;
        strings.push_back(nameIndex);
    }
    return ImagePair{images, assemblies, std::move(strings)};
}

std::optional<ImagePair> selectImagePair(
    const Reader& reader,
    const Directory& directory,
    const std::array<bool, kDirectorySectionCount>& used,
    std::size_t typeCount) {
    std::optional<ImagePair> accepted;
    for (const auto& images : directory.sections) {
        if (used[images.directoryIndex] || images.count == 0 ||
            !isShape(images, images.count, kLayout.image.size)) {
            continue;
        }
        for (const auto& assemblies : directory.sections) {
            if (used[assemblies.directoryIndex] ||
                assemblies.directoryIndex == images.directoryIndex ||
                assemblies.count != images.count ||
                !isShape(assemblies, assemblies.count, kLayout.assembly.size)) {
                continue;
            }
            auto candidate = validateImagePair(reader, images, assemblies, typeCount);
            if (!candidate) {
                continue;
            }
            if (accepted) {
                return std::nullopt;
            }
            accepted = std::move(candidate);
        }
    }
    return accepted;
}

bool validUtf8(const std::uint8_t* bytes, std::size_t size) noexcept {
    std::size_t index = 0;
    while (index < size) {
        const auto first = bytes[index];
        if (first <= 0x7FU) {
            ++index;
        } else if (first >= 0xC2U && first <= 0xDFU) {
            if (index + 1 >= size || (bytes[index + 1] & 0xC0U) != 0x80U) {
                return false;
            }
            index += 2;
        } else if (first >= 0xE0U && first <= 0xEFU) {
            if (index + 2 >= size || (bytes[index + 1] & 0xC0U) != 0x80U ||
                (bytes[index + 2] & 0xC0U) != 0x80U ||
                (first == 0xE0U && bytes[index + 1] < 0xA0U) ||
                (first == 0xEDU && bytes[index + 1] >= 0xA0U)) {
                return false;
            }
            index += 3;
        } else if (first >= 0xF0U && first <= 0xF4U) {
            if (index + 3 >= size || (bytes[index + 1] & 0xC0U) != 0x80U ||
                (bytes[index + 2] & 0xC0U) != 0x80U ||
                (bytes[index + 3] & 0xC0U) != 0x80U ||
                (first == 0xF0U && bytes[index + 1] < 0x90U) ||
                (first == 0xF4U && bytes[index + 1] >= 0x90U)) {
                return false;
            }
            index += 4;
        } else {
            return false;
        }
    }
    return true;
}

bool validStringTable(
    const Reader& reader,
    const CountedMetadataSection& section,
    const std::vector<std::int32_t>& references) {
    if (section.size == 0 || references.empty()) {
        return false;
    }
    std::size_t reference = 0;
    std::size_t start = 0;
    while (reference < references.size() && start < section.size) {
        const auto* begin = reader.at(section.offset + start);
        const auto* end = std::find(begin, begin + (section.size - start), std::uint8_t{0});
        if (end == begin + (section.size - start)) {
            return false;
        }
        const auto length = static_cast<std::size_t>(end - begin);
        if (reference < references.size() &&
            static_cast<std::size_t>(references[reference]) < start) {
            return false;
        }
        if (reference < references.size() &&
            static_cast<std::size_t>(references[reference]) == start) {
            if (!validUtf8(begin, length)) {
                return false;
            }
            ++reference;
        }
        start += length + 1U;
    }
    return reference == references.size();
}

std::optional<CountedMetadataSection> selectStringTable(
    const Reader& reader,
    const Directory& directory,
    const std::array<bool, kDirectorySectionCount>& used,
    std::vector<std::int32_t> references) {
    if (references.empty() ||
        std::any_of(references.begin(), references.end(), [](std::int32_t value) {
            return value < 0;
        })) {
        return std::nullopt;
    }
    std::sort(references.begin(), references.end());
    references.erase(std::unique(references.begin(), references.end()), references.end());
    const auto maximumReference = static_cast<std::size_t>(references.back());
    const CountedMetadataSection* accepted = nullptr;
    for (const auto& section : directory.sections) {
        if (used[section.directoryIndex] || maximumReference >= section.size ||
            !validStringTable(reader, section, references)) {
            continue;
        }
        if (accepted != nullptr) {
            return std::nullopt;
        }
        accepted = &section;
    }
    return accepted == nullptr
        ? std::nullopt
        : std::optional<CountedMetadataSection>(*accepted);
}

std::optional<ComplementedCountedMetadataInference> inferFromTypeSection(
    const Reader& reader,
    const Directory& directory,
    const CountedMetadataSection& typeSection) {
    auto types = analyzeTypes(reader, typeSection);
    if (!types) {
        return std::nullopt;
    }
    std::array<bool, kDirectorySectionCount> used{};
    used[typeSection.directoryIndex] = true;
    CountedMetadataSections sections;
    sections.typeDefinitions = typeSection;

    const auto selectTypeSection = [&](TypeRangeRole role, std::size_t recordSize) {
        const auto& stats = types->ranges[static_cast<std::size_t>(role)];
        return selectUniqueSection(directory, stats.maximumEnd, recordSize, used);
    };
    const auto methods = selectTypeSection(TypeRangeRole::Method, kLayout.method.size);
    const auto events = selectTypeSection(TypeRangeRole::Event, kLayout.event.size);
    const auto properties = selectTypeSection(TypeRangeRole::Property, kLayout.property.size);
    const auto nestedTypes = selectTypeSection(
        TypeRangeRole::NestedType,
        kLayout.nestedTypeRecordSize);
    const auto interfaces = selectTypeSection(
        TypeRangeRole::Interface,
        kLayout.interfaceRecordSize);
    const auto vtableMethods = selectTypeSection(
        TypeRangeRole::VtableMethod,
        kLayout.vtableMethodRecordSize);
    const auto interfaceOffsets = selectTypeSection(
        TypeRangeRole::InterfaceOffset,
        kLayout.interfaceOffset.size);
    if (!methods || !events || !properties || !nestedTypes || !interfaces ||
        !vtableMethods || !interfaceOffsets) {
        return std::nullopt;
    }
    sections.methods = *methods;
    sections.events = *events;
    sections.properties = *properties;
    sections.nestedTypes = *nestedTypes;
    sections.interfaces = *interfaces;
    sections.vtableMethods = *vtableMethods;
    sections.interfaceOffsets = *interfaceOffsets;

    for (const auto role : {
             TypeRangeRole::Method,
             TypeRangeRole::Event,
             TypeRangeRole::Property,
             TypeRangeRole::NestedType,
             TypeRangeRole::Interface,
             TypeRangeRole::VtableMethod,
             TypeRangeRole::InterfaceOffset,
         }) {
        const auto& stats = types->ranges[static_cast<std::size_t>(role)];
        if (stats.claimedCount != stats.maximumEnd) {
            return std::nullopt;
        }
    }

    std::vector<std::int32_t> methodOwners;
    std::vector<std::int32_t> fieldOwners;
    std::vector<std::int32_t> eventOwners;
    std::vector<std::int32_t> propertyOwners;
    std::vector<std::int32_t> nestedOwners;
    const auto fieldCount = types->ranges[static_cast<std::size_t>(TypeRangeRole::Field)]
                                .maximumEnd;
    if (!validateTypeRanges(
            reader,
            typeSection,
            TypeRangeRole::Field,
            fieldCount,
            true,
            &fieldOwners) ||
        !validateTypeRanges(
            reader,
            typeSection,
            TypeRangeRole::Method,
            methods->count,
            false,
            &methodOwners) ||
        !validateTypeRanges(
            reader,
            typeSection,
            TypeRangeRole::Event,
            events->count,
            false,
            &eventOwners) ||
        !validateTypeRanges(
            reader,
            typeSection,
            TypeRangeRole::Property,
            properties->count,
            false,
            &propertyOwners) ||
        !validateTypeRanges(
            reader,
            typeSection,
            TypeRangeRole::NestedType,
            nestedTypes->count,
            false,
            &nestedOwners) ||
        !validateTypeRanges(reader, typeSection, TypeRangeRole::Interface, interfaces->count, false) ||
        !validateTypeRanges(
            reader,
            typeSection,
            TypeRangeRole::VtableMethod,
            vtableMethods->count,
            false) ||
        !validateTypeRanges(
            reader,
            typeSection,
            TypeRangeRole::InterfaceOffset,
            interfaceOffsets->count,
            false)) {
        return std::nullopt;
    }

    std::optional<CountedMetadataSection> fields;
    for (const auto& candidate : directory.sections) {
        if (used[candidate.directoryIndex] ||
            !isShape(candidate, fieldCount, kLayout.field.size)) {
            continue;
        }
        std::vector<std::int32_t> ignoredStrings;
        if (!validateFields(reader, candidate, fieldOwners, ignoredStrings)) {
            continue;
        }
        if (fields) {
            return std::nullopt;
        }
        fields = candidate;
    }
    if (!fields) {
        return std::nullopt;
    }
    sections.fields = *fields;
    used[fields->directoryIndex] = true;

    auto methodAnalysis = analyzeMethods(reader, *methods, methodOwners);
    if (!methodAnalysis) {
        return std::nullopt;
    }
    std::vector<std::int32_t> parameterOwners;
    if (!validateMethodParameterRanges(
            reader,
            *methods,
            methodAnalysis->parameters.maximumEnd,
            parameterOwners)) {
        return std::nullopt;
    }
    std::optional<CountedMetadataSection> parameters;
    for (const auto& candidate : directory.sections) {
        if (used[candidate.directoryIndex] ||
            !isShape(
                candidate,
                methodAnalysis->parameters.maximumEnd,
                kLayout.parameter.size)) {
            continue;
        }
        std::vector<std::int32_t> ignoredStrings;
        if (!validateParameters(reader, candidate, parameterOwners, ignoredStrings)) {
            continue;
        }
        if (parameters) {
            return std::nullopt;
        }
        parameters = candidate;
    }
    if (!parameters) {
        return std::nullopt;
    }
    sections.parameters = *parameters;
    used[parameters->directoryIndex] = true;

    std::vector<std::int32_t> stringReferences = std::move(types->stringReferences);
    appendReferences(stringReferences, methodAnalysis->stringReferences);
    if (!validateFields(reader, *fields, fieldOwners, stringReferences) ||
        !validateParameters(reader, *parameters, parameterOwners, stringReferences) ||
        !validateProperties(
            reader,
            *properties,
            propertyOwners,
            types->facts,
            stringReferences) ||
        !validateEvents(
            reader,
            *events,
            eventOwners,
            types->facts,
            stringReferences) ||
        !validateNestedTypes(reader, *nestedTypes, nestedOwners, types->facts) ||
        !validateCompactIndexRecords(
            reader,
            *interfaces,
            kLayout.interfaceRecordSize,
            0) ||
        !validateCompactIndexRecords(
            reader,
            *interfaceOffsets,
            kLayout.interfaceOffset.size,
            kLayout.interfaceOffset.typeIndex)) {
        return std::nullopt;
    }

    std::vector<GenericReference> genericReferences = std::move(types->genericReferences);
    genericReferences.insert(
        genericReferences.end(),
        methodAnalysis->genericReferences.begin(),
        methodAnalysis->genericReferences.end());
    if (!mapGenerics(
            reader,
            directory,
            genericReferences,
            used,
            sections,
            stringReferences)) {
        return std::nullopt;
    }

    const auto imagePair = selectImagePair(reader, directory, used, typeSection.count);
    if (!imagePair) {
        return std::nullopt;
    }
    sections.images = imagePair->images;
    sections.assemblies = imagePair->assemblies;
    used[sections.images.directoryIndex] = true;
    used[sections.assemblies.directoryIndex] = true;
    appendReferences(stringReferences, imagePair->strings);

    const auto strings = selectStringTable(reader, directory, used, std::move(stringReferences));
    if (!strings) {
        return std::nullopt;
    }
    sections.strings = *strings;

    return ComplementedCountedMetadataInference{
        kNormalizedVersion,
        directory.probe.metadataSize,
        true,
        29,
        sections,
        kLayout,
    };
}

}

std::optional<ComplementedCountedMetadataInference> inferComplementedCountedMetadata(
    const std::uint8_t* bytes,
    std::size_t size) noexcept {
    try {
        const auto directory = parseDirectory(bytes, size, size);
        if (!directory || directory->probe.metadataSize != size) {
            return std::nullopt;
        }
        const Reader reader(bytes, size);
        std::optional<ComplementedCountedMetadataInference> accepted;
        for (const auto& section : directory->sections) {
            if (!isShape(section, section.count, kLayout.type.size)) {
                continue;
            }
            auto candidate = inferFromTypeSection(reader, *directory, section);
            if (!candidate) {
                continue;
            }
            if (accepted) {
                return std::nullopt;
            }
            accepted = std::move(candidate);
        }
        return accepted;
    } catch (...) {
        return std::nullopt;
    }
}

}
