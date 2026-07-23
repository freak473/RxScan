package com.rxscan.backend.consent;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/** granted_at is the DEVICE-side grant time; the server receipt is the row's created_at. */
public record ConsentDto(String purpose, boolean granted,
                         @JsonProperty("granted_at") OffsetDateTime grantedAt) {}
