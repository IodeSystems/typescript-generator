package com.iodesystems.ts.extractor

import com.iodesystems.ts.extractor.registry.ApiRegistry
import io.github.classgraph.ScanResult

interface ApiExtractor {

    fun extract(scan: ScanResult): ApiRegistry
}
