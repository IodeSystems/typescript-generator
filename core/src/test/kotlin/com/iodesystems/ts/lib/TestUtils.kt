package com.iodesystems.ts.lib

import com.iodesystems.ts.Config
import com.iodesystems.ts.Emitter
import com.iodesystems.ts.Scanner
import com.iodesystems.ts.extractor.JvmExtractor
import kotlin.reflect.KClass

object TestUtils {

    inline fun <reified T> extract(
        crossinline fn: (Config.Builder.() -> Unit) = { }
    ): JvmExtractor.Extraction {
        val config = Config().build { includeApi(T::class).fn() }
        val scan = Scanner(config).scan()
        val apiRegistry = config.apiExtractor().extract(scan)
        return config.jvmExtractor(scan).buildFromRegistry(apiRegistry)
    }

    fun emitter(
        vararg classes: KClass<*>,
        fn: (Config.Builder.() -> Unit) = { }
    ): Emitter {
        val config = Config.Builder(Config()).apply {
            fn()
            includeApi(classes = classes)
        }.config
        val scan = Scanner(config).scan()
        val apiRegistry = config.apiExtractor().extract(scan)
        val extraction = config.jvmExtractor(scan).buildFromRegistry(apiRegistry)
        return Emitter(config, extraction)
    }

    fun Emitter.Output.content(
        includeLib: Boolean = false
    ): String {
        val sb = StringBuilder()
        files.forEach { file ->
            sb.append("//<${file.file.name}>\n")
            sb.append(file.content.lines().joinToString("\n") { line ->
                if (line.startsWith("import")) "//$line" else line
            })
            sb.append("\n//</${file.file.name}>\n")
        }
        return sb.toString().let { s ->
            if (includeLib) s
            else s.replace(Emitter.lib(), "")
        }
    }
}