#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace il2cppmanager {

constexpr std::size_t kMaximumMemoryTransferBytes = 16U * 1024U * 1024U;

struct ProcessMapRegion {
    std::uint64_t start = 0;
    std::uint64_t end = 0;
    std::uint64_t fileOffset = 0;
    bool readable = false;
    bool writable = false;
    bool executable = false;
    std::string path;
};

std::vector<std::int32_t> scanProcessIds();
std::optional<std::string> readProcessName(std::int32_t pid);
std::optional<std::int64_t> readProcessStartTicks(std::int32_t pid);
std::vector<ProcessMapRegion> readProcessMaps(std::int32_t pid);
bool modulePathMatches(
    std::string_view mappedPath,
    std::string_view requestedName) noexcept;
std::optional<std::uint64_t> findModuleBase(
    const std::vector<ProcessMapRegion>& regions,
    const std::string& moduleName);
std::optional<std::uint64_t> findModuleBase(std::int32_t pid, const std::string& moduleName);
bool readProcessMemory(
    std::int32_t pid,
    std::uint64_t address,
    std::uint8_t* destination,
    std::size_t size) noexcept;
bool writeProcessMemory(
    std::int32_t pid,
    std::uint64_t address,
    const std::uint8_t* source,
    std::size_t size) noexcept;

}
