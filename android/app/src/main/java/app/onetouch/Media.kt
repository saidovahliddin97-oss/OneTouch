package app.onetouch

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import android.webkit.MimeTypeMap

data class MediaItem(val uri: Uri, val isVideo: Boolean)

object Media {
    /** Most recent photos and videos, newest first. */
    fun recent(ctx: Context, limit: Int = 600): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val proj = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE)
        val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE},${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        ctx.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), proj, sel, null,
            "${MediaStore.Files.FileColumns.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val id = c.getLong(0)
                val video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                out += MediaItem(ContentUris.withAppendedId(base, id), video)
            }
        }
        return out
    }

    fun thumbnail(ctx: Context, uri: Uri, px: Int): Bitmap? =
        runCatching { ctx.contentResolver.loadThumbnail(uri, Size(px, px), null) }.getOrNull()

    /** Screen-sized bitmap for the full-screen viewer. */
    fun large(ctx: Context, item: MediaItem): Bitmap? = runCatching {
        if (item.isVideo) return@runCatching ctx.contentResolver.loadThumbnail(item.uri, Size(1600, 1600), null)
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, item.uri)) { dec, info, _ ->
            val max = 2400
            val s = maxOf(info.size.width, info.size.height)
            if (s > max) {
                val f = max.toFloat() / s
                dec.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
            }
        }
    }.getOrNull()

    fun nameAndSize(ctx: Context, uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return (name ?: uri.lastPathSegment ?: "file") to size
    }

    /** A job that opens the stream lazily (we hold media permission for it). */
    fun job(ctx: Context, uri: Uri): SendJob {
        val (name, size) = nameAndSize(ctx, uri)
        val app = ctx.applicationContext
        return SendJob(name, size, uri) { app.contentResolver.openInputStream(uri) ?: error("не удалось открыть $name") }
    }

    /**
     * Creates a pending MediaStore entry: photos → Pictures/OneTouch, videos →
     * Movies/OneTouch (both visible in the gallery), anything else → Download/OneTouch.
     */
    fun createTarget(ctx: Context, name: String): Uri {
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val (collection, dir) = when {
            mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
            mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/OneTouch")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return ctx.contentResolver.insert(collection, values) ?: error("не удалось создать файл")
    }

    fun publish(ctx: Context, uri: Uri) {
        ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    fun mimeOf(ctx: Context, uri: Uri): String = ctx.contentResolver.getType(uri) ?: "*/*"
}
