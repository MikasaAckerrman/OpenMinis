package com.openminis.app.config

/**
 * Resolve a dotted config path to the field a collection exposes for it.
 *
 * Pulled out of [ConfigRegistry] as a free function so it can be tested
 * without an Android Context or a live registry: the lookup rule is the part
 * that was wrong, and it is pure.
 *
 * The base path is discovered by trying every dot boundary rather than
 * assuming it is the first segment, because two shapes exist that a fixed
 * `[base, id, leaf]` split cannot express:
 *
 *  - a base that itself contains a dot (`agent.keys` + `planner`), and
 *  - a child whose field path has no leaf segment (`agent` + `autoRoute`,
 *    where the whole setting *is* the child).
 *
 * Both were silently unreachable before: `minis-config get agent.autoRoute`
 * answered `unknown_path` for a setting that was registered and working.
 *
 * Matching on the collection's own emitted paths keeps the lookup honest — an
 * id the collection does not know still resolves to null rather than to a
 * neighbouring field.
 *
 * @param lookup returns the collection registered at a base path, or null.
 */
internal fun resolveCollectionField(
    path: String,
    lookup: (String) -> ConfigCollection?,
): ConfigField? {
    var cut = path.indexOf('.')
    while (cut > 0) {
        val coll = lookup(path.substring(0, cut))
        if (coll != null) {
            // The child id is the segment right after the base; the leaf (when
            // there is one) may contain further dots, e.g.
            // `models.<uuid>.modality.video`.
            val childId = path.substring(cut + 1).substringBefore('.')
            coll.fields(forId = childId).firstOrNull { it.path == path }?.let { return it }
        }
        cut = path.indexOf('.', cut + 1)
    }
    return null
}
