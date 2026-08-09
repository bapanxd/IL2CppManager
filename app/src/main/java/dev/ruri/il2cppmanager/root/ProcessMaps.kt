package dev.ruri.il2cppmanager.root

import dev.ruri.il2cppmanager.ipc.IpcContract
import java.io.File
import java.io.IOException
import java.util.Collections

internal data class MemoryRegion(
    val start: Long,
    val endExclusive: Long,
    val readable: Boolean,
    val writable: Boolean,
    val path: String?,
    val mappingName: String?,
) {
    val sizeBytes: Long
        get() = endExclusive - start

    fun contains(address: Long, size: Int): Boolean {
        if (address <= 0 || size <= 0) {
            return false
        }
        val end = try {
            Math.addExact(address, size.toLong())
        } catch (_: ArithmeticException) {
            return false
        }
        return address >= start && end <= endExclusive
    }
}

internal class ProcessMaps private constructor(regions: List<MemoryRegion>) {
    private val regions = Collections.unmodifiableList(regions.toList())

    val readableRegions: List<MemoryRegion> =
        Collections.unmodifiableList(this.regions.filter(MemoryRegion::readable))

    val paths: List<String> = Collections.unmodifiableList(
        this.regions.mapNotNull(MemoryRegion::path).distinct(),
    )

    fun canRead(address: Long, size: Int): Boolean =
        regions.any { it.readable && it.contains(address, size) }

    fun canWrite(address: Long, size: Int): Boolean =
        regions.any { it.writable && it.contains(address, size) }

    companion object {
        private const val MAPS_FILE_NAME = "maps"
        private const val PROC_ROOT = "/proc"
        private const val MAP_COLUMNS = 6
        private val COLUMN_SEPARATOR = Regex("\\s+")

        fun load(pid: Int): ProcessMaps {
            if (pid <= 0) {
                throw ServiceFault(IpcContract.Error.INVALID_ARGUMENT, "Invalid process ID")
            }

            val mapsFile = File(File(PROC_ROOT, pid.toString()), MAPS_FILE_NAME)
            val regions = try {
                mapsFile.useLines { lines -> lines.mapNotNull(::parseRegion).toList() }
            } catch (error: IOException) {
                throw ServiceFault(
                    IpcContract.Error.MEMORY_ACCESS_DENIED,
                    "Unable to read target memory map",
                    error,
                )
            }
            if (regions.isEmpty()) {
                throw ServiceFault(IpcContract.Error.PROCESS_NOT_FOUND, "Target process has no memory map")
            }
            return ProcessMaps(regions)
        }

        private fun parseRegion(line: String): MemoryRegion? {
            val columns = line.trim().split(COLUMN_SEPARATOR, limit = MAP_COLUMNS)
            if (columns.size < 2) {
                return null
            }
            val range = columns[0].split('-', limit = 2)
            if (range.size != 2) {
                return null
            }
            val start = range[0].toLongOrNull(radix = 16) ?: return null
            val end = range[1].toLongOrNull(radix = 16) ?: return null
            if (start <= 0 || end <= start) {
                return null
            }
            val permissions = columns[1]
            val mappingName = columns.getOrNull(5)
                ?.removeSuffix(" (deleted)")
                ?.takeIf(String::isNotBlank)
            val path = mappingName?.takeIf { it.startsWith('/') }
            return MemoryRegion(
                start = start,
                endExclusive = end,
                readable = permissions.getOrNull(0) == 'r',
                writable = permissions.getOrNull(1) == 'w',
                path = path,
                mappingName = mappingName,
            )
        }
    }
}
