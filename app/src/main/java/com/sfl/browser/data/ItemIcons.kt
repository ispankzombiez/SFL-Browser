package com.sfl.browser.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.sfl.browser.R
import org.json.JSONObject
import java.util.Locale

data class ItemCatalogEntry(
    val name: String,
    val collection: String,
    val id: Int,
    val iconPath: String,
)

/**
 * Loads the Sunflower Land item icons that are bundled in `assets/sfl/`.
 *
 * Two ways to find an icon:
 *  - by **collection + id** — this is what the Community API gives us
 *    (`type=tradeable&collection=collectibles&id=601`, and the `collectibles-601`
 *    keys in marketplace activity), so the path is derived, no lookup needed;
 *  - by **item name** — what farm timers give us ("Kale", "Wood"), resolved
 *    through `assets/sfl/index.json`.
 *
 * Assets are laid out as `sfl/<collection>/<id>.<ext>` and were copied verbatim
 * from the game repo's `public/erc1155` (collectibles, incl. every resource) and
 * `public/wearables` metadata.
 */
object ItemIcons {

    /** Where `index.json` lives, relative to `assets/`. */
    private const val INDEX_ASSET = "sfl/index.json"
    private const val SPICE_RACK_ICON = "local:spice_rack"

    /** Prefix every indexed path with this to reach it from `assets/`. */
    private const val ASSET_PREFIX = "sfl/"

    private const val COLLECTION_COLLECTIBLES = "collectibles"
    private const val COLLECTION_WEARABLES = "wearables"

    /** Icons are 128px source art; this caps what we hand to a notification. */
    private const val DEFAULT_MAX_PX = 192

    @Volatile
    private var namesByLowercase: Map<String, String>? = null

    @Volatile
    private var pathsByName: Map<String, String>? = null

    /** ~4 MB of decoded icons, shared across notifications and list rows. */
    private val bitmapCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // ---------------------------------------------------------------- lookups

    /**
     * Asset path for an item name, e.g. `"Kale"` -> `"sfl/collectibles/211.webp"`.
     * Matching is case- and whitespace-insensitive; returns null when the item
     * has no bundled art (the caller falls back to its default icon).
     */
    fun pathForName(context: Context, name: String): String? {
        val key = name.trim().lowercase(Locale.ROOT)
        if (key.isEmpty()) return null
        if (key == "spice rack") return SPICE_RACK_ICON
        return nameIndex(context)[key]
    }

    /**
     * Asset path derived from the API's `collection` + `id`.
     *
     * `collection` is one of `collectibles`, `wearables`, `buds`, `pets`; only the
     * first two have bundled art, so `buds` and `pets` return null.
     */
    fun pathForId(collection: String, id: Int): String? {
        if (id < 0) return null
        return when (collection.trim().lowercase(Locale.ROOT)) {
            COLLECTION_COLLECTIBLES -> "$ASSET_PREFIX$COLLECTION_COLLECTIBLES/$id.webp"
            COLLECTION_WEARABLES -> "$ASSET_PREFIX$COLLECTION_WEARABLES/$id.png"
            else -> null
        }
    }

    /** Convenience for the `collectibles-601` / `wearables-12` keys the API uses. */
    fun pathForItemKey(context: Context, itemKey: String): String? {
        val dash = itemKey.indexOf('-')
        if (dash <= 0) return null
        val collection = itemKey.substring(0, dash)
        val id = itemKey.substring(dash + 1).toIntOrNull() ?: return null
        val path = pathForId(collection, id) ?: return null
        return if (assetExists(context, path)) path else null
    }

    /**
     * Display name for an id. Marketplace trades hand us
     * `{ collection: "collectibles", itemId: 641 }` and nothing human-readable,
     * so this walks the index backwards. Null when we ship no art for the item —
     * the caller then falls back to a generic "Item #641" label.
     */
    fun nameForId(context: Context, collection: String?, id: Int): String? {
        if (collection.isNullOrEmpty()) return null
        nameIndex(context) // builds the reverse map as a side effect
        val path = pathForId(collection, id) ?: return null
        return pathsByName?.get(path)
    }

