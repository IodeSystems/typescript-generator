package com.iodesystems.ts.lib

object Tree {
    fun <T> treeToListDepthFirst(source: T?, extract: (T) -> Collection<T>): List<T> {
        if(source == null) return emptyList()
        val result = mutableListOf<T>()
        fun flatten(t: T) {
            result.add(t)
            extract(t).map { flatten(it) }
        }
        flatten(source)
        return result
    }

    fun <T> treeToListBreadthFirst(source: T?, extract: (T) -> Collection<T>): List<T> {
        if(source == null) return emptyList()
        val result = mutableListOf<T>()
        val queue = ArrayDeque<T>()
        queue.add(source)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            result.add(current)
            queue.addAll(extract(current))
        }
        return result
    }

}