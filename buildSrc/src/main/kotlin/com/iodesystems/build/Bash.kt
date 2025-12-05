package com.iodesystems.build

object Bash {
  data class Result(
    val exitCode: Int,
    val output: String
  )

  fun String.bash(): Result {
    val process = ProcessBuilder(
      "/usr/bin/env", "bash", "-c", this
    )
      .also { it.environment()["PATH"] = "/usr/local/bin:/usr/bin:/bin" }
      .start()
    val output = mutableListOf<String>()
    val er = Thread {
      process.errorStream.reader().useLines { lines ->
        lines.forEach {
          println(it)
          output += it
        }
      }
    }
    val out = Thread {
      process.inputStream.reader().useLines { lines ->
        lines.forEach {
          output += it
        }
      }
    }
    er.start()
    out.start()

    val exitCode = process.waitFor().also { code ->
      er.join()
      out.join()
      if (code != 0) {
        throw Exception(
          """
        Failed ($code) to execute command: $this
        Output:
        ${output.joinToString("\n")}
      """.trimIndent()
        )
      }
    }
    return Result(
      exitCode = exitCode,
      output = output.joinToString("\n")
    )
  }

}
