#include "process_memory.h"

#include <algorithm>
#include <array>
#include <cerrno>
#include <charconv>
#include <climits>
#include <cctype>
#include <cstdint>
#include <dirent.h>
#include <fcntl.h>
#include <fstream>
#include <limits>
#include <memory>
#include <sstream>
#include <string_view>
#include <sys/types.h>
#include <sys/uio.h>
#include <unistd.h>
#include <utility>

namespace il2cppmanager {
namespace {

constexpr std::size_t kMaximumProcFileBytes = 16U * 1024U * 1024U;
constexpr std::size_t kMaximumProcessNameBytes = 64U * 1024U;
constexpr std::size_t kMaximumModuleNameBytes = 4096;
constexpr std::size_t kMaximumIovecsPerCall = 128;
constexpr std::size_t kFallbackPageSize = 4096;
constexpr std::uint64_t kMaximumLoadSegmentOffsetDelta = 64U * 1024U * 1024U;
constexpr std::string_view kMemoryFilePrefix = "memfd:";

class UniqueFd final {
public:
    explicit UniqueFd(int value = -1) noexcept : value_(value) {}
    ~UniqueFd() {
        if (value_ >= 0) {
            close(value_);
        }
    }

    UniqueFd(const UniqueFd&) = delete;
    UniqueFd& operator=(const UniqueFd&) = delete;

    int get() const noexcept {
        return value_;
    }

