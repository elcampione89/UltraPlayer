package tech.daemonhub.ultraplayer.panel

import tech.daemonhub.ultraplayer.data.Category

/**
 * Category ordering/hiding from the panel's Categories tab.
 * "UK" matches names containing "uk"; "^UK" matches names starting with "uk".
 * Order: Top rules (in order) → everything else (provider order or A–Z) → Bottom rules (in order).
 */
object CategoryRules {
    private const val MIDDLE = 50_000
    private const val BOTTOM = 100_000
    private val cfg get() = PanelClient.config.value

    private fun matches(name: String, pattern: String): Boolean {
        val p = pattern.trim().lowercase()
        if (p.isEmpty()) return false
        return if (p.startsWith("^")) name.startsWith(p.substring(1)) else name.contains(p)
    }

    private fun indexIn(name: String, patterns: List<String>) = patterns.indexOfFirst { matches(name, it) }

    fun isHidden(rawName: String): Boolean {
        val c = cfg
        val name = rawName.lowercase()
        if (indexIn(name, c.catHide) >= 0) return true
        return c.catHideUnlisted && indexIn(name, c.catTop) < 0 && indexIn(name, c.catBottom) < 0
    }

    fun rank(rawName: String): Int {
        val c = cfg
        val name = rawName.lowercase()
        indexIn(name, c.catTop).let { if (it >= 0) return it }
        indexIn(name, c.catBottom).let { if (it >= 0) return BOTTOM + it }
        return MIDDLE
    }

    /** Applies hide + order rules to a provider's category list (which arrives in provider order). */
    fun arrange(categories: List<Category>): List<Category> {
        val visible = categories.filter { !isHidden(it.name) }
        val base = if (cfg.catSortAz) visible.sortedBy { it.name.lowercase() } else visible
        return base.sortedBy { rank(it.name) } // stable sort keeps the base order inside each group
    }
}
