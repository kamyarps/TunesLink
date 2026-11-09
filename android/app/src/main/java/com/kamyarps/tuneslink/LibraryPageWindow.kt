package com.kamyarps.tuneslink

internal data class PageWindow<T>(val items: List<T>, val startOffset: Int)

/**
 * Merges a page into the retained window by absolute offset. The result is unique by [key]
 * (lazy lists key rows by ID): when a row moved between page loads, the incoming copy keeps its
 * position and the stale copy is dropped. Dropping a stale row before the page moves the window
 * start so the page's rows keep their absolute offsets; the next page load fills any gap.
 */
internal fun <T> mergePageWindow(
    existing: List<T>,
    existingStart: Int,
    incoming: List<T>,
    incomingStart: Int,
    replace: Boolean,
    maximumItems: Int,
    total: Int = Int.MAX_VALUE,
    key: (T) -> Any? = { it },
): PageWindow<T> {
    require(maximumItems > 0)
    require(total >= 0)
    if (total == 0) return PageWindow(emptyList(), 0)
    val window = mergeUnboundedPageWindow(existing, existingStart, incoming.distinctBy(key),
        incomingStart, replace, maximumItems, key)
    val start = window.startOffset.coerceAtMost(total)
    return PageWindow(window.items.take(total - start), start)
}

private fun <T> mergeUnboundedPageWindow(
    existing: List<T>, existingStart: Int, incoming: List<T>, incomingStart: Int,
    replace: Boolean, maximumItems: Int, key: (T) -> Any?,
): PageWindow<T> {
    if (replace || existing.isEmpty()) {
        val kept = incoming.take(maximumItems)
        return PageWindow(kept, incomingStart.coerceAtLeast(0))
    }
    if (incoming.isEmpty()) return PageWindow(existing, existingStart)

    val safeExistingStart = existingStart.coerceAtLeast(0)
    val safeIncomingStart = incomingStart.coerceAtLeast(0)
    if (safeIncomingStart.toLong() > safeExistingStart.toLong() + existing.size ||
        safeExistingStart.toLong() > safeIncomingStart.toLong() + incoming.size
    ) return PageWindow(incoming.take(maximumItems), safeIncomingStart)
    val unionStart = minOf(safeExistingStart, safeIncomingStart)
    val unionEnd = maxOf(safeExistingStart + existing.size, safeIncomingStart + incoming.size)
    val slots = MutableList<T?>(unionEnd - unionStart) { null }
    existing.forEachIndexed { index, item -> slots[safeExistingStart - unionStart + index] = item }
    incoming.forEachIndexed { index, item -> slots[safeIncomingStart - unionStart + index] = item }
    if (slots.any { it == null }) {
        val kept = incoming.take(maximumItems)
        return PageWindow(kept, safeIncomingStart)
    }
    val incomingFrom = safeIncomingStart - unionStart
    val incomingUntil = incomingFrom + incoming.size
    val incomingKeys = incoming.mapTo(HashSet(), key)
    val seen = HashSet<Any?>()
    val merged = ArrayList<T>(slots.size)
    var droppedBefore = 0
    slots.forEachIndexed { index, slot ->
        @Suppress("UNCHECKED_CAST")
        val item = slot as T
        val itemKey = key(item)
        val fromIncoming = index in incomingFrom until incomingUntil
        if (!fromIncoming && (itemKey in incomingKeys || !seen.add(itemKey))) {
            if (index < incomingFrom) droppedBefore++
        } else {
            seen.add(itemKey)
            merged += item
        }
    }
    val start = unionStart + droppedBefore
    if (merged.size <= maximumItems) return PageWindow(merged, start)

    return if (safeIncomingStart >= safeExistingStart) {
        val drop = merged.size - maximumItems
        PageWindow(merged.drop(drop), start + drop)
    } else {
        PageWindow(merged.take(maximumItems), start)
    }
}
