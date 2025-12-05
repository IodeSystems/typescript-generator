package com.iodesystems.ts

class TypeScriptGenerator(
    val config: Config = Config(),
) {
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
        return emitter().ts()
    }
}