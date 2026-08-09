#pragma once

#include "metadata_parser.h"

#include <cstddef>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace il2cppmanager {

enum class MetadataSymbolKind : std::int32_t {
    Type = 0,
    Field = 1,
    Method = 2,
};

struct MetadataSymbolResult {
    MetadataSymbolKind kind;
    std::int32_t typeIndex;
    std::int32_t memberIndex;
    std::string name;
    std::string assemblyName;
    std::string ownerName;
};

struct MetadataSymbolPage {
    std::size_t totalCount = 0;
    std::vector<MetadataSymbolResult> items;
};

using MethodAddressMatcher = std::function<bool(
    const TypeMetadata& type,
    const MethodMetadata& method,
    std::uint64_t query)>;

MetadataSymbolPage searchMetadataSymbols(
    const MetadataModel& model,
    const std::string& query,
    bool exactMatch,
    bool matchCase,
    std::size_t offset,
    std::size_t limit,
    bool countAllMatches,
    const MethodAddressMatcher& methodAddressMatcher);

}
