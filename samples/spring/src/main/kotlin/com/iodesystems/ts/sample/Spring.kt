package com.iodesystems.ts.sample

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
open class Spring {
}

fun main() {
    SpringApplication.run(Spring::class.java, *emptyArray())
}