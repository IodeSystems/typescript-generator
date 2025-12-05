@file:Suppress(
    "UNCHECKED_CAST"
)

package com.iodesystems.ts.extractor

import io.github.classgraph.ClassInfo
import io.github.classgraph.MethodInfo
import kotlinx.metadata.KmClass
import kotlinx.metadata.KmConstructor
import kotlinx.metadata.KmFunction
import kotlinx.metadata.jvm.JvmMethodSignature
import kotlinx.metadata.jvm.KotlinClassMetadata
import kotlinx.metadata.jvm.signature
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode

object KotlinMetadata {

    private val metadataCache = mutableMapOf<String, Metadata?>()
    private val classCache = mutableMapOf<String, KmClass?>()
    private val methodCache = mutableMapOf<String, KmFunction?>()
    private val ctorCache = mutableMapOf<String, KmConstructor?>()

    fun MethodInfo.kotlinConstructor(): KmConstructor? {
        val key = "$className#$name:$typeDescriptorStr"
        return ctorCache.getOrPut(key) {
            val kc = classInfo.kotlinClass() ?: return@getOrPut null
            kc.constructors.firstOrNull { c ->
                when (val s = c.signature) {
                    is JvmMethodSignature -> {
                        s.descriptor == typeDescriptorStr
                    }

                    else -> false
                }
            }
        }

    }

    fun MethodInfo.kotlinMethod(): KmFunction? {
        val key = "$className#$name:$typeDescriptorStr"
        return methodCache.getOrPut(key) {
            val kc = classInfo.kotlinClass() ?: return@getOrPut null
            kc.functions.firstOrNull { f ->
                if (f.name != name) false
                else when (val s = f.signature) {
                    is JvmMethodSignature -> {
                        s.descriptor == typeDescriptorStr
                    }

                    else -> false
                }
            }
        }

    }

    fun ClassInfo.kotlinClass(): KmClass? = classCache.getOrPut(name) {
        val metadata = kotlinMetadata()
        if (metadata != null) {
            (KotlinClassMetadata.readLenient(metadata) as? KotlinClassMetadata.Class)?.kmClass
        } else {
            null
        }
    }

    fun ClassInfo.kotlinMetadata(): Metadata? = metadataCache.getOrPut(name) {
        val classNode = ClassNode()
        val res = this.resource ?: return@getOrPut null
        val classReader = res.open().use {
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
            return@getOrPut null
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

        kotlinx.metadata.jvm.Metadata(kind, metadataVersion, data1, data2, extraString, packageName, extraInt)
    }
}