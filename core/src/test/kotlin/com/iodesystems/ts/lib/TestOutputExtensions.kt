package com.iodesystems.ts.lib

import com.iodesystems.ts.Emitter.Output

// Concatenate all emitted TypeScript (lib, types, apis) to a single string
fun Output.ts(): String = files.joinToString("\n") { it.content.toString() }

// Concatenate only API files (exclude lib and shared types) to a single string
fun Output.tsApis(): String = files
    .filter { f ->
        val name = f.file.name
        name != "api-lib.ts" && name != "api-types.ts"
    }
    .sortedBy { it.file.name }
    .joinToString("\n") { it.content.toString() }
