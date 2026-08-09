package dev.ruri.il2cppmanager.root

import android.util.Log
import dev.ruri.il2cppmanager.ipc.IpcContract
import dev.ruri.il2cppmanager.nativebridge.NativeEngine
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile

internal class MetadataLocator {
    fun open(pid: Int, maps: ProcessMaps): Long {
        var sourceFound = false
        val mappedFile = openMappedFile(maps)
        if (mappedFile.handle > 0) {
            return mappedFile.handle
        }
        sourceFound = mappedFile.found
        val apkEntry = openApkEntry(maps)
        if (apkEntry.handle > 0) {
            return apkEntry.handle
        }
        sourceFound = sourceFound || apkEntry.found
        val mappedMemory = openMappedMemory(pid, maps)
        if (mappedMemory.handle > 0) {
            return mappedMemory.handle
        }
        sourceFound = sourceFound || mappedMemory.found
        if (sourceFound) {
            throw ServiceFault(
                IpcContract.Error.METADATA_UNSUPPORTED,
                "IL2CPP metadata is invalid or unsupported",
            )
        }
        throw ServiceFault(IpcContract.Error.METADATA_NOT_FOUND, "IL2CPP metadata was not found")
    }

    private fun openMappedFile(maps: ProcessMaps): MetadataOpenResult {
        var found = false
        for (path in maps.paths.filter { it.endsWith(METADATA_FILE_NAME) }) {
            val file = File(path)
            if (!file.isFile) {
                continue
            }
            found = true
            if (file.length() !in MINIMUM_METADATA_HEADER_BYTES..MAX_METADATA_BYTES) {
                continue
            }
            try {
                file.inputStream().use { input ->
                    val metadata = readMetadata(input, file.length()) ?: return@use
                    val handle = NativeEngine.openMetadata(metadata, allowErasedMagic = false)
                    if (handle > 0) {
                        return MetadataOpenResult(handle, true)
                    }
                }
            } catch (_: IOException) {
                continue
            }
        }
        return MetadataOpenResult(found = found)
    }

    private fun openMappedMemory(pid: Int, maps: ProcessMaps): MetadataOpenResult {
        var found = false
        val candidates = maps.readableRegions
            .asSequence()
            .filter { region ->
                region.sizeBytes >= MINIMUM_METADATA_HEADER_BYTES &&
                    region.sizeBytes <= MAX_METADATA_BYTES
            }
            .mapNotNull { region ->
                memoryRegionPriority(region)?.let { priority -> MemoryCandidate(region, priority) }
            }
            .sortedWith(
                compareBy<MemoryCandidate>(MemoryCandidate::priority)
                    .thenByDescending { candidate -> candidate.region.sizeBytes },
            )
            .take(MAX_MEMORY_REGION_CANDIDATES)

        for (candidate in candidates) {
            val region = candidate.region
            val reportFailure = candidate.priority <= ZERO_DEVICE_REGION_PRIORITY
            val probeSize = minOf(region.sizeBytes, METADATA_PROBE_BYTES.toLong()).toInt()
            val probeBytes = NativeEngine.readMemory(pid, region.start, probeSize)
                ?.takeIf { bytes -> bytes.size == probeSize }
            if (probeBytes == null) {
                if (reportFailure) {
                    Log.w(LOG_TAG, "Unable to read metadata candidate at ${region.start.toString(HEX_RADIX)}")
                }
                continue
            }
            val byteCount = NativeEngine.probeMetadataSize(
                probeBytes,
                region.sizeBytes,
                allowErasedMagic = true,
            )
            if (byteCount <= 0 || byteCount > MAX_METADATA_BYTES || byteCount > Int.MAX_VALUE) {
                if (reportFailure) {
                    Log.d(LOG_TAG, "Rejected metadata candidate at ${region.start.toString(HEX_RADIX)}")
                }
                continue
            }
            found = true
            Log.i(
                LOG_TAG,
                "Reading $byteCount metadata bytes at ${region.start.toString(HEX_RADIX)}",
            )
            val metadata = readMappedMetadata(pid, region, probeBytes, byteCount.toInt())
            if (metadata == null) {
                Log.w(LOG_TAG, "Unable to read complete in-memory metadata")
                continue
            }
            val handle = NativeEngine.openMetadata(metadata, allowErasedMagic = true)
            if (handle > 0) {
                return MetadataOpenResult(handle, true)
            }
        }
        return MetadataOpenResult(found = found)
    }

    private fun memoryRegionPriority(region: MemoryRegion): Int? {
        val mappingName = region.mappingName
        return when {
            mappingName?.contains(METADATA_FILE_NAME, ignoreCase = true) == true ->
                METADATA_NAMED_REGION_PRIORITY

            region.path == ZERO_DEVICE_PATH -> ZERO_DEVICE_REGION_PRIORITY
            mappingName == null || mappingName.startsWith(ANONYMOUS_MAPPING_PREFIX) ->
                ANONYMOUS_REGION_PRIORITY

            else -> null
        }
    }

