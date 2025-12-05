package com.iodesystems.ts.extractor

import io.github.classgraph.ClassInfo
import io.github.classgraph.ClassRefTypeSignature
import io.github.classgraph.ClassTypeSignature
import io.github.classgraph.HackSessor
import io.github.classgraph.HierarchicalTypeSignature

sealed interface ClassShim {
    fun fqcn(): String
    fun getTypeArguments(): List<HierarchicalTypeSignature>
    fun getClassInfo(): ClassInfo
    fun getSuffixTypeArguments(): List<List<HierarchicalTypeSignature>>


    class ClassTypeShim(sig: ClassTypeSignature) : ClassShim {
        private val classInfo = HackSessor.getClassInfo(sig)!!
        override fun fqcn(): String = classInfo.name
        override fun getTypeArguments(): List<HierarchicalTypeSignature> {
            return classInfo.typeDescriptor.typeParameters.map { it }
        }

        override fun getClassInfo(): ClassInfo {
            return classInfo
        }

        override fun getSuffixTypeArguments(): List<List<HierarchicalTypeSignature>> {
            return emptyList()
        }

    }

    class ClassRefShim(val sig: ClassRefTypeSignature) : ClassShim {
        override fun fqcn(): String = sig.fullyQualifiedClassName

        override fun getTypeArguments(): List<HierarchicalTypeSignature> {
            return sig.typeArguments.map { f -> f.typeSignature }
        }

        override fun getClassInfo(): ClassInfo {
            return sig.classInfo
        }

        override fun getSuffixTypeArguments(): List<List<HierarchicalTypeSignature>> {
            return sig.suffixTypeArguments.map { it.map { it.typeSignature } }
        }
    }
}