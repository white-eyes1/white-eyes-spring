package com.whiteeyes.admin.stat.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ResultReceiveRequest(
        @NotNull Long cameraId,
        @NotNull OffsetDateTime at,
        @NotNull @PositiveOrZero Integer personCount,
        @NotNull @Valid DirectionCount in,
        @NotNull @Valid DirectionCount out,
        @NotNull List<@NotNull @PositiveOrZero Double> dwellSec) {

    public record DirectionCount(
            @JsonProperty("N") @NotNull @PositiveOrZero Integer north,
            @JsonProperty("E") @NotNull @PositiveOrZero Integer east,
            @JsonProperty("S") @NotNull @PositiveOrZero Integer south,
            @JsonProperty("W") @NotNull @PositiveOrZero Integer west) {}
}
