package com.shaun.agentbox.sandbox

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * DocumentsProvider 实现。
 *
 * 让 AgentBox 的 ai_workspace 目录出现在系统"文件"应用的侧边栏中，
 * 类似 Termux 在系统文件管理器中的表现。
 *
 * Document ID 约定：
 * - "root" → workspace 根目录
 * - 其他  → 相对于 workspace 根目录的相对路径（如 "code/main.py"）
 */
class WorkspaceProvider : android.provider.DocumentsProvider() {

    private lateinit var sandboxManager: SandboxManager

    private val appContext get() = getContext()!!

    private val rootDir: File get() = sandboxManager.workspaceDir

    override fun onCreate(): Boolean {
        sandboxManager = SandboxManager(appContext)
        return true
    }

    // ===================== 列定义 =====================

    private val defaultRootProjection = arrayOf(
        DocumentsContract.Root.COLUMN_ROOT_ID,
        DocumentsContract.Root.COLUMN_FLAGS,
        DocumentsContract.Root.COLUMN_ICON,
        DocumentsContract.Root.COLUMN_TITLE,
        DocumentsContract.Root.COLUMN_SUMMARY,
        DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        DocumentsContract.Root.COLUMN_MIME_TYPES
    )

    private val defaultDocumentProjection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE
    )

    // ===================== 根目录 =====================

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(resolveProjection(projection, defaultRootProjection))
        cursor.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, "agentbox_workspace")
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            add(DocumentsContract.Root.COLUMN_TITLE, "AgentBox")
            add(DocumentsContract.Root.COLUMN_SUMMARY, "AI Workspace")
            add(DocumentsContract.Root.COLUMN_ICON, android.R.drawable.ic_menu_manage)
            add(
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                        DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD or
                        DocumentsContract.Root.FLAG_LOCAL_ONLY
            )
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
        }
        return cursor
    }

    // ===================== 文档查询 =====================

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(resolveProjection(projection, defaultDocumentProjection))
        val file = resolveDocId(documentId)
        addFileRow(cursor, documentId ?: ROOT_DOC_ID, file)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(resolveProjection(projection, defaultDocumentProjection))
        val parentFile = resolveDocId(parentDocumentId)

        parentFile.listFiles()
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
            ?.forEach { child ->
                val childDocId = if (parentDocumentId == ROOT_DOC_ID || parentDocumentId == null) {
                    child.name
                } else {
                    "$parentDocumentId/${child.name}"
                }
                addFileRow(cursor, childDocId, child)
            }

        // 通知系统文件管理器该 URI 可监听变化
        cursor.setNotificationUri(
            appContext.contentResolver,
            DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId ?: ROOT_DOC_ID)
        )

        return cursor
    }

    // ===================== 文件操作 =====================

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = resolveDocId(documentId)
        if (!file.exists()) throw FileNotFoundException("Document $documentId not found")
        val accessMode = ParcelFileDescriptor.parseMode(mode ?: "r")
        return ParcelFileDescriptor.open(file, accessMode)
    }

    override fun createDocument(parentDocumentId: String?, mimeType: String?, displayName: String?): String {
        val parentFile = resolveDocId(parentDocumentId)
        val name = displayName ?: "untitled"
        val newFile = File(parentFile, name)

        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            newFile.mkdirs()
        } else {
            newFile.parentFile?.mkdirs()
            newFile.createNewFile()
        }

        notifyChange(parentDocumentId)

        return if (parentDocumentId == ROOT_DOC_ID || parentDocumentId == null) {
            name
        } else {
            "$parentDocumentId/$name"
        }
    }

    override fun deleteDocument(documentId: String?) {
        val file = resolveDocId(documentId)
        val parentDocId = documentId?.substringBeforeLast('/', ROOT_DOC_ID) ?: ROOT_DOC_ID

        if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }

        notifyChange(parentDocId)
    }

    override fun renameDocument(documentId: String?, displayName: String?): String? {
        if (documentId == null || displayName == null) return null

        val file = resolveDocId(documentId)
        val newFile = File(file.parentFile, displayName)
        if (!file.renameTo(newFile)) return null

        val parentDocId = documentId.substringBeforeLast('/', ROOT_DOC_ID)
        notifyChange(parentDocId)

        return if (parentDocId == ROOT_DOC_ID) {
            displayName
        } else {
            "$parentDocId/$displayName"
        }
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        if (parentDocumentId == null || documentId == null) return false
        if (parentDocumentId == ROOT_DOC_ID) return true
        return documentId.startsWith("$parentDocumentId/")
    }

    // ===================== 辅助方法 =====================

    /**
     * 将 Document ID 解析为实际的 File 对象。
     * 使用相对路径作为 Document ID，避免暴露绝对路径。
     */
    private fun resolveDocId(documentId: String?): File {
        if (documentId == null || documentId == ROOT_DOC_ID) {
            return rootDir
        }
        return sandboxManager.resolveFile(documentId)
    }

    private fun addFileRow(cursor: MatrixCursor, docId: String, file: File) {
        var flags = 0
        if (file.isDirectory) {
            flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        }
        if (file.canWrite()) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                    DocumentsContract.Document.FLAG_SUPPORTS_RENAME
        }

        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name)
            add(DocumentsContract.Document.COLUMN_SIZE, if (file.isFile) file.length() else null)
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, getMimeType(file))
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(DocumentsContract.Document.COLUMN_FLAGS, flags)
        }
    }

    private fun getMimeType(file: File): String {
        if (file.isDirectory) return DocumentsContract.Document.MIME_TYPE_DIR
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun notifyChange(parentDocId: String?) {
        val uri = DocumentsContract.buildChildDocumentsUri(
            AUTHORITY,
            parentDocId ?: ROOT_DOC_ID
        )
        appContext.contentResolver.notifyChange(uri, null)
    }

    private fun resolveProjection(requested: Array<out String>?, default: Array<String>): Array<String> {
        return if (requested.isNullOrEmpty()) default else Array(requested.size) { requested[it] }
    }

    companion object {
        const val AUTHORITY = "com.shaun.agentbox.documents"
        const val ROOT_DOC_ID = "root"
    }
}
