package com.iodesystems.ts

class TypeScriptGenerator(
    val config: Config = Config(),
) {
    companion object {
        fun build(builder: (Config.Builder) -> Config.Builder): TypeScriptGenerator {
            return TypeScriptGenerator(builder(Config.Builder()).config)
        }
    }

    fun generate(): Output {
        val scanner = Scanner(config)
        val scanResult = scanner.scan()
        val apiRegistry = config.apiExtractor.extract(scanResult)
        val extraction = config.jvmExtractor.buildFromRegistry(scanResult, apiRegistry)
        val emitter = Emitter(config)
        return Output(extraction, emitter, config)
    }
}