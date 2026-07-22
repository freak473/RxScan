package com.rxscan.backend.extraction.parse;

/**
 * How a course duration was expressed. {@link #DAYS} carries a concrete day count; {@link #ONGOING}
 * is an open-ended "continue"; {@link #UNSPECIFIED} means nothing parseable was read (parser spec
 * §Components 4). Nothing is ever inferred.
 */
public enum DurationType {
    DAYS,
    ONGOING,
    UNSPECIFIED
}
