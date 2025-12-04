package io.github.classgraph

object HackSessor {
    fun getClassInfo(sig: ClassTypeSignature): ClassInfo? {
        return sig.classInfo
    }
}