package com.flowmail.campaigns;

import java.time.LocalDate;

public record UsageResponse(LocalDate period, int used, int limit) {
}