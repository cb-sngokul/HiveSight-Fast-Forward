package com.hivesight.engine;

public record TimelineEvent(
        String type,
        long date,
        String dateFormatted,
        String description,
        Ramp ramp
) {
    public static final String TYPE_RENEWAL = "renewal";
    public static final String TYPE_RAMP_APPLIED = "ramp_applied";
    public static final String TYPE_CANCELLED = "cancelled";
}
