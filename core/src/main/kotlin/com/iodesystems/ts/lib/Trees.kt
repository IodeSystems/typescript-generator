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

}