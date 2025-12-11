package com.iodesystems.ts

import com.iodesystems.ts.lib.Log.logger

class TypeScriptGenerator(
    val config: Config = Config(),
) {
    val log = logger()

    companion object {
        fun build(builder: (Config.Builder) -> Config.Builder): TypeScriptGenerator {
            return TypeScriptGenerator(builder(Config.Builder()).config)
        }
    }

    fun emitter(): Emitter {
        val scanner = Scanner(config)
        val scanResult = scanner.scan()
        val apiRegistry = config.apiExtractor().extract(scanResult)
        val extraction = config.jvmExtractor().buildFromRegistry(scanResult, apiRegistry)
        return Emitter(config, extraction)
    }

    fun generate(): Emitter.Output {
        log.debug("Generating type script with config: \n ${config.toString().replaceIndent("    ")}")
        return emitter().ts()
    }
}