    private fun readMappedMetadata(
        pid: Int,
        region: MemoryRegion,
        probeBytes: ByteArray,
        byteCount: Int,
    ): ByteArray? {
        if (!region.contains(region.start, byteCount)) {
            return null
        }

        val metadata = ByteArray(byteCount)
        val copiedBytes = minOf(probeBytes.size, metadata.size)
        probeBytes.copyInto(metadata, endIndex = copiedBytes)
        var offset = copiedBytes
        while (offset < metadata.size) {
            val chunkSize = minOf(MEMORY_READ_CHUNK_BYTES, metadata.size - offset)
            val address = try {
                Math.addExact(region.start, offset.toLong())
            } catch (_: ArithmeticException) {
                return null
            }
            val chunk = NativeEngine.readMemory(pid, address, chunkSize)
                ?.takeIf { bytes -> bytes.size == chunkSize }
            if (chunk == null) {
                Log.w(LOG_TAG, "Metadata memory read failed at byte offset $offset")
                return null
            }
            chunk.copyInto(metadata, destinationOffset = offset)
            offset += chunkSize
        }

        return metadata
    }

    private fun openApkEntry(maps: ProcessMaps): MetadataOpenResult {
        val paths = archivePaths(maps)
        val suffixScanBudget = ArchiveEntryScanBudget(MAX_TOTAL_ARCHIVE_ENTRY_SCAN_COUNT)
        var found = false
        for (lookup in MetadataEntryLookup.entries) {
            for (path in paths) {
                if (lookup == MetadataEntryLookup.SUFFIX && !suffixScanBudget.hasRemaining) {
                    break
                }
                val result = openApkEntry(path, lookup, suffixScanBudget)
                if (result.handle > 0) {
                    return result
                }
                found = found || result.found
            }
        }
        return MetadataOpenResult(found = found)
    }

    private fun openApkEntry(
        path: String,
        lookup: MetadataEntryLookup,
        suffixScanBudget: ArchiveEntryScanBudget,
    ): MetadataOpenResult {
        var found = false
        return try {
            ZipFile(path).use { archive ->
                for (entry in metadataEntries(archive, lookup, suffixScanBudget)) {
                    found = true
                    if (entry.size > MAX_METADATA_BYTES) {
                        continue
                    }
                    val availableBytes = entry.size.takeIf { it >= 0 } ?: MAX_METADATA_BYTES
                    val metadata = archive.getInputStream(entry).use { input ->
                        readMetadata(input, availableBytes)
                    } ?: continue
                    val handle = NativeEngine.openMetadata(metadata, allowErasedMagic = false)
                    if (handle > 0) {
                        return MetadataOpenResult(handle, true)
                    }
                }
                MetadataOpenResult(found = found)
            }
        } catch (_: ZipException) {
            MetadataOpenResult(found = found)
        } catch (_: IOException) {
            MetadataOpenResult(found = found)
        }
    }

    private fun metadataEntries(
        archive: ZipFile,
        lookup: MetadataEntryLookup,
        suffixScanBudget: ArchiveEntryScanBudget,
    ) = when (lookup) {
        MetadataEntryLookup.EXACT -> archive.getEntry(METADATA_ENTRY_PATH)
            ?.takeUnless { entry -> entry.isDirectory }
            ?.let(::sequenceOf)
            ?: emptySequence()
        MetadataEntryLookup.SUFFIX -> archive.entries().asSequence()
            .takeWhile { suffixScanBudget.consume() }
            .filter { entry ->
                !entry.isDirectory &&
                    entry.name.length <= MAX_ARCHIVE_ENTRY_NAME_LENGTH &&
                    entry.name != METADATA_ENTRY_PATH &&
                    (entry.name == METADATA_ENTRY_SUFFIX ||
                        entry.name.endsWith("/$METADATA_ENTRY_SUFFIX"))
            }
            .take(MAX_METADATA_ENTRY_CANDIDATES)
    }

    private fun archivePaths(maps: ProcessMaps): List<String> {
        val mappedArchives = maps.paths.mapNotNull(::normalizeApkPath).distinct()
        val candidates = LinkedHashSet<String>()
        val scannedDirectories = HashSet<String>()
        candidates += mappedArchives.take(MAX_APK_ARCHIVE_CANDIDATES)
        for (path in mappedArchives) {
            if (candidates.size >= MAX_APK_ARCHIVE_CANDIDATES) {
                break
            }
            val directory = File(path).parentFile ?: continue
            if (!scannedDirectories.add(directory.path)) {
                continue
            }
            val siblings = directory.listFiles { file ->
                file.isFile && file.name.endsWith(APK_EXTENSION, ignoreCase = true)
            }?.sortedBy(File::getName).orEmpty()
            for (sibling in siblings) {
                val canonicalPath = canonicalApkPath(sibling) ?: continue
                if (File(canonicalPath).parentFile != directory) {
                    continue
                }
                candidates += canonicalPath
                if (candidates.size >= MAX_APK_ARCHIVE_CANDIDATES) {
                    break
                }
            }
        }
        return candidates.toList()
    }

