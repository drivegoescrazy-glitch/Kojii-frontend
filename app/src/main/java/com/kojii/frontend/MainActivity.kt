package com.kojii.frontend

import android.app.Activity
import android.app.Presentation
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import android.content.Context
import java.util.Locale
import kotlin.concurrent.thread

data class Game(val title: String, val uri: Uri, val system: String)

class MainActivity : Activity() {
    private lateinit var displayManager: DisplayManager
    private var secondScreen: HeroPresentation? = null
    private var secondDisplayId: Int? = null
    private var games: List<Game> = emptyList()
    private var selected = 0
    private var scanRoot: Uri? = null
    private lateinit var gridView: KojiiView
    private val preferences by lazy { getSharedPreferences("kojii_library", Context.MODE_PRIVATE) }
    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            scanRoot?.let { putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, it) }
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_PICK_FOLDER)
    }

    @Deprecated("Uses the platform document picker for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_FOLDER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                scanRoot = uri
                preferences.edit().putString(KEY_ROM_TREE_URI, uri.toString()).apply()
                scanGames(uri)
            } catch (_: Exception) {
                Toast.makeText(this, "Could not read that folder", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        gridView = KojiiView(this, KojiiView.Mode.GRID) { action ->
            when (action) {
                "folder" -> pickFolder()
                "prev" -> moveSelection(-1)
                "next" -> moveSelection(1)
                "launch" -> launchSelected()
                else -> if (action.startsWith("select:")) { selected = action.substringAfter(":").toIntOrNull() ?: 0; refreshViews() }
            }
        }
        setContentView(gridView)
        displayManager.registerDisplayListener(displayListener, null)
        refreshSecondaryDisplay()

        // Restore the persisted Storage Access Framework grant instead of prompting
        // for the ROM directory every time the app starts.
        val savedTree = preferences.getString(KEY_ROM_TREE_URI, null)?.let(Uri::parse)
        if (savedTree != null && contentResolver.persistedUriPermissions.any {
                it.uri == savedTree && it.isReadPermission
            }) {
            scanRoot = savedTree
            scanGames(savedTree)
        } else {
            scanRoot = null
            refreshViews()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshSecondaryDisplay()
        override fun onDisplayRemoved(displayId: Int) { secondScreen?.dismiss(); secondScreen = null }
        override fun onDisplayChanged(displayId: Int) = refreshSecondaryDisplay()
    }

    private fun refreshSecondaryDisplay() {
        val display = displayManager.displays.firstOrNull {
            it.displayId != Display.DEFAULT_DISPLAY && it.state == Display.STATE_ON
        }
        if (display?.displayId != secondDisplayId) {
            secondScreen?.dismiss()
            secondScreen = null
            secondDisplayId = null
            if (display != null) {
                try {
                    secondScreen = HeroPresentation(this, display).also { it.show() }
                    secondDisplayId = display.displayId
                } catch (_: Exception) {
                    secondScreen = null
                    secondDisplayId = null
                }
            }
        }
        refreshViews()
    }

    private fun scanGames(root: Uri) {
        Toast.makeText(this, "Scanning ROM folder…", Toast.LENGTH_SHORT).show()
        thread(name = "kojii-rom-scan") {
            val found = mutableListOf<Game>()
            val seen = HashSet<String>()
            fun walk(folder: Uri, depth: Int) {
                if (depth > 12 || found.size >= 5000) return
                val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                    folder, android.provider.DocumentsContract.getTreeDocumentId(folder)
                )
                // For nested SAF directories, use the document ID supplied by each child.
                val queryUri = if (folder == root) children else
                    android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(root, android.provider.DocumentsContract.getDocumentId(folder))
                try {
                    contentResolver.query(queryUri, arrayOf(
                        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
                    ), null, null, null)?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getString(0) ?: continue
                            val name = cursor.getString(1) ?: continue
                            val mime = cursor.getString(2) ?: ""
                            val child = android.provider.DocumentsContract.buildDocumentUriUsingTree(root, id)
                            if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                                walk(child, depth + 1)
                            } else {
                                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                                if (ext in ROM_EXTENSIONS && seen.add(id)) {
                                    found += Game(name.substringBeforeLast('.', name), child, inferSystem(ext))
                                }
                            }
                        }
                    }
                } catch (_: Exception) { }
            }
            walk(root, 0)
            found.sortBy { it.title.lowercase(Locale.ROOT) }
            runOnUiThread {
                games = found
                selected = 0
                refreshViews()
                Toast.makeText(this, if (found.isEmpty()) "No supported ROM files found in that folder" else "Found ${found.size} games", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun inferSystem(ext: String) = when (ext) {
        "3ds", "cci", "cia", "3dsx" -> "Nintendo 3DS"
        "nds", "srl" -> "Nintendo DS"
        "gba", "agb" -> "Game Boy Advance"
        "gbc", "cgb" -> "Game Boy Color"
        "gb" -> "Game Boy"
        "n64", "z64", "v64" -> "Nintendo 64"
        "iso", "cso" -> "Disc image"
        "nes", "fds" -> "NES"
        "sfc", "smc" -> "Super Nintendo"
        "gcm", "gcz", "rvz", "wbfs", "wud", "wux" -> "Console disc"
        "zip", "7z" -> "Archive"
        else -> "Game"
    }

    private fun moveSelection(delta: Int) {
        if (games.isNotEmpty()) selected = (selected + delta + games.size) % games.size
        refreshViews()
    }

    private fun launchSelected() {
        val game = games.getOrNull(selected) ?: run { pickFolder(); return }
        try {
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(game.uri, contentResolver.getType(game.uri) ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "No compatible app found. Choose an emulator for this system.", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshViews() {
        if (!::gridView.isInitialized) return
        gridView.setGames(games, selected, scanRoot != null)
        secondScreen?.updateGame(games.getOrNull(selected), selected + 1, games.size)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> { moveSelection(-1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> { moveSelection(1); return true }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> { launchSelected(); return true }
            KeyEvent.KEYCODE_BUTTON_Y -> { pickFolder(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        secondScreen?.dismiss()
        secondScreen = null
        secondDisplayId = null
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_PICK_FOLDER = 701
        private const val KEY_ROM_TREE_URI = "rom_tree_uri"
        val ROM_EXTENSIONS = setOf("3ds","cci","cia","3dsx","nds","srl","gba","agb","gbc","cgb","gb","n64","z64","v64","iso","cso","nes","fds","sfc","smc","gcm","gcz","rvz","wbfs","wud","wux","pce","cue","chd","md","gen","sms","gg","zip","7z")
    }
}

class HeroPresentation(context: Context, display: Display) : Presentation(context, display) {
    private var hero: HeroView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = HeroView(context)
        hero = view
        setContentView(view)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
    }
    fun updateGame(game: Game?, number: Int, total: Int) { hero?.setGame(game, number, total) }
}

class KojiiView(context: Context, private val mode: Mode, private val action: (String) -> Unit) : View(context) {
    enum class Mode { GRID }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var games: List<Game> = emptyList()
    private var selected = 0
    private var hasFolder = false
    private var cols = 5
    private val bounds = mutableListOf<RectF>()
    private fun color(c: Int) { p.color = c }
    fun setGames(items: List<Game>, selection: Int, folder: Boolean) { games = items; selected = selection.coerceIn(0, (items.size - 1).coerceAtLeast(0)); hasFolder = folder; invalidate() }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat()
        p.shader = android.graphics.LinearGradient(0f, 0f, w, h,
            intArrayOf(Color.rgb(8, 14, 29), Color.rgb(17, 29, 53), Color.rgb(10, 19, 38)),
            null, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        p.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        color(Color.rgb(25, 38, 64)); c.drawRoundRect(18f, 14f, w-18f, 74f, 20f, 20f, p)
        color(Color.rgb(72, 204, 190)); c.drawRoundRect(30f, 27f, 36f, 61f, 3f, 3f, p)
        p.textSize = 23f; color(Color.WHITE); c.drawText("KOJII", 47f, 51f, p)
        p.typeface = android.graphics.Typeface.create("sans-serif", 0); p.textSize = 13f; color(Color.rgb(165, 185, 215))
        c.drawText(if (hasFolder) "${games.size} GAMES  •  SELECT TO PLAY" else "YOUR LIBRARY  •  CHOOSE ROM FOLDER", 132f, 49f, p)
        color(Color.rgb(37, 55, 83)); c.drawRoundRect(w-172f, 25f, w-30f, 62f, 14f, 14f, p)
        p.textSize = 12f; color(Color.rgb(139, 228, 218)); c.drawText("Y  ROM FOLDER", w-157f, 48f, p)
        bounds.clear()
        val left = 20f; val gap = 12f; val top = 94f
        cols = if (w < 800) 4 else 6
        val cellW = (w-left*2-gap*(cols-1))/cols
        val cellH = ((h-top-24f)/3.1f).coerceIn(90f, 180f)
        val count = minOf(games.size, 300)
        for (i in 0 until count) {
            val row = i / cols; val col = i % cols
            val x = left + col*(cellW+gap); val y = top + row*(cellH+gap)
            val r = RectF(x,y,x+cellW,y+cellH); bounds.add(r)
            color(if (i == selected) Color.rgb(77, 218, 201) else Color.rgb(36, 52, 80))
            c.drawRoundRect(r, 17f, 17f, p)
            color(if (i == selected) Color.rgb(27, 76, 88) else Color.rgb(24, 37, 62))
            c.drawRoundRect(x+3,y+3,x+cellW-3,y+cellH-3,14f,14f,p)
            color(if (i == selected) Color.rgb(39, 101, 111) else Color.rgb(37, 55, 84))
            c.drawRoundRect(x+9,y+9,x+cellW-9,y+cellH-40,11f,11f,p)
            color(if (i == selected) Color.rgb(72, 204, 190) else Color.rgb(58, 81, 116))
            c.drawCircle(x+cellW/2, y+(cellH-31f)/2, minOf(cellW, cellH)*0.20f, p)
            p.textSize = 26f; color(if (i == selected) Color.WHITE else Color.rgb(190, 211, 239))
            val label = games[i].title.take(1).uppercase(Locale.ROOT)
            c.drawText(label, x+cellW/2-p.measureText(label)/2, y+(cellH-31f)/2+9f, p)
            p.textSize = 12f; color(Color.WHITE)
            drawWrapped(c, games[i].title, x+12, y+cellH-20, cellW-24, p)
        }
        if (games.isEmpty()) {
            color(Color.rgb(24, 36, 61)); c.drawRoundRect(28f, 110f, w-28f, h-28f, 22f, 22f, p)
            p.textSize = 25f; color(Color.WHITE)
            val title = if (hasFolder) "No ROMs found in this folder" else "Let’s build your game library"
            c.drawText(title, 48f, h*0.46f, p)
            p.textSize = 15f; color(Color.rgb(163, 182, 211))
            c.drawText(if (hasFolder) "Choose another folder with Y" else "Choose your existing ROM folder to scan it read-only.", 48f, h*0.46f+32f, p)
            color(Color.rgb(72, 204, 190)); c.drawRoundRect(48f,h*0.46f+55f,260f,h*0.46f+101f,14f,14f,p)
            p.textSize = 15f; color(Color.rgb(9, 25, 39)); c.drawText("CHOOSE ROM FOLDER",62f,h*0.46f+83f,p)
        }
        color(Color.rgb(20, 31, 52)); c.drawRoundRect(18f, h-34f, w-18f, h-4f, 12f, 12f, p)
        p.textSize = 11f; color(Color.rgb(151, 174, 207))
        c.drawText("▲ ▼ ◀ ▶  NAVIGATE     A / ENTER  OPEN     Y  CHANGE FOLDER", 30f, h-14f, p)
    }
    private fun drawWrapped(c: Canvas, text: String, x: Float, y: Float, max: Float, paint: Paint) {
        var line = ""; val words = text.split(" "); var yy = y
        for (word in words) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(next) > max && line.isNotEmpty()) { c.drawText(line, x, yy, paint); yy += 14f; line = word; if (yy > y+14f) break } else line = next
        }
        c.drawText(line.take(40), x, yy, paint)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            if (games.isEmpty()) { action("folder"); return true }
            val i = bounds.indexOfFirst { it.contains(e.x,e.y) }
            if (i >= 0) { if (i == selected) action("launch") else action("select:$i"); performClick(); return true }
            if (e.x > width*0.75f && e.y < 80f) action("folder")
            return true
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}

class HeroView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var game: Game? = null; private var number = 0; private var total = 0
    fun setGame(g: Game?, n: Int, t: Int) { game = g; number = n; total = t; invalidate() }
    override fun onDraw(c: Canvas) {
        super.onDraw(c); val w=width.toFloat(); val h=height.toFloat()
        c.drawColor(Color.rgb(10,15,29))
        p.shader = android.graphics.LinearGradient(0f,0f,w,h, intArrayOf(Color.rgb(22,48,70),Color.rgb(35,48,80),Color.rgb(10,15,29)),null,android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f,0f,w,h,p); p.shader=null
        p.color=Color.rgb(72,204,190); c.drawRoundRect(30f,30f,44f,90f,7f,7f,p)
        p.color=Color.WHITE; p.typeface=android.graphics.Typeface.create("sans-serif-medium",0); p.textSize=22f; c.drawText("KOJII",64f,65f,p)
        p.typeface=android.graphics.Typeface.create("sans-serif",0); p.textSize=13f; p.color=Color.rgb(155,181,211); c.drawText("GAME LIBRARY",64f,87f,p)
        val g=game
        p.textSize=if(w<700) 32f else 46f; p.typeface=android.graphics.Typeface.create("sans-serif-medium",0); p.color=Color.WHITE
        c.drawText((g?.title ?: "Ready when you are").take(32),40f,h*0.48f,p)
        p.textSize=18f; p.typeface=android.graphics.Typeface.create("sans-serif",0); p.color=Color.rgb(72,204,190)
        c.drawText(g?.system ?: "Select a game on the lower screen",42f,h*0.48f+36f,p)
        p.textSize=14f; p.color=Color.rgb(154,174,204); c.drawText(if(g!=null) "GAME $number OF $total" else "CHOOSE A ROM FOLDER TO BEGIN",42f,h-40f,p)
    }
}
