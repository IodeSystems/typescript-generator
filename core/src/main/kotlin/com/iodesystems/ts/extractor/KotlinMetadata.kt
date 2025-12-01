@file:Suppress(
    "UNCHECKED_CAST"
)

package com.iodesystems.ts.extractor

import io.github.classgraph.ClassInfo
import kotlinx.metadata.KmClass
import kotlinx.metadata.jvm.KotlinClassMetadata
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode

object KotlinMetadata {

    fun Metadata.kotlinClass(): KmClass? {
        return (KotlinClassMetadata.readLenient(this) as? KotlinClassMetadata.Class)?.kmClass
    }

    fun ClassInfo.kotlinMetadata(): Metadata? {
        val classNode = ClassNode()
        val classReader = this.resource.open().use {
            ClassReader(it)
        }

        // Read the class bytes and populate the ClassNode
        classReader.accept(classNode, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)

        // The Kotlin Metadata annotation has a specific internal JVM name
        val metadataDescriptor = "Lkotlin/Metadata;"
        val metadataAnnotation = classNode.visibleAnnotations?.find {
            it.desc == metadataDescriptor
        } ?: classNode.invisibleAnnotations?.find {
            it.desc == metadataDescriptor
        }

        if (metadataAnnotation == null) {
            return null
        }

        // Extract the raw fields from the annotation (JVM names are short to save space)
        val annotationValues = metadataAnnotation.values
        var kind: Int? = null
        var metadataVersion: IntArray? = null
        var data1: Array<String>? = null
        var data2: Array<String>? = null
        var extraString: String? = null
        var packageName: String? = null
        var extraInt: Int? = null

        if (annotationValues != null) {
            for (i in 0 until annotationValues.size step 2) {
                when (annotationValues[i]) {
                    "k" -> kind = annotationValues[i + 1] as Int
                    "mv" -> metadataVersion = (annotationValues[i + 1] as List<Int>).toIntArray()
                    "d1" ->
                        @Suppress("UNCHECKED_CAST")
                        data1 = (annotationValues[i + 1] as List<String>).toTypedArray()
                    "d2" ->
                        @Suppress("UNCHECKED_CAST")
                        data2 = (annotationValues[i + 1] as List<String>).toTypedArray()
                    "xs" -> extraString = annotationValues[i + 1] as String
                    "pn" -> packageName = annotationValues[i + 1] as String
                    "xi" -> extraInt = annotationValues[i + 1] as Int
                }
            }
        }

        return kotlinx.metadata.jvm.Metadata(kind, metadataVersion, data1, data2, extraString, packageName, extraInt)
    }
}