    explicit operator bool() const noexcept {
        return value_ >= 0;
    }

private:
    int value_;
};

struct DirectoryCloser {
    void operator()(DIR* directory) const noexcept {
        if (directory != nullptr) {
            closedir(directory);
        }
    }
};

using UniqueDirectory = std::unique_ptr<DIR, DirectoryCloser>;

bool validPid(std::int32_t pid) noexcept {
    return pid > 0;
}

std::string procPath(std::int32_t pid, std::string_view file) {
    return "/proc/" + std::to_string(pid) + "/" + std::string(file);
}

bool parseDecimalPid(std::string_view value, std::int32_t& pid) noexcept {
    if (value.empty() || value.size() > 10 ||
        !std::all_of(value.begin(), value.end(), [](char character) {
            return character >= '0' && character <= '9';
        })) {
        return false;
    }
    std::int64_t parsed = 0;
    const auto result = std::from_chars(value.data(), value.data() + value.size(), parsed, 10);
    if (result.ec != std::errc{} || result.ptr != value.data() + value.size() ||
        parsed <= 0 || parsed > std::numeric_limits<std::int32_t>::max()) {
        return false;
    }
    pid = static_cast<std::int32_t>(parsed);
    return true;
}

std::optional<std::vector<std::uint8_t>> readLimitedFile(
    const std::string& path,
    std::size_t maximumBytes) {
    UniqueFd file(open(path.c_str(), O_RDONLY | O_CLOEXEC));
    if (!file) {
        return std::nullopt;
    }

    std::vector<std::uint8_t> bytes;
    bytes.reserve(std::min<std::size_t>(maximumBytes, 4096));
    std::array<std::uint8_t, 4096> buffer{};
    while (true) {
        ssize_t count = 0;
        do {
            count = read(file.get(), buffer.data(), buffer.size());
        } while (count < 0 && errno == EINTR);
        if (count < 0) {
            return std::nullopt;
        }
        if (count == 0) {
            return bytes;
        }
        const auto unsignedCount = static_cast<std::size_t>(count);
        if (bytes.size() > maximumBytes || unsignedCount > maximumBytes - bytes.size()) {
            return std::nullopt;
        }
        bytes.insert(bytes.end(), buffer.begin(), buffer.begin() + count);
    }
}

bool parseHex(std::string_view text, std::uint64_t& value) noexcept {
    if (text.empty()) {
        return false;
    }
    const auto result = std::from_chars(text.data(), text.data() + text.size(), value, 16);
    return result.ec == std::errc{} && result.ptr == text.data() + text.size();
}

std::string trimMapsPath(std::string path) {
    const auto first = path.find_first_not_of(' ');
    if (first == std::string::npos) {
        return {};
    }
    path.erase(0, first);
    constexpr std::string_view deletedSuffix = " (deleted)";
    if (path.size() >= deletedSuffix.size() &&
        path.compare(path.size() - deletedSuffix.size(), deletedSuffix.size(), deletedSuffix) == 0) {
        path.resize(path.size() - deletedSuffix.size());
    }
    return path;
}

std::string_view moduleBasename(std::string_view path) noexcept {
    const auto separator = path.find_last_of('/');
    auto name = separator == std::string_view::npos ? path : path.substr(separator + 1);
    if (name.size() >= kMemoryFilePrefix.size() &&
        name.compare(0, kMemoryFilePrefix.size(), kMemoryFilePrefix) == 0) {
        name.remove_prefix(kMemoryFilePrefix.size());
    }
    return name;
}

bool validModuleName(std::string_view moduleName) noexcept {
    return !moduleName.empty() && moduleName.size() <= kMaximumModuleNameBytes;
}

std::size_t pageSize() noexcept {
    static const std::size_t value = [] {
        const auto configured = sysconf(_SC_PAGESIZE);
        return configured > 0
                   ? static_cast<std::size_t>(configured)
                   : kFallbackPageSize;
    }();
    return value;
}

bool validMemoryRange(std::uint64_t address, std::size_t size) noexcept {
    if (address == 0 || size > kMaximumMemoryTransferBytes ||
        address > static_cast<std::uint64_t>(std::numeric_limits<std::uintptr_t>::max()) ||
        address > static_cast<std::uint64_t>(std::numeric_limits<off64_t>::max())) {
        return false;
    }
    if (size == 0) {
        return true;
    }
    return static_cast<std::uint64_t>(size - 1) <=
           static_cast<std::uint64_t>(std::numeric_limits<std::uintptr_t>::max()) - address;
}

template <typename LocalPointer, typename Transfer>
bool transferProcessVm(
    std::int32_t pid,
    std::uint64_t address,
    LocalPointer localBuffer,
    std::size_t size,
    Transfer transfer) noexcept {
    const auto systemPageSize = pageSize();
    std::size_t completed = 0;
    while (completed < size) {
        std::array<iovec, kMaximumIovecsPerCall> localVectors{};
        std::array<iovec, kMaximumIovecsPerCall> remoteVectors{};
        std::size_t vectorCount = 0;
        std::size_t expected = 0;

        while (completed + expected < size && vectorCount < localVectors.size()) {
            const auto remoteAddress = address + completed + expected;
            const auto pageRemaining = systemPageSize - static_cast<std::size_t>(remoteAddress % systemPageSize);
            const auto length = std::min(pageRemaining, size - completed - expected);
            localVectors[vectorCount] = {
                const_cast<std::uint8_t*>(localBuffer + completed + expected),
                length,
            };
            remoteVectors[vectorCount] = {
                reinterpret_cast<void*>(static_cast<std::uintptr_t>(remoteAddress)),
                length,
            };
            expected += length;
            ++vectorCount;
        }

        ssize_t transferred = 0;
        do {
            transferred = transfer(
                static_cast<pid_t>(pid),
                localVectors.data(),
                static_cast<unsigned long>(vectorCount),
                remoteVectors.data(),
                static_cast<unsigned long>(vectorCount));
        } while (transferred < 0 && errno == EINTR);
        if (transferred < 0 || static_cast<std::size_t>(transferred) != expected) {
            return false;
        }
        completed += expected;
    }
    return true;
}

template <typename Buffer, typename Transfer>
bool transferProcMem(
    std::int32_t pid,
    std::uint64_t address,
    Buffer buffer,
    std::size_t size,
    int openFlags,
    Transfer transfer) noexcept {
    const auto path = procPath(pid, "mem");
    UniqueFd memory(open(path.c_str(), openFlags | O_CLOEXEC));
    if (!memory) {
        return false;
    }

    const auto systemPageSize = pageSize();
    std::size_t completed = 0;
    while (completed < size) {
        const auto remoteAddress = address + completed;
        const auto pageRemaining = systemPageSize - static_cast<std::size_t>(remoteAddress % systemPageSize);
        const auto requested = std::min(pageRemaining, size - completed);
        std::size_t pageCompleted = 0;
        while (pageCompleted < requested) {
            ssize_t transferred = 0;
            do {
                transferred = transfer(
                    memory.get(),
                    buffer + completed + pageCompleted,
                    requested - pageCompleted,
                    static_cast<off64_t>(remoteAddress + pageCompleted));
            } while (transferred < 0 && errno == EINTR);
            if (transferred <= 0) {
                return false;
            }
            pageCompleted += static_cast<std::size_t>(transferred);
        }
        completed += requested;
    }
    return true;
}

}

bool modulePathMatches(
    std::string_view mappedPath,
    std::string_view requestedName) noexcept {
    return !mappedPath.empty() &&
           !requestedName.empty() &&
           moduleBasename(mappedPath) == moduleBasename(requestedName);
}

std::vector<std::int32_t> scanProcessIds() {
    UniqueDirectory directory(opendir("/proc"));
    if (!directory) {
        return {};
    }

    std::vector<std::int32_t> pids;
    while (const auto* entry = readdir(directory.get())) {
        std::int32_t pid = 0;
        if (parseDecimalPid(entry->d_name, pid)) {
            pids.push_back(pid);
        }
    }
    std::sort(pids.begin(), pids.end());
    pids.erase(std::unique(pids.begin(), pids.end()), pids.end());
    return pids;
}

std::optional<std::string> readProcessName(std::int32_t pid) {
    if (!validPid(pid)) {
        return std::nullopt;
    }
    auto bytes = readLimitedFile(procPath(pid, "cmdline"), kMaximumProcessNameBytes);
    if (!bytes || bytes->empty()) {
        return std::nullopt;
    }
    const auto terminator = std::find(bytes->begin(), bytes->end(), std::uint8_t{0});
    if (terminator == bytes->begin()) {
        return std::nullopt;
    }
    return std::string(reinterpret_cast<const char*>(bytes->data()), static_cast<std::size_t>(terminator - bytes->begin()));
}

std::optional<std::int64_t> readProcessStartTicks(std::int32_t pid) {
    if (!validPid(pid)) {
        return std::nullopt;
    }
    auto bytes = readLimitedFile(procPath(pid, "stat"), kMaximumProcessNameBytes);
    if (!bytes || bytes->empty()) {
        return std::nullopt;
    }

    const std::string stat(reinterpret_cast<const char*>(bytes->data()), bytes->size());
    const auto commandEnd = stat.rfind(')');
    if (commandEnd == std::string::npos || commandEnd + 2 >= stat.size() || stat[commandEnd + 1] != ' ') {
        return std::nullopt;
    }

    std::istringstream fields(stat.substr(commandEnd + 2));
    std::string state;
    if (!(fields >> state) || state.size() != 1) {
        return std::nullopt;
    }

    std::string value;
    for (int field = 4; field <= 22; ++field) {
        if (!(fields >> value)) {
            return std::nullopt;
        }
    }
    std::uint64_t ticks = 0;
    const auto parsed = std::from_chars(value.data(), value.data() + value.size(), ticks, 10);
    if (parsed.ec != std::errc{} || parsed.ptr != value.data() + value.size() ||
        ticks > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max())) {
        return std::nullopt;
    }
    return static_cast<std::int64_t>(ticks);
}