    /** Named entries with bundled art for one collection, for item pickers. */
    fun catalogForCollection(context: Context, collection: String): List<ItemCatalogEntry> {
        nameIndex(context)
        val normalizedCollection = collection.trim().lowercase(Locale.ROOT)
        return pathsByName.orEmpty().mapNotNull { (path, name) ->
            val parts = path.split('/')
            if (parts.size != 3 || parts[0] != ASSET_PREFIX.trimEnd('/') ||
                parts[1] != normalizedCollection
            ) {
                return@mapNotNull null
            }
            val id = parts[2].substringBeforeLast('.').toIntOrNull() ?: return@mapNotNull null
            ItemCatalogEntry(name, normalizedCollection, id, path)
        }.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    // ---------------------------------------------------------------- loading

    /** Decoded icon, scaled down to [maxPx] on its longest edge. Null if absent. */
    fun bitmap(context: Context, path: String?, maxPx: Int = DEFAULT_MAX_PX): Bitmap? {
        if (path.isNullOrEmpty()) return null
        bitmapCache.get(path)?.let { return it }
        if (path == SPICE_RACK_ICON) {
            val art = context.getDrawable(R.drawable.ic_spice_rack) ?: return null
            val size = maxPx.coerceIn(1, 96)
            return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
                art.setBounds(0, 0, size, size)
                art.draw(Canvas(it))
                bitmapCache.put(path, it)
            }
        }

        val bytes = try {
            context.assets.open(path).use { it.readBytes() }
        } catch (_: Exception) {
            return null
        }
        if (bytes.isEmpty()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decode = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(maxOf(bounds.outWidth, bounds.outHeight), maxPx)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decode) ?: return null
        bitmapCache.put(path, decoded)
        return decoded
    }

    /** Drawable for an `ImageView`. Null when the item has no bundled art. */
    fun drawable(context: Context, path: String?, maxPx: Int = DEFAULT_MAX_PX): Drawable? {
        val bmp = bitmap(context, path, maxPx) ?: return null
        return BitmapDrawable(context.resources, bmp)
    }

    /** [pathForName] + [drawable] in one call. */
    fun drawableForName(context: Context, name: String?): Drawable? =
        if (name.isNullOrEmpty()) null else drawable(context, pathForName(context, name))

    /** [pathForId] + [drawable] in one call. */
    fun drawableForId(context: Context, collection: String?, id: Int): Drawable? =
        if (collection.isNullOrEmpty()) null
        else drawable(context, pathForId(collection, id))

    fun clearCache() = bitmapCache.evictAll()

    // --------------------------------------------------------------- internal

    private fun assetExists(context: Context, path: String): Boolean = try {
        context.assets.open(path).use { true }
    } catch (_: Exception) {
        false
    }

    /**
     * Powers-of-two sample size that brings [srcPx] down to at most [maxPx].
     * Kept as a plain function so the same rule applies everywhere.
     */
    private fun sampleSize(srcPx: Int, maxPx: Int): Int {
        if (maxPx <= 0 || srcPx <= maxPx) return 1
        var sample = 1
        while (srcPx / sample > maxPx) sample *= 2
        return sample
    }

    private fun nameIndex(context: Context): Map<String, String> {
        namesByLowercase?.let { return it }
        synchronized(this) {
            namesByLowercase?.let { return it }
            val byName = LinkedHashMap<String, String>(4096)
            val byPath = LinkedHashMap<String, String>(4096)
            try {
                val text = context.assets.open(INDEX_ASSET).use { it.readBytes() }
                    .toString(Charsets.UTF_8)
                val json = JSONObject(text)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    // The index stores paths relative to `assets/sfl/`, while
                    // `pathForId` builds them with the prefix — without
                    // normalising here, name→art and art→name would both miss.
                    val raw = json.getString(name)
                    val path = if (raw.startsWith(ASSET_PREFIX)) raw else ASSET_PREFIX + raw
                    byName[name.trim().lowercase(Locale.ROOT)] = path
                    // One path per name in the index; the first writer wins for
                    // the reverse direction, which is good enough for a label.
                    if (!byPath.containsKey(path)) byPath[path] = name
                }
            } catch (_: Exception) {
                // Missing or corrupt index: every lookup simply misses and the
                // caller falls back to its default icon.
            }
            // The game has two distinct Green Amulets. The name-keyed art
            // index keeps the collectible; the picker needs both trade IDs.
            byPath.putIfAbsent("$ASSET_PREFIX$COLLECTION_WEARABLES/30.png", "Green Amulet")
            pathsByName = byPath
            namesByLowercase = byName
            return byName
        }
    }
}
