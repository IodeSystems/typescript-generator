package com.iodesystems.ts;

import com.fasterxml.jackson.annotation.JsonProperty;

public class JacksonParam {
    @JsonProperty(required = false, defaultValue = "derp")
    public String fOptional;
    @JsonProperty(required = false)
    public String fOptionalNullable;
}
