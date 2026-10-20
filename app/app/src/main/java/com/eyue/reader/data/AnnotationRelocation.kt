package com.eyue.reader.data

/** Both endpoints follow source text when a directory rebuild changes HTML whitespace. */
internal fun relocateTextAnnotation(annotation: Annotation, oldText: List<String>, newText: List<String>,
                                    position: Pair<Int, Int>): Annotation {
    if (annotation.endOffset <= annotation.characterOffset) return annotation.copy(chapterIndex = position.first,
        characterOffset = position.second, pageIndex = 0, endOffset = 0)
    val end = TextParser.relocatePosition(oldText, newText, annotation.chapterIndex, annotation.endOffset)
    val limit = if (end.first == position.first) end.second else newText[position.first].length
    val finish = limit.coerceIn(position.second, newText[position.first].length)
    return annotation.copy(chapterIndex = position.first, characterOffset = position.second, pageIndex = 0,
        endOffset = finish, quote = newText[position.first].substring(position.second, finish).trim())
}
