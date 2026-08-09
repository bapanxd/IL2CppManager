#include "metadata_search.h"

#include <algorithm>
#include <array>
#include <charconv>
#include <limits>
#include <optional>
#include <stdexcept>
#include <string_view>
#include <system_error>

namespace il2cppmanager {
namespace {

constexpr std::size_t kMaximumTypeNestingDepth = 64;
constexpr char kNestedTypeSeparator = '/';
constexpr char kNamespaceSeparator = '.';

unsigned char foldedAscii(unsigned char value) noexcept {
    return value >= 'A' && value <= 'Z'
               ? static_cast<unsigned char>(value + ('a' - 'A'))
               : value;
}

bool equalFoldedCharacter(char left, char right) noexcept {
    return foldedAscii(static_cast<unsigned char>(left)) ==
           foldedAscii(static_cast<unsigned char>(right));
}

bool equalsFolded(std::string_view value, std::string_view query) noexcept {
    if (value.size() != query.size()) {
        return false;
    }
    for (std::size_t index = 0; index < query.size(); ++index) {
        if (!equalFoldedCharacter(value[index], query[index])) {
            return false;
        }
    }
    return true;
}

bool isAsciiWhitespace(char value) noexcept {
    return value == ' ' || (value >= '\t' && value <= '\r');
}

std::optional<std::uint64_t> methodAddressQuery(std::string_view query) noexcept {
    while (!query.empty() && isAsciiWhitespace(query.front())) {
        query.remove_prefix(1);
    }
    while (!query.empty() && isAsciiWhitespace(query.back())) {
        query.remove_suffix(1);
    }

    const bool hasHexPrefix =
        query.size() >= 2 && query[0] == '0' && (query[1] == 'x' || query[1] == 'X');
    if (hasHexPrefix) {
        query.remove_prefix(2);
    }
    if (query.empty()) {
        return std::nullopt;
    }

    bool hasDecimalDigit = false;
    for (const auto character : query) {
        if (character >= '0' && character <= '9') {
            hasDecimalDigit = true;
            continue;
        }
        if ((character < 'a' || character > 'f') &&
            (character < 'A' || character > 'F')) {
            return std::nullopt;
        }
    }
    if (!hasHexPrefix && !hasDecimalDigit) {
        return std::nullopt;
    }

    std::uint64_t value = 0;
    const auto result = std::from_chars(
        query.data(),
        query.data() + query.size(),
        value,
        16);
    return result.ec == std::errc{} && result.ptr == query.data() + query.size()
               ? std::optional(value)
               : std::nullopt;
}

class SymbolNameMatcher final {
public:
    SymbolNameMatcher(std::string_view query, bool exactMatch, bool matchCase) noexcept
        : query_(query), exactMatch_(exactMatch), matchCase_(matchCase) {
        skip_.fill(query_.size());
        if (!exactMatch_ && !matchCase_ && query_.size() > 1) {
            for (std::size_t index = 0; index + 1 < query_.size(); ++index) {
                skip_[foldedAscii(static_cast<unsigned char>(query_[index]))] =
                    query_.size() - index - 1;
            }
        }
    }

    bool matches(std::string_view value) const noexcept {
        if (query_.empty() || query_.size() > value.size()) {
            return false;
        }
        if (matchCase_) {
            return exactMatch_ ? value == query_ : value.find(query_) != std::string_view::npos;
        }
        if (exactMatch_) {
            return equalsFolded(value, query_);
        }
        return containsFolded(value);
    }

private:
    bool containsFolded(std::string_view value) const noexcept {
        const auto querySize = query_.size();
        std::size_t offset = 0;
        while (offset <= value.size() - querySize) {
            auto index = querySize;
            while (index > 0 &&
                   equalFoldedCharacter(value[offset + index - 1], query_[index - 1])) {
                --index;
            }
            if (index == 0) {
                return true;
            }
            offset += skip_[foldedAscii(
                static_cast<unsigned char>(value[offset + querySize - 1]))];
        }
        return false;
    }

