package com.iodesystems.ts.extractor

import io.github.classgraph.*

sealed interface TypeShim {
    fun getClassInfo(): ClassInfo? = null
    fun fqcn(): String
    fun packageName(): String = ""
    fun getTypeArguments(): List<HierarchicalTypeSignature> = emptyList()
    fun getSuffixTypeArguments(): List<List<HierarchicalTypeSignature>> = emptyList()
    fun intersectionSignatures(): List<HierarchicalTypeSignature> = emptyList()
    fun isEnum(): Boolean = false
    fun simpleName(): String
    fun enumConstantNames(): List<String> = emptyList()
    fun typeParameters(): List<TypeParameter> = emptyList()
    fun getClassAnnotations() = emptyList<AnnotationInfo>()
    fun getClassAnnotation(name: String): AnnotationInfo? = getClassAnnotations().firstOrNull { it.name == name }

    companion object Companion {
        enum class CollectionKind { NONE, LIST, SET, MAP }

        val COLLECTION_FQCNS: Set<String> = setOf(
            "java.util.List",
            "kotlin.collections.List",
            "java.util.Set",
            "kotlin.collections.Set",
            "java.util.Map",
            "kotlin.collections.Map",
        )

        fun isCollectionFqcn(fqcn: String): Boolean = fqcn in COLLECTION_FQCNS

        fun collectionKindForFqcn(fqcn: String): CollectionKind = when (fqcn) {
            "java.util.List", "kotlin.collections.List" -> CollectionKind.LIST
            "java.util.Set", "kotlin.collections.Set" -> CollectionKind.SET
            "java.util.Map", "kotlin.collections.Map" -> CollectionKind.MAP
            else -> CollectionKind.NONE
        }

        // Directly mapped JVM types to TS primitives (kept in sync with RegistrationContext.quickType)
        private val DIRECT_STRING = setOf(
            java.util.UUID::class.java.name,
            // Avoid referencing kotlin.uuid.Uuid class directly to skip ExperimentalUuidApi opt-in requirement
            "kotlin.uuid.Uuid",
            java.lang.String::class.java.name,
            String::class.qualifiedName,
            Character::class.java.name,
            Char::class.qualifiedName,
            // Additional string-like
            "kotlin.CharSequence",
        ).filterNotNull().toSet()

        private val DIRECT_NUMBER = setOf(
            java.math.BigDecimal::class.java.name,
            java.math.BigInteger::class.java.name,
            java.lang.Short::class.java.name,
            Integer::class.java.name,
            java.lang.Long::class.java.name,
            java.lang.Double::class.java.name,
            java.lang.Float::class.java.name,
            java.lang.Byte::class.java.name,
            java.lang.Number::class.java.name,
            Short::class.qualifiedName,
            Int::class.qualifiedName,
            Long::class.qualifiedName,
            Double::class.qualifiedName,
            Float::class.qualifiedName,
            Byte::class.qualifiedName,
            Number::class.qualifiedName,
        ).filterNotNull().toSet()

        private val DIRECT_BOOLEAN = setOf(
            java.lang.Boolean::class.java.name,
            Boolean::class.qualifiedName,
        ).filterNotNull().toSet()

        private val DIRECT_SPECIAL = mapOf(
            "kotlin.Unit" to "void",
            "java.lang.Void" to "void",
            "java.lang.Object" to "any",
        )

        fun directTsForFqcnOrNull(fqcn: String): String? = when (fqcn) {
            in DIRECT_STRING -> "string"
            in DIRECT_NUMBER -> "number"
            in DIRECT_BOOLEAN -> "boolean"
            else -> DIRECT_SPECIAL[fqcn]
        }

        fun forClassInfo(ci: ClassInfo): TypeShim {
            return forSignature(ci.typeSignatureOrTypeDescriptor)
        }

        fun forSignature(s: HierarchicalTypeSignature): TypeShim {
            return when (s) {
                is ClassTypeSignature -> ClassTypeShim(s)
                is ClassRefTypeSignature -> ClassRefShim(s)
                is ArrayTypeSignature -> ArrayShim(s)
                is BaseTypeSignature -> PrimitiveShim(s)
                is TypeVariableSignature -> TypeVarShim(s.name)
                is TypeParameter -> TypeVarShim(s.name)
                else -> error("Invalid signature type: $s")
            }
        }
    }


