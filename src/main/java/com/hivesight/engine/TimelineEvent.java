package com.hivesight.engine;

public record TimelineEvent(
        String type,
        long date,
        String dateFormatted,
        String description,
        Ramp ramp,
        Long amount,
        String currencyCode
) {
    public static final String TYPE_RENEWAL = "renewal";
    public static final String TYPE_RAMP_APPLIED = "ramp_applied";
    public static final String TYPE_CANCELLED = "cancelled";
    public static final String TYPE_TRIAL_END = "trial_end";
    public static final String TYPE_PAUSED = "paused";
    public static final String TYPE_RESUMED = "resumed";
    public static final String TYPE_NON_RENEWING = "non_renewing";
    public static final String TYPE_CONTRACT_END = "contract_end";
}
