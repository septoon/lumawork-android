package ru.lumastack.lumawork.domain.backpack

import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
data class BackpackItem(
    val id: String,
    val name: String,
    val serialNumber: String,
    val responsible: String,
    val receivedAt: String,
    val receivedAtEpochMillis: Long? = null,
    val quantity: Int = 0,
)

@Serializable
data class BackpackSnapshot(
    val simpleOneUserId: String,
    val items: List<BackpackItem> = emptyList(),
    val updatedAtEpochMillis: Long? = null,
)

data class BackpackItemGroup(
    val id: String,
    val name: String,
    val items: List<BackpackItem>,
) {
    val quantity: Int
        get() = items.sumOf { it.quantity.coerceAtLeast(1) }

    val latestReceivedAtEpochMillis: Long?
        get() = items.mapNotNull(BackpackItem::receivedAtEpochMillis).maxOrNull()
}

@Serializable
data class BackpackPhotoCatalog(
    val version: Long? = null,
    val items: List<BackpackPhoto> = emptyList(),
)

@Serializable
data class BackpackPhoto(
    val id: String = "",
    val referenceName: String,
    val aliases: List<String> = emptyList(),
    val fileName: String? = null,
    val url: String? = null,
    val imageUrl: String? = null,
    val revision: Long? = null,
) {
    val resolvedUrl: String?
        get() = url?.takeIf(String::isNotBlank) ?: imageUrl?.takeIf(String::isNotBlank)
}

fun backpackGroups(items: List<BackpackItem>): List<BackpackItemGroup> = items
    .groupBy { item ->
        val name = normalizedBackpackText(item.name)
        if (name == normalizedBackpackText(NOT_SET)) "item:${item.id}" else "model:$name"
    }
    .map { (id, groupedItems) ->
        val sortedItems = groupedItems.sortedWith(BACKPACK_ITEM_COMPARATOR)
        BackpackItemGroup(id, sortedItems.first().name, sortedItems)
    }
    .sortedWith(
        compareByDescending<BackpackItemGroup> { it.latestReceivedAtEpochMillis }
            .thenBy { normalizedBackpackText(it.name) },
    )

fun filterBackpackGroups(groups: List<BackpackItemGroup>, query: String): List<BackpackItemGroup> {
    val normalizedQuery = normalizedBackpackText(query)
    if (normalizedQuery.isEmpty()) return groups
    return groups.filter { group ->
        normalizedBackpackText(group.name).contains(normalizedQuery) || group.items.any { item ->
            normalizedBackpackText(item.serialNumber).contains(normalizedQuery) ||
                normalizedBackpackText(item.receivedAt).contains(normalizedQuery)
        }
    }
}

fun BackpackPhotoCatalog.photoFor(itemName: String): BackpackPhoto? {
    val normalizedItemName = normalizedBackpackName(itemName)
    items.firstOrNull { photo ->
        (listOf(photo.referenceName) + photo.aliases).any {
            normalizedBackpackName(it) == normalizedItemName
        }
    }?.let { return it }

    val itemFingerprint = BackpackNameFingerprint(itemName)
    return items.mapNotNull { photo ->
        itemFingerprint.matchScore(BackpackNameFingerprint(photo.referenceName))?.let { photo to it }
    }.maxByOrNull(Pair<BackpackPhoto, Int>::second)?.first
}

internal fun normalizedBackpackText(raw: String): String = Normalizer
    .normalize(raw.trim(), Normalizer.Form.NFD)
    .replace(COMBINING_MARKS, "")
    .lowercase(Locale.ROOT)
    .replace(WHITESPACE, " ")

private fun normalizedBackpackName(raw: String): String = normalizedBackpackText(raw)
    .split(NON_ALPHANUMERIC)
    .filter(String::isNotBlank)
    .joinToString(" ")

private data class BackpackNameFingerprint(
    val terms: Set<String>,
    val brands: Set<String>,
    val models: Set<String>,
    val variants: Set<String>,
) {
    constructor(raw: String) : this(
        terms = tokens(raw).toSet(),
        brands = tokens(raw).toSet().intersect(BRAND_TERMS),
        models = modelTerms(tokens(raw)),
        variants = tokens(raw).toSet().intersect(VARIANT_TERMS),
    )

    fun matchScore(other: BackpackNameFingerprint): Int? {
        val matchingModels = models.intersect(other.models)
        if (matchingModels.isEmpty()) return null
        return matchingModels.size * 100 + brands.intersect(other.brands).size * 30 +
            variants.intersect(other.variants).size * 25 + terms.intersect(other.terms).size * 2
    }

    companion object {
        private fun tokens(raw: String): List<String> = normalizedBackpackName(raw)
            .split(' ')
            .filter(String::isNotBlank)

        private fun modelTerms(tokens: List<String>): Set<String> {
            val result = tokens.filterTo(mutableSetOf()) { token ->
                token.any(Char::isLetter) && token.any(Char::isDigit)
            }
            tokens.zipWithNext().forEach { (prefix, suffix) ->
                if (prefix.all(Char::isLetter) && prefix.length <= 3 && suffix.all(Char::isDigit)) {
                    result += prefix + suffix
                }
            }
            return result
        }
    }
}

private val BACKPACK_ITEM_COMPARATOR = compareByDescending<BackpackItem> { it.receivedAtEpochMillis }
    .thenBy { normalizedBackpackText(it.serialNumber) }
private val COMBINING_MARKS = Regex("\\p{M}+")
private val WHITESPACE = Regex("\\s+")
private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
private val BRAND_TERMS = setOf("aisino", "feitian", "morefun", "pax", "unitodi", "vanstone")
private val VARIANT_TERMS = setOf("внешняя", "переносной", "стационарный")
const val NOT_SET = "(не задано)"
