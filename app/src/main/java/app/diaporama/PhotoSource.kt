package app.diaporama

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore

data class Album(val id: String, val name: String, val count: Int)

object PhotoSource {

    /** Toutes les photos des dossiers et albums choisis, sans doublon. */
    fun collect(context: Context, settings: Settings): List<Uri> {
        val out = LinkedHashSet<Uri>()
        settings.folders.forEach { runCatching { walkTree(context, Uri.parse(it), out) } }
        settings.photos.forEach { out.add(Uri.parse(it)) }
        if (settings.albums.isNotEmpty()) runCatching { queryAlbums(context, settings.albums, out) }
        return out.toList()
    }

    /** Parcourt récursivement un dossier choisi via le sélecteur système. */
    private fun walkTree(context: Context, tree: Uri, out: MutableSet<Uri>) {
        val stack = ArrayDeque<String>().apply { add(DocumentsContract.getTreeDocumentId(tree)) }
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE)
        while (stack.isNotEmpty()) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, stack.removeLast())
            context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val mime = c.getString(1) ?: continue
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> stack.add(id)
                        mime.startsWith("image/") -> out.add(DocumentsContract.buildDocumentUriUsingTree(tree, id))
                    }
                }
            }
        }
    }

    private fun queryAlbums(context: Context, ids: Set<String>, out: MutableSet<Uri>) {
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val sel = "${MediaStore.Images.Media.BUCKET_ID} IN (${ids.joinToString(",") { "?" }})"
        context.contentResolver.query(
            base, arrayOf(MediaStore.Images.Media._ID), sel, ids.toTypedArray(),
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )?.use { c ->
            while (c.moveToNext()) out.add(ContentUris.withAppendedId(base, c.getLong(0)))
        }
    }

    /** Albums de la galerie, triés par nombre de photos. */
    fun listAlbums(context: Context): List<Album> {
        val counts = HashMap<String, Pair<String, Int>>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: tr("Sans nom", "Untitled")
                counts[id] = name to (counts[id]?.second ?: 0) + 1
            }
        }
        return counts.map { (id, v) -> Album(id, v.first, v.second) }.sortedByDescending { it.count }
    }
}