    @JvmInline
    value class ClassTypeShim(val sig: ClassTypeSignature) : TypeShim {

        override fun fqcn(): String = getClassInfo().name
        override fun packageName(): String = getClassInfo().packageName
        override fun getTypeArguments(): List<HierarchicalTypeSignature> {
            return getClassInfo().typeDescriptor.typeParameters.map { it }
        }

        override fun getClassInfo(): ClassInfo {
            return HackSessor.getClassInfo(sig)!!
        }

        override fun getClassAnnotations(): List<AnnotationInfo> {
            return getClassInfo().annotationInfo?.toList() ?: emptyList()
        }

        override fun intersectionSignatures(): List<HierarchicalTypeSignature> {
            val ti = getClassInfo().typeSignatureOrTypeDescriptor
            return if (ti == null) emptyList()
            else (ti.superinterfaceSignatures + ti.superclassSignature).filterNotNull()
        }

        override fun isEnum(): Boolean = getClassInfo().isEnum
        override fun simpleName(): String = getClassInfo().simpleName
        override fun enumConstantNames(): List<String> = getClassInfo().enumConstants.map { it.name }
        override fun typeParameters(): List<TypeParameter> =
            getClassInfo().typeSignature?.typeParameters ?: emptyList()

    }

    @JvmInline
    value class ClassRefShim(val sig: ClassRefTypeSignature) : TypeShim {
        override fun fqcn(): String = sig.fullyQualifiedClassName
        override fun packageName(): String = sig.classInfo.packageName

        override fun getTypeArguments(): List<HierarchicalTypeSignature> {
            return sig.typeArguments.mapNotNull { f -> f.typeSignature }
        }

        override fun getClassInfo(): ClassInfo? {
            return sig.classInfo
        }

        override fun getClassAnnotations(): List<AnnotationInfo> {
            return sig.classInfo?.annotationInfo?.toList() ?: emptyList()
        }

        override fun getSuffixTypeArguments(): List<List<HierarchicalTypeSignature>> {
            return sig.suffixTypeArguments.map { layer -> layer.map { it.typeSignature } }
        }

        override fun intersectionSignatures(): List<HierarchicalTypeSignature> {
            val ci = sig.classInfo ?: return emptyList()
            val ti = ci.typeSignatureOrTypeDescriptor
            return if (ti == null) emptyList()
            else (ti.superinterfaceSignatures + ti.superclassSignature).filterNotNull()
        }

        override fun isEnum(): Boolean = sig.classInfo?.isEnum ?: false
        override fun simpleName(): String = sig.classInfo?.simpleName
            ?: fqcn().substringAfterLast('.')

        override fun enumConstantNames(): List<String> =
            sig.classInfo?.enumConstants?.map { it.name } ?: emptyList()

        override fun typeParameters(): List<TypeParameter> =
            sig.classInfo?.typeSignature?.typeParameters ?: emptyList()
    }

    /** Shim for array signatures; uses kotlin.Array as fqcn and exposes the element as a single type argument. */
    @JvmInline
    value class ArrayShim(private val sig: ArrayTypeSignature) : TypeShim {
        override fun fqcn(): String = "kotlin.Array"
        override fun getTypeArguments(): List<HierarchicalTypeSignature> = listOfNotNull(sig.elementTypeSignature)
        override fun simpleName(): String = "Array"
        override fun typeParameters(): List<TypeParameter> {
            throw IllegalStateException("TypeParameter is not available for Arrays")
        }
    }

    /** Shim for primitive/base signatures. fqcn() returns JVM primitive name, no type args. */
    @JvmInline
    value class PrimitiveShim(private val sig: BaseTypeSignature) : TypeShim {
        override fun fqcn(): String = sig.type.name

        override fun simpleName(): String = sig.typeStr
    }

    /** Shim for type variables (T) and parameters. fqcn() is synthetic #T. */
    @JvmInline
    value class TypeVarShim(private val name: String) : TypeShim {
        override fun fqcn(): String = "#${name}"
        override fun simpleName(): String = name
    }
}