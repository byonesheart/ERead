package com.eyue.reader.data

/** The publication outline addresses existing reading fragments; it never renumbers their text or annotations. */
internal fun epubPublicationContents(parsed: ParsedEpub): List<PublicationContentsEntry> {
    val sections = parsed.sections.withIndex().groupBy { it.value.path }
    val targets = parsed.navigation.associate { item ->
        val candidates = sections[item.path].orEmpty()
        item.id to (candidates.firstOrNull { item.fragment.isNotEmpty() && it.value.sourceAnchor == item.fragment }
            ?: candidates.firstOrNull())?.index
    }.toMutableMap()
    val directTargets = targets.toMap()
    // A span-only parent (or a broken parent link with readable children) opens its first child.
    parsed.navigation.asReversed().forEach { item ->
        val target = targets[item.id]
        if (target != null && item.parentId != null && directTargets[item.parentId] == null) targets[item.parentId] = target
    }
    val retained = parsed.navigation.filter { targets[it.id] != null }.map { it.id }.toSet()
    return parsed.navigation.filter { it.id in retained }.map { item ->
        PublicationContentsEntry(item.id, item.title, requireNotNull(targets[item.id]), item.parentId?.takeIf { it in retained })
    }
}
