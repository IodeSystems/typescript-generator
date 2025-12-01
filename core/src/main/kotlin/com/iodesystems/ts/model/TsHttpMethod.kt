package com.iodesystems.ts.model

// Decouple from Spring by introducing our own HTTP method enum
enum class TsHttpMethod { GET, POST, PUT, PATCH, DELETE }