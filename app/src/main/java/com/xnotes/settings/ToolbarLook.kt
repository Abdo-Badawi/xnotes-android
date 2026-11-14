package com.xnotes.settings

/** How the editor toolbar is drawn; one setting for both the paged and the canvas bar. */
data class ToolbarLook(
    val position: ToolbarPosition = ToolbarPosition.TOP,
    val size: ToolbarSize = ToolbarSize.REGULAR,
    /** A rounded pill over the canvas instead of a strip along the pane's edge. */
    val floating: Boolean = false,
    /** Which of the floating bar's three shapes is showing; ignored while [floating] is false. */
    val state: ToolbarState = ToolbarState.COLLAPSED,
    /** Floating bar anchor across its free space, 0 (left/top edge) .. 1 (right/bottom edge). */
    val anchorX: Float = 0.5f,
    val anchorY: Float = 0.9f,
)

/** The three shapes of the floating toolbar: one icon, the compact tool row, or the full bar. */
enum class ToolbarState(val id: String) {
    COLLAPSED("collapsed"),
    COMPACT("compact"),
    EXPANDED("expanded");

    companion object {
        fun fromId(id: String?): ToolbarState = entries.find { it.id == id } ?: COLLAPSED
    }
}

/** Which edge of its pane the toolbar runs along. */
enum class ToolbarPosition(val id: String) {
    TOP("top"),
    BOTTOM("bottom"),
    LEFT("left"),
    RIGHT("right");

    val vertical: Boolean get() = this == LEFT || this == RIGHT

    companion object {
        fun fromId(id: String): ToolbarPosition = entries.find { it.id == id } ?: TOP
    }
}

/** How big the toolbar's buttons are. */
enum class ToolbarSize(val id: String) {
    COMPACT("compact"),
    REGULAR("regular"),
    COMFORTABLE("comfortable");

    companion object {
        fun fromId(id: String): ToolbarSize = entries.find { it.id == id } ?: REGULAR
    }
}
