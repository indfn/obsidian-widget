package com.obsidianwidget

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Manages reading/writing to the Obsidian vault via SAF (Storage Access Framework).
 */
class VaultManager(private val context: Context, private val widgetId: Int = -1) {

    enum class NoteMode { DAILY, PINNED }

    data class ChecklistItem(
        val lineIndex: Int,
        val text: String,
        val isChecked: Boolean,
        val isPlainText: Boolean = false,
        val isHeading: Boolean = false,
        val isBullet: Boolean = false,
        val indentLevel: Int = 0,
        val hasChildren: Boolean = false,
        var isCollapsed: Boolean = false
    )

    companion object {
        private const val PREFS_NAME = "obsidian_widget_prefs"
        // Global keys (shared across all widgets)
        private const val KEY_VAULT_URI = "vault_uri"
        private const val KEY_VAULT_NAME = "vault_name"
        // Per-widget keys (suffixed with _widgetId)
        private const val KEY_DAILY_FOLDER = "daily_folder"
        private const val KEY_DATE_FORMAT = "date_format"
        private const val KEY_NOTE_MODE = "note_mode"
        private const val KEY_PINNED_NOTE_URI = "pinned_note_uri"
        private const val KEY_PINNED_NOTE_NAME = "pinned_note_name"
        private const val KEY_SHOW_BUTTONS = "show_buttons"
        private const val KEY_PINNED_NOTE_URIS = "pinned_note_uris"
        private const val KEY_PINNED_NOTE_NAMES = "pinned_note_names"
        private const val KEY_CURRENT_NOTE_INDEX = "current_note_index"
        private const val KEY_WIDGET_ALPHA = "widget_alpha"
        private const val KEY_TAP_CHECKBOX_ONLY = "tap_checkbox_only"
        private const val KEY_ADD_TO_TOP = "add_to_top"
        private const val KEY_SHOW_ADD_TO_TOP = "show_add_to_top"
        private const val KEY_WIDGET_THEME = "widget_theme"
        private const val KEY_ACCENT_COLOR = "accent_color"
        private const val KEY_SYNC_COMPLETED_TASKS = "sync_completed_tasks"
        private const val DEFAULT_DATE_FORMAT = "yyyy-MM-dd"

        // Task patterns - expanded to support various checkbox states
        private val CHECKLIST_REGEX = Regex("""^(\s*)-\s*\[([ xX])\]\s*(.*)$""")
        private val TASK_LINE_REGEX = Regex("""^(\s*)-\s*\[([ xX/\-><])\]\s*(.*)$""")
        private val HEADING_REGEX = Regex("""^(#{1,6})\s+(.+)$""")
        private val BULLET_REGEX = Regex("""^(\s*)[*+-]\s+(.+)$""")
        
        // Tasks plugin detection patterns
        private val TASKS_EMOJI_REGEX = Regex("""[📅⏫🔼🔽⏬✅❌🔁📆🔺]""")
        private val DATE_PATTERN_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
        private val COMPLETION_MARKER_REGEX = Regex("""\s*✅\s*\d{4}-\d{2}-\d{2}""")
        
        // Default Completed Tasks plugin settings
        private val DEFAULT_STATUSES = listOf("- [ ]", "- [/]", "- [x]", "- [-]", "- [>]", "- [<]")
        private val DEFAULT_SORTED_STATUSES = listOf("- [ ]", "- [/]", "- [x]")
        private val DEFAULT_PRIORITY_EMOJIS = listOf("⏬", "🔽", "🔼", "⏫", "🔺")

        fun deleteWidgetPrefs(context: Context, widgetId: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .remove("${KEY_DAILY_FOLDER}_$widgetId")
                .remove("${KEY_DATE_FORMAT}_$widgetId")
                .remove("${KEY_NOTE_MODE}_$widgetId")
                .remove("${KEY_PINNED_NOTE_URI}_$widgetId")
                .remove("${KEY_PINNED_NOTE_NAME}_$widgetId")
                .remove("${KEY_SHOW_BUTTONS}_$widgetId")
                .remove("${KEY_PINNED_NOTE_URIS}_$widgetId")
                .remove("${KEY_PINNED_NOTE_NAMES}_$widgetId")
                .remove("${KEY_CURRENT_NOTE_INDEX}_$widgetId")
                .remove("${KEY_WIDGET_ALPHA}_$widgetId")
                .remove("${KEY_TAP_CHECKBOX_ONLY}_$widgetId")
                .remove("${KEY_ADD_TO_TOP}_$widgetId")
                .remove("${KEY_SHOW_ADD_TO_TOP}_$widgetId")
                .remove("${KEY_WIDGET_THEME}_$widgetId")
                .remove("${KEY_ACCENT_COLOR}_$widgetId")
                .remove("${KEY_SYNC_COMPLETED_TASKS}_$widgetId")
                .apply()
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun wk(key: String): String =
        if (widgetId >= 0) "${key}_$widgetId" else key

    // Global settings (same for all widgets)
    var vaultUri: Uri?
        get() = prefs.getString(KEY_VAULT_URI, null)?.let { Uri.parse(it) }
        set(value) = prefs.edit().putString(KEY_VAULT_URI, value?.toString()).apply()

    var vaultName: String?
        get() = prefs.getString(KEY_VAULT_NAME, null)
        set(value) = prefs.edit().putString(KEY_VAULT_NAME, value).apply()

    // Per-widget settings
    var dailyFolder: String
        get() = prefs.getString(wk(KEY_DAILY_FOLDER), prefs.getString(KEY_DAILY_FOLDER, "") ?: "") ?: ""
        set(value) = prefs.edit().putString(wk(KEY_DAILY_FOLDER), value).apply()

    var dateFormat: String
        get() = prefs.getString(wk(KEY_DATE_FORMAT), prefs.getString(KEY_DATE_FORMAT, DEFAULT_DATE_FORMAT) ?: DEFAULT_DATE_FORMAT) ?: DEFAULT_DATE_FORMAT
        set(value) = prefs.edit().putString(wk(KEY_DATE_FORMAT), value).apply()

    var noteMode: NoteMode
        get() = if (prefs.getString(wk(KEY_NOTE_MODE), prefs.getString(KEY_NOTE_MODE, "DAILY")) == "PINNED") NoteMode.PINNED else NoteMode.DAILY
        set(value) = prefs.edit().putString(wk(KEY_NOTE_MODE), value.name).apply()

    var pinnedNoteUri: Uri?
        get() = prefs.getString(wk(KEY_PINNED_NOTE_URI), prefs.getString(KEY_PINNED_NOTE_URI, null))?.let { Uri.parse(it) }
        set(value) = prefs.edit().putString(wk(KEY_PINNED_NOTE_URI), value?.toString()).apply()

    var pinnedNoteName: String?
        get() = prefs.getString(wk(KEY_PINNED_NOTE_NAME), prefs.getString(KEY_PINNED_NOTE_NAME, null))
        set(value) = prefs.edit().putString(wk(KEY_PINNED_NOTE_NAME), value).apply()

    var pinnedNoteUriList: List<String>
        get() {
            val list = prefs.getString(wk(KEY_PINNED_NOTE_URIS), null)
            if (!list.isNullOrEmpty()) return list.split("|||").filter { it.isNotEmpty() }
            val single = prefs.getString(wk(KEY_PINNED_NOTE_URI), prefs.getString(KEY_PINNED_NOTE_URI, null))
            return if (single != null) listOf(single) else emptyList()
        }
        set(value) { prefs.edit().putString(wk(KEY_PINNED_NOTE_URIS), value.joinToString("|||")).commit() }

    var pinnedNoteNameList: List<String>
        get() {
            val list = prefs.getString(wk(KEY_PINNED_NOTE_NAMES), null)
            if (!list.isNullOrEmpty()) return list.split("|||").filter { it.isNotEmpty() }
            val single = prefs.getString(wk(KEY_PINNED_NOTE_NAME), prefs.getString(KEY_PINNED_NOTE_NAME, null))
            return if (single != null) listOf(single) else emptyList()
        }
        set(value) { prefs.edit().putString(wk(KEY_PINNED_NOTE_NAMES), value.joinToString("|||")).commit() }

    var currentNoteIndex: Int
        get() = prefs.getInt(wk(KEY_CURRENT_NOTE_INDEX), 0)
        set(value) { prefs.edit().putInt(wk(KEY_CURRENT_NOTE_INDEX), value).commit() }

    fun addPinnedNote(uri: Uri, name: String) {
        val uris = pinnedNoteUriList.toMutableList()
        val names = pinnedNoteNameList.toMutableList()
        uris.add(uri.toString())
        names.add(name)
        pinnedNoteUriList = uris
        pinnedNoteNameList = names
    }

    fun removePinnedNote(index: Int) {
        val uris = pinnedNoteUriList.toMutableList()
        val names = pinnedNoteNameList.toMutableList()
        if (index in uris.indices) {
            uris.removeAt(index)
            names.removeAt(index)
            pinnedNoteUriList = uris
            pinnedNoteNameList = names
            if (currentNoteIndex >= uris.size) {
                currentNoteIndex = (uris.size - 1).coerceAtLeast(0)
            }
        }
    }

    fun getPinnedNoteCount(): Int = pinnedNoteUriList.size

    fun navigateNote(direction: Int) {
        val count = getPinnedNoteCount()
        if (count <= 1) return
        var newIndex = currentNoteIndex + direction
        if (newIndex < 0) newIndex = count - 1
        if (newIndex >= count) newIndex = 0
        currentNoteIndex = newIndex
    }

    fun getCurrentPinnedNoteUri(): Uri? {
        val uris = pinnedNoteUriList
        if (uris.isEmpty()) return null
        val idx = currentNoteIndex.coerceIn(0, uris.lastIndex)
        return Uri.parse(uris[idx])
    }

    fun getCurrentPinnedNoteName(): String? {
        val names = pinnedNoteNameList
        if (names.isEmpty()) return null
        val idx = currentNoteIndex.coerceIn(0, names.lastIndex)
        return names[idx]
    }

    /**
     * Get the relative path of the current pinned note within the vault.
     * This is needed for Obsidian deep links which expect paths like "folder/note" not just "note".
     */
    fun getCurrentPinnedNoteRelativePath(): String? {
        val noteUri = getCurrentPinnedNoteUri() ?: return null
        val vaultUriStr = vaultUri?.toString() ?: return getCurrentPinnedNoteName()?.removeSuffix(".md")
        
        // SAF URIs look like: content://...document/primary:VaultName/folder/note.md
        // or content://...tree/primary:VaultName/document/primary:VaultName/folder/note.md
        val notePathSegment = noteUri.lastPathSegment ?: return getCurrentPinnedNoteName()?.removeSuffix(".md")
        val vaultPathSegment = Uri.parse(vaultUriStr).lastPathSegment ?: return getCurrentPinnedNoteName()?.removeSuffix(".md")
        
        // Extract the part after the colon (e.g., "VaultName" or "VaultName/folder/note.md")
        val vaultPath = vaultPathSegment.substringAfter(':', "")
        val notePath = notePathSegment.substringAfter(':', "")
        
        // notePath might be "VaultName/folder/note.md", vaultPath is "VaultName"
        // We want "folder/note" (relative path without .md)
        return if (notePath.startsWith(vaultPath)) {
            val relativePath = notePath.removePrefix(vaultPath).trimStart('/').removeSuffix(".md")
            relativePath.ifEmpty { getCurrentPinnedNoteName()?.removeSuffix(".md") }
        } else {
            // Fallback to just the filename
            getCurrentPinnedNoteName()?.removeSuffix(".md")
        }
    }

    var showButtons: Boolean
        get() = prefs.getBoolean(wk(KEY_SHOW_BUTTONS), prefs.getBoolean(KEY_SHOW_BUTTONS, true))
        set(value) = prefs.edit().putBoolean(wk(KEY_SHOW_BUTTONS), value).apply()

    var widgetAlpha: Int
        get() = prefs.getInt(wk(KEY_WIDGET_ALPHA), 100)
        set(value) = prefs.edit().putInt(wk(KEY_WIDGET_ALPHA), value).apply()

    var tapCheckboxOnly: Boolean
        get() = prefs.getBoolean(wk(KEY_TAP_CHECKBOX_ONLY), false)
        set(value) = prefs.edit().putBoolean(wk(KEY_TAP_CHECKBOX_ONLY), value).apply()

    var addToTop: Boolean
        get() = prefs.getBoolean(wk(KEY_ADD_TO_TOP), false)
        set(value) = prefs.edit().putBoolean(wk(KEY_ADD_TO_TOP), value).apply()

    var showAddToTop: Boolean
        get() = prefs.getBoolean(wk(KEY_SHOW_ADD_TO_TOP), true)
        set(value) = prefs.edit().putBoolean(wk(KEY_SHOW_ADD_TO_TOP), value).apply()

    var syncCompletedTasks: Boolean
        get() = prefs.getBoolean(wk(KEY_SYNC_COMPLETED_TASKS), false)
        set(value) = prefs.edit().putBoolean(wk(KEY_SYNC_COMPLETED_TASKS), value).apply()

    var widgetTheme: String
        get() = prefs.getString(wk(KEY_WIDGET_THEME), "dark") ?: "dark"
        set(value) = prefs.edit().putString(wk(KEY_WIDGET_THEME), value).apply()

    var accentColor: String
        get() = prefs.getString(wk(KEY_ACCENT_COLOR), "#D97757") ?: "#D97757"
        set(value) = prefs.edit().putString(wk(KEY_ACCENT_COLOR), value).apply()

    fun getThemeColors(): ThemeColors {
        val isDark = widgetTheme == "dark"
        val accent = try { android.graphics.Color.parseColor(accentColor) } catch (_: Exception) { 0xFFD97757.toInt() }
        return if (isDark) {
            ThemeColors(
                bg = 0xF21A1A1E.toInt(),
                text = 0xFFE8E6E3.toInt(),
                textSecondary = 0xFF8B8B8F.toInt(),
                accent = accent,
                buttonText = 0xFFFFFFFF.toInt()
            )
        } else {
            ThemeColors(
                bg = 0xF2F5F5F5.toInt(),
                text = 0xFF1A1A1E.toInt(),
                textSecondary = 0xFF6B6B6F.toInt(),
                accent = accent,
                buttonText = 0xFFFFFFFF.toInt()
            )
        }
    }

    data class ThemeColors(
        val bg: Int,
        val text: Int,
        val textSecondary: Int,
        val accent: Int,
        val buttonText: Int
    )

    /**
     * Batch save all widget settings using commit() for reliable persistence.
     */
    fun saveWidgetSettings(
        dailyFolder: String,
        dateFormat: String,
        noteMode: NoteMode,
        showButtons: Boolean,
        widgetAlpha: Int,
        tapCheckboxOnly: Boolean,
        addToTop: Boolean,
        showAddToTop: Boolean,
        widgetTheme: String,
        accentColor: String,
        syncCompletedTasks: Boolean
    ) {
        prefs.edit()
            .putString(wk(KEY_DAILY_FOLDER), dailyFolder)
            .putString(wk(KEY_DATE_FORMAT), dateFormat)
            .putString(wk(KEY_NOTE_MODE), noteMode.name)
            .putBoolean(wk(KEY_SHOW_BUTTONS), showButtons)
            .putInt(wk(KEY_WIDGET_ALPHA), widgetAlpha)
            .putBoolean(wk(KEY_TAP_CHECKBOX_ONLY), tapCheckboxOnly)
            .putBoolean(wk(KEY_ADD_TO_TOP), addToTop)
            .putBoolean(wk(KEY_SHOW_ADD_TO_TOP), showAddToTop)
            .putString(wk(KEY_WIDGET_THEME), widgetTheme)
            .putString(wk(KEY_ACCENT_COLOR), accentColor)
            .putBoolean(wk(KEY_SYNC_COMPLETED_TASKS), syncCompletedTasks)
            .commit()
    }

    val isVaultConfigured: Boolean
        get() = vaultUri != null

    /**
     * Read the widget note based on current mode.
     */
    fun readWidgetNote(): String? {
        return when (noteMode) {
            NoteMode.DAILY -> readDailyNote()
            NoteMode.PINNED -> readPinnedNote()
        }
    }

    /**
     * Get the display title for the widget.
     */
    fun getWidgetTitle(): String {
        return when (noteMode) {
            NoteMode.DAILY -> "Daily Note"
            NoteMode.PINNED -> getCurrentPinnedNoteName()?.removeSuffix(".md") ?: "Pinned Note"
        }
    }

    /**
     * Read the pinned note content.
     */
    fun readPinnedNote(): String? {
        val uri = getCurrentPinnedNoteUri() ?: return null
        return readFileContent(uri)
    }

    /**
     * Read today's daily note content, if it exists.
     */
    fun readDailyNote(): String? {
        val uri = vaultUri ?: return null
        val rootDoc = DocumentFile.fromTreeUri(context, uri) ?: return null

        val todayFileName = getTodayFileName()
        val targetDir = if (dailyFolder.isNotBlank()) {
            findSubDirectory(rootDoc, dailyFolder)
        } else {
            rootDoc
        } ?: return null

        val noteFile = targetDir.findFile(todayFileName) ?: return null
        return readFileContent(noteFile.uri)
    }

    /**
     * Append text to today's daily note (creates the file if it doesn't exist).
     */
    fun appendToWidgetNote(text: String): Boolean {
        val formatted = if (parseChecklist().any { !it.isPlainText }) "- [ ] $text" else text
        return when (noteMode) {
            NoteMode.DAILY -> appendToDailyNote(formatted)
            NoteMode.PINNED -> appendToPinnedNote(formatted)
        }
    }

    fun appendToPinnedNote(text: String): Boolean {
        val uri = getCurrentPinnedNoteUri() ?: return false
        val existing = readFileContent(uri) ?: ""
        val newContent = if (existing.isNotBlank()) {
            if (addToTop) "$text\n$existing" else "$existing\n$text"
        } else text
        return writeFileContent(uri, newContent)
    }

    fun appendToDailyNote(text: String): Boolean {
        val uri = vaultUri ?: return false
        val rootDoc = DocumentFile.fromTreeUri(context, uri) ?: return false

        val todayFileName = getTodayFileName()
        val targetDir = if (dailyFolder.isNotBlank()) {
            findOrCreateSubDirectory(rootDoc, dailyFolder)
        } else {
            rootDoc
        } ?: return false

        val noteFile = targetDir.findFile(todayFileName)

        return if (noteFile != null) {
            // Append or prepend to existing file
            val existing = readFileContent(noteFile.uri) ?: ""
            val newContent = if (existing.isNotBlank()) {
                if (addToTop) "$text\n\n$existing" else "$existing\n\n$text"
            } else text
            writeFileContent(noteFile.uri, newContent)
        } else {
            // Create new file — just the captured text, no header
            val newFile = targetDir.createFile("text/markdown", todayFileName) ?: return false
            writeFileContent(newFile.uri, text)
        }
    }

    private fun getTodayFileName(): String {
        val formatter = DateTimeFormatter.ofPattern(dateFormat)
        return "${LocalDate.now().format(formatter)}.md"
    }

    private fun findSubDirectory(root: DocumentFile, path: String): DocumentFile? {
        var current = root
        for (segment in path.split("/")) {
            if (segment.isBlank()) continue
            current = current.findFile(segment) ?: return null
            if (!current.isDirectory) return null
        }
        return current
    }

    private fun findOrCreateSubDirectory(root: DocumentFile, path: String): DocumentFile? {
        var current = root
        for (segment in path.split("/")) {
            if (segment.isBlank()) continue
            val existing = current.findFile(segment)
            current = if (existing != null && existing.isDirectory) {
                existing
            } else {
                current.createDirectory(segment) ?: return null
            }
        }
        return current
    }

    private fun readFileContent(uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).readText()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeFileContent(uri: Uri, content: String): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(content)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Parse checklist items from the current widget note with hierarchy support.
     */
    fun parseChecklist(): List<ChecklistItem> {
        val content = readWidgetNote() ?: return emptyList()
        val lines = content.lines()
        val items = mutableListOf<ChecklistItem>()
        
        // Get collapsed state
        val collapsedKey = "collapsed_items_$widgetId"
        val collapsedSet = prefs.getStringSet(collapsedKey, emptySet()) ?: emptySet()
        
        lines.forEachIndexed { index, line ->
            val match = CHECKLIST_REGEX.matchEntire(line)
            val headingMatch = HEADING_REGEX.matchEntire(line)
            
            if (match != null) {
                val indent = match.groupValues[1]
                // Count indent level: tabs or spaces (tab = 1 level, 2 spaces = 1 level)
                val indentLevel = indent.count { it == '\t' } + (indent.count { it == ' ' } / 2)
                val checked = match.groupValues[2].lowercase() == "x"
                val text = match.groupValues[3].trim()
                
                items.add(ChecklistItem(
                    lineIndex = index,
                    text = text,
                    isChecked = checked,
                    indentLevel = indentLevel,
                    isCollapsed = collapsedSet.contains(index.toString())
                ))
            } else if (headingMatch != null) {
                val text = headingMatch.groupValues[2].trim()
                items.add(ChecklistItem(
                    lineIndex = index,
                    text = text,
                    isChecked = false,
                    isPlainText = true,
                    isHeading = true
                ))
            } else {
                val bulletMatch = BULLET_REGEX.matchEntire(line)
                if (bulletMatch != null) {
                    val text = bulletMatch.groupValues[2].trim()
                    items.add(ChecklistItem(
                        lineIndex = index,
                        text = text,
                        isChecked = false,
                        isPlainText = true,
                        isBullet = true
                    ))
                } else if (line.isNotBlank()) {
                    items.add(ChecklistItem(
                        lineIndex = index,
                        text = line.trim(),
                        isChecked = false,
                        isPlainText = true
                    ))
                }
            }
        }
        
        // Determine hasChildren and set default collapsed state
        for (i in items.indices) {
            if (!items[i].isPlainText) {
                // Check if next item is indented more (is a child)
                if (i + 1 < items.size && 
                    !items[i + 1].isPlainText && 
                    items[i + 1].indentLevel > items[i].indentLevel) {
                    // Has children - set collapsed state
                    val lineStr = items[i].lineIndex.toString()
                    val isCollapsed = if (collapsedSet.contains(lineStr)) {
                        // Explicitly collapsed
                        true
                    } else if (collapsedSet.contains("expanded_$lineStr")) {
                        // Explicitly expanded
                        false
                    } else {
                        // Default: collapsed
                        true
                    }
                    items[i] = items[i].copy(hasChildren = true, isCollapsed = isCollapsed)
                }
            }
        }
        
        // Filter out children of collapsed parents
        return filterCollapsedChildren(items)
    }
    
    private fun filterCollapsedChildren(items: List<ChecklistItem>): List<ChecklistItem> {
        val result = mutableListOf<ChecklistItem>()
        var i = 0
        
        while (i < items.size) {
            result.add(items[i])
            
            // If this item is collapsed and has children, skip all children
            if (items[i].isCollapsed && items[i].hasChildren) {
                val parentLevel = items[i].indentLevel
                i++
                // Skip all items with higher indent level
                while (i < items.size && !items[i].isPlainText && items[i].indentLevel > parentLevel) {
                    i++
                }
            } else {
                i++
            }
        }
        
        return result
    }
    
    /**
     * Toggle collapse state for a task item.
     * Default state is collapsed, so we track expanded items with "expanded_" prefix.
     */
    fun toggleCollapse(lineIndex: Int) {
        val collapsedKey = "collapsed_items_$widgetId"
        val collapsedSet = prefs.getStringSet(collapsedKey, emptySet())?.toMutableSet() ?: mutableSetOf()
        
        val lineStr = lineIndex.toString()
        val expandedKey = "expanded_$lineStr"
        
        // If explicitly marked (either collapsed or expanded), toggle it
        when {
            collapsedSet.contains(lineStr) -> {
                // Was explicitly collapsed, now expand
                collapsedSet.remove(lineStr)
                collapsedSet.add(expandedKey)
            }
            collapsedSet.contains(expandedKey) -> {
                // Was explicitly expanded, back to default (collapsed)
                collapsedSet.remove(expandedKey)
            }
            else -> {
                // Default is collapsed, so clicking means expand
                collapsedSet.add(expandedKey)
            }
        }
        
        prefs.edit().putStringSet(collapsedKey, collapsedSet).apply()
    }

    /**
     * Toggle a checklist item by its line index in the current widget note.
     * Implements Tasks plugin compatibility:
     * - Adds completion marker (✅ YYYY-MM-DD) when completing tasks with Tasks plugin metadata
     * - Removes completion marker when unchecking
     * - Triggers file-level sorting for top-level tasks when syncCompletedTasks is enabled
     */
    fun toggleChecklistItem(lineIndex: Int): Boolean {
        val noteUri = getWidgetNoteUri() ?: return false
        val content = readFileContent(noteUri) ?: return false
        val lines = content.lines().toMutableList()

        if (lineIndex < 0 || lineIndex >= lines.size) return false

        val line = lines[lineIndex]
        val match = CHECKLIST_REGEX.matchEntire(line) ?: return false

        val indent = match.groupValues[1]
        val currentState = match.groupValues[2]
        val text = match.groupValues[3]
        
        // Detect if this is a top-level task (no indentation)
        val isTopLevel = indent.isEmpty()
        
        // Detect Tasks plugin metadata (emojis or date patterns)
        val hasTasksMetadata = TASKS_EMOJI_REGEX.containsMatchIn(line) || DATE_PATTERN_REGEX.containsMatchIn(line)

        if (currentState == " ") {
            // UNCHECKED → CHECKED
            if (hasTasksMetadata) {
                // Add completion marker: ✅ YYYY-MM-DD
                val today = java.time.LocalDate.now().toString()
                lines[lineIndex] = "$indent- [x] $text ✅ $today"
            } else {
                lines[lineIndex] = "$indent- [x] $text"
            }
        } else {
            // CHECKED → UNCHECKED
            var newText = text
            // Remove completion marker if present
            newText = COMPLETION_MARKER_REGEX.replace(newText, "")
            lines[lineIndex] = "$indent- [ ] $newText"
        }

        // Write the toggled content
        val success = writeFileContent(noteUri, lines.joinToString("\n"))
        
        // Trigger sorting for top-level tasks when sync is enabled
        if (success && isTopLevel && syncCompletedTasks) {
            sortCompletedTasks(noteUri)
        }
        
        return success
    }
    
    /**
     * Data class representing a task group (parent task + subtasks)
     */
    private data class TaskGroup(
        val lines: MutableList<String>,
        val statusVal: Int,
        val priorityVal: Int
    )
    
    /**
     * Sort tasks in the file according to Completed Tasks plugin logic.
     * Groups tasks with their subtasks and sorts by status first, then priority.
     */
    private fun sortCompletedTasks(noteUri: Uri) {
        val content = readFileContent(noteUri) ?: return
        val lines = content.lines()
        
        // Load settings (could be extended to read from .obsidian/plugins/completed-tasks/data.json)
        val sortedStatuses = DEFAULT_SORTED_STATUSES
        val priorityEmojis = DEFAULT_PRIORITY_EMOJIS
        
        // Helper functions
        fun isTaskLine(line: String): Boolean {
            val trimmed = line.trimStart()
            return DEFAULT_STATUSES.any { trimmed.startsWith(it) }
        }
        
        fun isTopLevelTask(line: String): Boolean {
            return TASK_LINE_REGEX.matchEntire(line)?.let { 
                it.groupValues[1].isEmpty() // No indent
            } ?: false
        }
        
        fun isSubtask(line: String): Boolean {
            return isTaskLine(line) && !isTopLevelTask(line)
        }
        
        fun getStatusSortVal(line: String): Int {
            val trimmed = line.trimStart()
            for ((index, status) in sortedStatuses.withIndex()) {
                if (trimmed.startsWith(status)) return index + 1
            }
            return 0
        }
        
        fun getPrioritySortVal(line: String): Int {
            val trimmed = line.trimStart()
            // Higher index = higher priority = should sort FIRST (use negative)
            for ((index, emoji) in priorityEmojis.withIndex()) {
                if (trimmed.contains(emoji)) return -index
            }
            return 1 // No priority emoji = sorts last
        }
        
        // Step 1: Parse into task groups and non-task lines
        data class ParsedItem(val isTask: Boolean, val taskGroup: TaskGroup?, val line: String?)
        
        val parsed = mutableListOf<ParsedItem>()
        var i = 0
        
        while (i < lines.size) {
            if (isTopLevelTask(lines[i])) {
                val taskGroup = TaskGroup(
                    lines = mutableListOf(lines[i]),
                    statusVal = getStatusSortVal(lines[i]),
                    priorityVal = getPrioritySortVal(lines[i])
                )
                i++
                
                // Collect all following subtasks
                while (i < lines.size && isSubtask(lines[i])) {
                    taskGroup.lines.add(lines[i])
                    i++
                }
                
                parsed.add(ParsedItem(true, taskGroup, null))
            } else {
                parsed.add(ParsedItem(false, null, lines[i]))
                i++
            }
        }
        
        // Step 2: Sort consecutive task blocks
        val finalLines = mutableListOf<String>()
        val taskBuffer = mutableListOf<TaskGroup>()
        
        for (item in parsed) {
            if (item.isTask && item.taskGroup != null) {
                taskBuffer.add(item.taskGroup)
            } else {
                // Non-task line: sort and flush the buffer
                if (taskBuffer.isNotEmpty()) {
                    taskBuffer.sortWith(compareBy({ it.statusVal }, { it.priorityVal }))
                    taskBuffer.forEach { group -> finalLines.addAll(group.lines) }
                    taskBuffer.clear()
                }
                item.line?.let { finalLines.add(it) }
            }
        }
        
        // Flush remaining tasks at end
        if (taskBuffer.isNotEmpty()) {
            taskBuffer.sortWith(compareBy({ it.statusVal }, { it.priorityVal }))
            taskBuffer.forEach { group -> finalLines.addAll(group.lines) }
        }
        
        // Step 3: Write only if changed
        val newContent = finalLines.joinToString("\n")
        if (newContent != content) {
            writeFileContent(noteUri, newContent)
        }
    }

    /**
     * Get the URI of the current widget note file.
     */
    fun getWidgetNoteUri(): Uri? {
        return when (noteMode) {
            NoteMode.PINNED -> getCurrentPinnedNoteUri()
            NoteMode.DAILY -> getDailyNoteUri()
        }
    }

    private fun getDailyNoteUri(): Uri? {
        val uri = vaultUri ?: return null
        val rootDoc = DocumentFile.fromTreeUri(context, uri) ?: return null
        val todayFileName = getTodayFileName()
        val targetDir = if (dailyFolder.isNotBlank()) {
            findSubDirectory(rootDoc, dailyFolder)
        } else {
            rootDoc
        } ?: return null
        return targetDir.findFile(todayFileName)?.uri
    }
}
