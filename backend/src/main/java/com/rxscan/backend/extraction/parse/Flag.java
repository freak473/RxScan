package com.rxscan.backend.extraction.parse;

/**
 * A field re-check request. Structurally carries ONLY the field and the reason — there is no value
 * component and there never may be one. This is the CDSCO "flag, don't correct" invariant enforced
 * by the type system, not by convention (CLAUDE.md; tech-design §4). A test asserts the record has
 * exactly these two components.
 */
public record Flag(FieldName field, FlagReason reason) {
}