    std::string_view query_;
    bool exactMatch_;
    bool matchCase_;
    std::array<std::size_t, 256> skip_{};
};

std::string qualifiedTypeName(const MetadataModel& model, std::size_t typeIndex) {
    const auto& types = model.types();
    std::vector<std::size_t> chain;
    chain.reserve(kMaximumTypeNestingDepth);
    auto currentIndex = typeIndex;
    bool complete = false;
    for (std::size_t depth = 0; depth < kMaximumTypeNestingDepth; ++depth) {
        if (currentIndex >= types.size()) {
            throw std::runtime_error("Declaring type index is out of range");
        }
        if (std::find(chain.begin(), chain.end(), currentIndex) != chain.end()) {
            throw std::runtime_error("Declaring type chain is cyclic");
        }
        chain.push_back(currentIndex);
        const auto declaringIndex = types[currentIndex].declaringTypeDefinitionIndex;
        if (declaringIndex < 0) {
            complete = true;
            break;
        }
        currentIndex = static_cast<std::size_t>(declaringIndex);
    }
    if (!complete) {
        throw std::runtime_error("Declaring type chain exceeds the depth limit");
    }

    const auto& type = types[typeIndex];
    const auto& outerType = types[chain.back()];
    const auto& namespaze = outerType.namespaze.empty() ? type.namespaze : outerType.namespaze;
    std::size_t size = namespaze.size() + (namespaze.empty() ? 0U : 1U);
    for (const auto index : chain) {
        size += types[index].name.size() + 1U;
    }

    std::string result;
    result.reserve(size);
    if (!namespaze.empty()) {
        result.append(namespaze);
        result.push_back(kNamespaceSeparator);
    }
    for (auto iterator = chain.rbegin(); iterator != chain.rend(); ++iterator) {
        if (iterator != chain.rbegin()) {
            result.push_back(kNestedTypeSeparator);
        }
        result.append(types[*iterator].name);
    }
    return result;
}

template <typename Name>
void collectMatch(
    MetadataSymbolPage& page,
    const MetadataModel& model,
    MetadataSymbolKind kind,
    std::size_t typeIndex,
    std::size_t memberIndex,
    const Name& name,
    std::size_t offset,
    std::size_t limit) {
    if (page.totalCount >= offset && page.items.size() < limit) {
        const auto& type = model.types()[typeIndex];
        page.items.push_back(MetadataSymbolResult{
            kind,
            static_cast<std::int32_t>(typeIndex),
            kind == MetadataSymbolKind::Type
                ? -1
                : static_cast<std::int32_t>(memberIndex),
            name,
            type.assemblyName,
            qualifiedTypeName(model, typeIndex),
        });
    }
    if (page.totalCount < std::numeric_limits<std::size_t>::max()) {
        ++page.totalCount;
    }
}

}

MetadataSymbolPage searchMetadataSymbols(
    const MetadataModel& model,
    const std::string& query,
    bool exactMatch,
    bool matchCase,
    std::size_t offset,
    std::size_t limit,
    bool countAllMatches,
    const MethodAddressMatcher& methodAddressMatcher) {
    MetadataSymbolPage page;
    page.items.reserve(limit);
    const SymbolNameMatcher matcher(query, exactMatch, matchCase);
    const auto addressQuery =
        methodAddressMatcher ? methodAddressQuery(query) : std::nullopt;
    const auto& types = model.types();
    for (std::size_t typeIndex = 0; typeIndex < types.size(); ++typeIndex) {
        const auto& type = types[typeIndex];
        if (matcher.matches(type.name)) {
            collectMatch(
                page,
                model,
                MetadataSymbolKind::Type,
                typeIndex,
                0,
                type.name,
                offset,
                limit);
            if (!countAllMatches && page.items.size() == limit) return page;
        }
        for (std::size_t fieldIndex = 0; fieldIndex < type.fields.size(); ++fieldIndex) {
            const auto& field = type.fields[fieldIndex];
            if (matcher.matches(field.name)) {
                collectMatch(
                    page,
                    model,
                    MetadataSymbolKind::Field,
                    typeIndex,
                    fieldIndex,
                    field.name,
                    offset,
                    limit);
                if (!countAllMatches && page.items.size() == limit) return page;
            }
        }
        for (std::size_t methodIndex = 0; methodIndex < type.methods.size(); ++methodIndex) {
            const auto& method = type.methods[methodIndex];
            const bool nameMatches = matcher.matches(method.name);
            const bool addressMatches =
                !nameMatches && addressQuery &&
                methodAddressMatcher(type, method, *addressQuery);
            if (nameMatches || addressMatches) {
                collectMatch(
                    page,
                    model,
                    MetadataSymbolKind::Method,
                    typeIndex,
                    methodIndex,
                    method.name,
                    offset,
                    limit);
                if (!countAllMatches && page.items.size() == limit) return page;
            }
        }
    }
    return page;
}

}