std::vector<ProcessMapRegion> readProcessMaps(std::int32_t pid) {
    if (!validPid(pid)) {
        return {};
    }
    std::ifstream maps(procPath(pid, "maps"));
    if (!maps) {
        return {};
    }

    std::vector<ProcessMapRegion> regions;
    std::string line;
    std::size_t consumedBytes = 0;
    while (std::getline(maps, line)) {
        consumedBytes += line.size() + 1;
        if (consumedBytes > kMaximumProcFileBytes) {
            return {};
        }

        std::istringstream fields(line);
        std::string range;
        std::string permissions;
        std::string fileOffsetText;
        std::string device;
        std::string inode;
        if (!(fields >> range >> permissions >> fileOffsetText >> device >> inode)) {
            continue;
        }
        std::string path;
        std::getline(fields, path);
        path = trimMapsPath(std::move(path));

        const auto rangeSeparator = range.find('-');
        if (rangeSeparator == std::string::npos) {
            continue;
        }
        std::uint64_t start = 0;
        std::uint64_t end = 0;
        std::uint64_t fileOffset = 0;
        if (!parseHex(std::string_view(range).substr(0, rangeSeparator), start) ||
            !parseHex(std::string_view(range).substr(rangeSeparator + 1), end) ||
            !parseHex(fileOffsetText, fileOffset) || start >= end) {
            continue;
        }
        regions.push_back({
            start,
            end,
            fileOffset,
            !permissions.empty() && permissions[0] == 'r',
            permissions.size() > 1 && permissions[1] == 'w',
            permissions.size() > 2 && permissions[2] == 'x',
            std::move(path),
        });
    }
    return regions;
}

