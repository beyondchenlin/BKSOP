package com.wangzan101.mika.core.workflow

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * One product-owned ledger, separate from the learned-route cache. Never evicts user definitions.
 * Syncs the temporary file and atomically renames it, protecting readers from partial process-crash writes.
 * This portable implementation does not fsync the directory; it does not promise power-loss durability.
 */
class FileWorkflowStore(private val directory: File) : WorkflowStore {
    private val file get() = File(directory, "workflows-v1.json")

    @Synchronized
    override fun load(): WorkflowSnapshot {
        if (!file.exists()) return WorkflowSnapshot()
        return try {
            WorkflowCodec.json.decodeFromString<WorkflowSnapshot>(file.readText(Charsets.UTF_8)).also {
                if (it.formatVersion != 1) throw WorkflowException("storage_version", "流程存储格式不受支持，原文件已保留")
                if (it.storageIssue != null) throw WorkflowException("storage_corrupt", "流程存储含错误状态，原文件已保留")
            }
        } catch (e: WorkflowException) { throw e }
        catch (_: Exception) { throw WorkflowException("storage_corrupt", "流程存储无法读取或解析，原文件已保留，未创建空记录覆盖它") }
    }

    @Synchronized
    override fun save(snapshot: WorkflowSnapshot) {
        if (snapshot.formatVersion != 1 || snapshot.storageIssue != null)
            throw WorkflowException("storage_unavailable", "不能用不完整状态覆盖流程存储")
        if (!directory.isDirectory && !directory.mkdirs())
            throw WorkflowException("storage_unavailable", "无法创建流程存储目录")
        val temporary = try { File.createTempFile("workflows-", ".tmp", directory) }
        catch (_: Exception) { throw WorkflowException("storage_unavailable", "无法创建流程保存文件") }
        try {
            val bytes = WorkflowCodec.json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
            FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
            // Refuse a non-atomic fallback: acknowledging a partially replaced task ledger could duplicate effects.
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            throw WorkflowException("storage_write_failed", "流程保存失败，未确认本次修改成功")
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}

/** Useful for deterministic core tests; Android production uses FileWorkflowStore. */
class MemoryWorkflowStore : WorkflowStore {
    private var saved = WorkflowCodec.json.encodeToString(WorkflowSnapshot())
    @Synchronized override fun load(): WorkflowSnapshot = WorkflowCodec.json.decodeFromString(saved)
    @Synchronized override fun save(snapshot: WorkflowSnapshot) { saved = WorkflowCodec.json.encodeToString(snapshot) }
}
