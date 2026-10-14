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
    private var secondScreen: LibraryPresentation? = null
    private var secondDisplayId: Int? = null
    private var games: List<Game> = emptyList()
    private var selected = 0
    private var scanRoot: Uri? = null
    private lateinit var heroView: HeroView
    private var libraryView: KojiiView? = null
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
        heroView = HeroView(this) { pickFolder() }
        setContentView(heroView)
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
        override fun onDisplayRemoved(displayId: Int) { secondScreen?.dismiss(); secondScreen = null; secondDisplayId = null }
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
                    secondScreen = LibraryPresentation(this, display) { action ->
                        when (action) {
                            "folder" -> pickFolder()
                            "prev" -> moveSelection(-1)
                            "next" -> moveSelection(1)
                            "launch" -> launchSelected()
                            else -> if (action.startsWith("select:")) {
                                selected = action.substringAfter(":").toIntOrNull() ?: 0
                                refreshViews()
                            }
                        }
                    }.also { it.show() }
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
        if (!::heroView.isInitialized) return
        heroView.setGame(games.getOrNull(selected), selected + 1, games.size, scanRoot != null)
        secondScreen?.setGames(games, selected, scanRoot != null)
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

class LibraryPresentation(context: Context, display: Display, private val action: (String) -> Unit) : Presentation(context, display) {
    private var library: KojiiView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = KojiiView(context, KojiiView.Mode.GRID, action)
        library = view
        setContentView(view)
    }
    fun setGames(games: List<Game>, selected: Int, hasFolder: Boolean) {
        library?.setGames(games, selected, hasFolder)
    }
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

class HeroView(context: Context, private val chooseFolder: () -> Unit) : View(context) {
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && game == null) chooseFolder()
        return true
    }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var game: Game? = null; private var number = 0; private var total = 0
    private var hasFolder = false
    fun setGame(g: Game?, n: Int, t: Int, folder: Boolean) { game = g; number = n; total = t; hasFolder = folder; invalidate() }
    override fun onDraw(c: Canvas) {
        super.onDraw(c); val w=width.toFloat(); val h=height.toFloat()
        c.drawColor(Color.rgb(32,32,32))
        p.shader = android.graphics.LinearGradient(0f,0f,w,h, intArrayOf(Color.rgb(49,49,49),Color.rgb(31,31,31),Color.rgb(18,18,18)),null,android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f,0f,w,h,p); p.shader=null
        val barH = (h * 0.09f).coerceAtLeast(30f)
        p.color=Color.rgb(18,18,18); c.drawRect(0f,0f,w,barH,p)
        p.color=Color.rgb(85,190,235); c.drawRoundRect(10f,barH*0.28f,w*0.33f,barH*0.76f,8f,8f,p)
        p.color=Color.WHITE; p.textSize=barH*0.48f; p.typeface=android.graphics.Typeface.create("sans-serif-medium",0)
        c.drawText("Internet",w*0.08f,barH*0.66f,p)
        val g=game
        val iconSize=minOf(w*0.24f,h*0.27f)
        val iconLeft=(w-iconSize)/2f; val iconTop=barH+ h*0.035f
        p.color=Color.rgb(90,90,90); c.drawRoundRect(iconLeft-5,iconTop-5,iconLeft+iconSize+5,iconTop+iconSize+5,18f,18f,p)
        p.color=Color.rgb(225,225,225); c.drawRoundRect(iconLeft,iconTop,iconLeft+iconSize,iconTop+iconSize,14f,14f,p)
        p.color=Color.rgb(40,40,40); p.textSize=iconSize*0.58f; p.typeface=android.graphics.Typeface.create("sans-serif",0)
        val mark=if(g!=null) g.title.take(1).uppercase(Locale.ROOT) else "?"
        c.drawText(mark,iconLeft+iconSize/2-p.measureText(mark)/2,iconTop+iconSize*0.68f,p)
        val titleTop=iconTop+iconSize+h*0.025f
        p.color=Color.rgb(224,224,224); c.drawRoundRect(w*0.18f,titleTop,w*0.82f,titleTop+h*0.17f,16f,16f,p)
        p.color=Color.rgb(40,40,40); p.textSize=(w*0.035f).coerceIn(12f,20f); p.typeface=android.graphics.Typeface.create("sans-serif",0)
        val title=(g?.title ?: if(hasFolder) "No games found" else "Choose ROM folder").take(34)
        c.drawText(title,w/2-p.measureText(title)/2,titleTop+h*0.075f,p)
        p.textSize=(w*0.027f).coerceIn(10f,15f)
        val system=g?.system ?: if(hasFolder) "Choose another folder" else "Kojii game library"
        c.drawText(system,w/2-p.measureText(system)/2,titleTop+h*0.125f,p)
        p.color=Color.rgb(16,16,16); c.drawRect(0f,h*0.84f,w,h,p)
        p.color=Color.WHITE; p.textSize=(w*0.04f).coerceIn(14f,22f)
        val footer=if(g!=null) "Open  •  $number / $total" else "Choose ROM Folder"
        c.drawText(footer,w/2-p.measureText(footer)/2,h*0.93f,p)
        if(g==null) {
            p.color=Color.argb(1,0,0,0)
        }
    }
}
