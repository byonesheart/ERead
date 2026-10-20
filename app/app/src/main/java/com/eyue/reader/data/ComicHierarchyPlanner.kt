package com.eyue.reader.data

/** Pages in a directory are already naturally sorted; each child is a directory or a PDF. */
internal data class ComicHierarchyNode(val title: String, val pages: List<PageSource> = emptyList(),
                                      val children: List<ComicHierarchyNode> = emptyList())

internal object ComicHierarchyPlanner {
    fun chapters(root: ComicHierarchyNode): List<Chapter> {
        val compacted = compact(root)
        val chapters = mutableListOf<Chapter>()
        if (compacted.pages.isNotEmpty()) chapters += Chapter(title = root.title, pages = compacted.pages)
        compacted.children.forEach { node ->
            val pages = mutableListOf<PageSource>()
            fun append(current: ComicHierarchyNode): List<ComicSection> {
                pages += current.pages
                return current.children.map { child ->
                    val start = pages.size
                    val descendants = append(child)
                    ComicSection(child.title, start, pages.size, descendants)
                }
            }
            val sections = append(node)
            chapters += Chapter(title = node.title, pages = pages, subsections = sections)
        }
        return chapters
    }

    private fun compact(node: ComicHierarchyNode): ComicHierarchyNode {
        val children = node.children.map(::compact).filter { it.pages.isNotEmpty() || it.children.isNotEmpty() }
            .sortedWith { a, b -> NaturalOrder.compare(a.title, b.title) }
        if (children.size == 1) {
            val only = children.single()
            return ComicHierarchyNode(node.title, node.pages + only.pages, only.children)
        }
        return node.copy(children = children)
    }
}
