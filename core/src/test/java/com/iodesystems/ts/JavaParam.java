package com.iodesystems.ts;

import org.jetbrains.annotations.Nullable;

// Simple Java POJO used as a query parameter element to verify we don't rely on Class.forName
public class JavaParam {
    @Nullable
    public String name;
    @TsOptional
    public String nick;
    public int count;
}