    private fun normalizeApkPath(mappedPath: String): String? {
        var searchStart = 0
        while (searchStart < mappedPath.length) {
            val extensionEnd = mappedPath.indexOf(APK_EXTENSION, searchStart, ignoreCase = true)
            if (extensionEnd < 0) {
                return null
            }
            val endExclusive = extensionEnd + APK_EXTENSION.length
            val boundary = mappedPath.getOrNull(endExclusive)
            if (boundary == null || boundary == '!' || boundary == '/') {
                canonicalApkPath(File(mappedPath.substring(0, endExclusive)))?.let { return it }
            }
            searchStart = endExclusive
        }
        return null
    }

    private fun canonicalApkPath(file: File): String? = try {
        file.canonicalFile
            .takeIf { candidate ->
                candidate.isFile && candidate.name.endsWith(APK_EXTENSION, ignoreCase = true)
            }
            ?.path
    } catch (_: IOException) {
        null
    }

    private fun readMetadata(input: InputStream, availableBytes: Long): ByteArray? {
        if (availableBytes !in MINIMUM_METADATA_HEADER_BYTES..MAX_METADATA_BYTES) {
            return null
        }
        val probeSize = minOf(availableBytes, METADATA_PROBE_BYTES.toLong()).toInt()
        val probe = ByteArray(probeSize)
        if (!readFully(input, probe, 0, probe.size)) {
            return null
        }
        val byteCount = NativeEngine.probeMetadataSize(
            probe,
            availableBytes,
            allowErasedMagic = false,
        )
        if (byteCount <= 0 || byteCount > MAX_METADATA_BYTES || byteCount > Int.MAX_VALUE) {
            return null
        }
        val metadata = ByteArray(byteCount.toInt())
        val copiedBytes = minOf(probe.size, metadata.size)
        probe.copyInto(metadata, endIndex = copiedBytes)
        return metadata.takeIf { readFully(input, it, copiedBytes, it.size) }
    }

    private fun readFully(
        input: InputStream,
        destination: ByteArray,
        startIndex: Int,
        endIndex: Int,
    ): Boolean {
        var offset = startIndex
        while (offset < endIndex) {
            val count = input.read(destination, offset, endIndex - offset)
            if (count < 0) {
                return false
            }
            if (count == 0) {
                val value = input.read()
                if (value < 0) {
                    return false
                }
                destination[offset++] = value.toByte()
            } else {
                offset += count
            }
        }
        return true
    }

    private companion object {
        const val METADATA_FILE_NAME = "global-metadata.dat"
        const val METADATA_ENTRY_PATH = "assets/bin/Data/Managed/Metadata/global-metadata.dat"
        const val METADATA_ENTRY_SUFFIX = "bin/Data/Managed/Metadata/global-metadata.dat"
        const val APK_EXTENSION = ".apk"
        const val ZERO_DEVICE_PATH = "/dev/zero"
        const val ANONYMOUS_MAPPING_PREFIX = "[anon"
        const val MAX_METADATA_BYTES = 128L * 1_024L * 1_024L
        const val MEMORY_READ_CHUNK_BYTES = 1_024 * 1_024
        const val MAX_MEMORY_REGION_CANDIDATES = 256
        const val MAX_APK_ARCHIVE_CANDIDATES = 256
        const val MAX_TOTAL_ARCHIVE_ENTRY_SCAN_COUNT = 250_000
        const val MAX_METADATA_ENTRY_CANDIDATES = 8
        const val MAX_ARCHIVE_ENTRY_NAME_LENGTH = 4_096
        const val MINIMUM_METADATA_HEADER_BYTES = Long.SIZE_BYTES.toLong()
        const val METADATA_PROBE_BYTES = 4 * 1_024
        const val HEX_RADIX = 16
        const val LOG_TAG = "MetadataLocator"

        const val METADATA_NAMED_REGION_PRIORITY = 0
        const val ZERO_DEVICE_REGION_PRIORITY = 1
        const val ANONYMOUS_REGION_PRIORITY = 2

    }

    private data class MemoryCandidate(
        val region: MemoryRegion,
        val priority: Int,
    )

    private data class MetadataOpenResult(
        val handle: Long = 0,
        val found: Boolean = false,
    )

    private enum class MetadataEntryLookup {
        EXACT,
        SUFFIX,
    }

    private class ArchiveEntryScanBudget(limit: Int) {
        private var remaining = limit

        val hasRemaining: Boolean
            get() = remaining > 0

        fun consume(): Boolean {
            if (!hasRemaining) {
                return false
            }
            remaining -= 1
            return true
        }
    }

}