std::optional<std::uint64_t> findModuleBase(
    const std::vector<ProcessMapRegion>& regions,
    const std::string& moduleName) {
    if (!validModuleName(moduleName)) {
        return std::nullopt;
    }

    std::vector<const ProcessMapRegion*> candidates;
    std::optional<std::uint64_t> archiveBase;
    for (const auto& region : regions) {
        if (!modulePathMatches(region.path, moduleName)) {
            continue;
        }

        if (region.path.find("!/") != std::string::npos) {
            archiveBase = archiveBase ? std::min(*archiveBase, region.start) : region.start;
            continue;
        }
        if (region.fileOffset == 0 && region.start > 0 &&
            region.start <= static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max())) {
            candidates.push_back(&region);
        }
    }
    if (candidates.empty()) {
        return archiveBase;
    }

    const ProcessMapRegion* best = candidates.front();
    std::size_t bestScore = 0;
    std::uint64_t bestDeviation = std::numeric_limits<std::uint64_t>::max();
    for (const auto* candidate : candidates) {
        std::size_t score = 0;
        std::uint64_t deviation = 0;
        for (const auto& region : regions) {
            if (region.path != candidate->path || region.fileOffset == 0 || region.start <= candidate->start) {
                continue;
            }
            const auto relativeStart = region.start - candidate->start;
            const auto difference = relativeStart > region.fileOffset
                                        ? relativeStart - region.fileOffset
                                        : region.fileOffset - relativeStart;
            if (difference <= kMaximumLoadSegmentOffsetDelta) {
                ++score;
                deviation += difference;
            }
        }
        if (score > bestScore ||
            (score == bestScore && deviation < bestDeviation) ||
            (score == bestScore && deviation == bestDeviation && candidate->start < best->start)) {
            best = candidate;
            bestScore = score;
            bestDeviation = deviation;
        }
    }
    return best->start;
}

std::optional<std::uint64_t> findModuleBase(std::int32_t pid, const std::string& moduleName) {
    if (!validPid(pid) || !validModuleName(moduleName)) {
        return std::nullopt;
    }
    return findModuleBase(readProcessMaps(pid), moduleName);
}

bool readProcessMemory(
    std::int32_t pid,
    std::uint64_t address,
    std::uint8_t* destination,
    std::size_t size) noexcept {
    if (!validPid(pid) || destination == nullptr || !validMemoryRange(address, size)) {
        return false;
    }
    if (size == 0) {
        return true;
    }
    if (transferProcessVm(
            pid,
            address,
            destination,
            size,
            [](pid_t target, const iovec* local, unsigned long localCount, const iovec* remote, unsigned long remoteCount) {
                return process_vm_readv(target, local, localCount, remote, remoteCount, 0);
            })) {
        return true;
    }
    return transferProcMem(
        pid,
        address,
        destination,
        size,
        O_RDONLY,
        [](int file, std::uint8_t* buffer, std::size_t count, off64_t offset) {
            return pread64(file, buffer, count, offset);
        });
}

bool writeProcessMemory(
    std::int32_t pid,
    std::uint64_t address,
    const std::uint8_t* source,
    std::size_t size) noexcept {
    if (!validPid(pid) || source == nullptr || !validMemoryRange(address, size)) {
        return false;
    }
    if (size == 0) {
        return true;
    }
    if (transferProcessVm(
            pid,
            address,
            source,
            size,
            [](pid_t target, const iovec* local, unsigned long localCount, const iovec* remote, unsigned long remoteCount) {
                return process_vm_writev(target, local, localCount, remote, remoteCount, 0);
            })) {
        return true;
    }
    return transferProcMem(
        pid,
        address,
        source,
        size,
        O_RDWR,
        [](int file, const std::uint8_t* buffer, std::size_t count, off64_t offset) {
            return pwrite64(file, buffer, count, offset);
        });
}

}
