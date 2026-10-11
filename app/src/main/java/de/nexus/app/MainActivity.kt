package de.nexus.app

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import android.os.Handler
import android.os.Looper

class Memory(c: Context, path: String) : SQLiteOpenHelper(c, path, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE mem(id INTEGER PRIMARY KEY AUTOINCREMENT, txt TEXT, ts INTEGER)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
    fun add(t: String) {
        writableDatabase.insert("mem", null, ContentValues().apply { put("txt", t); put("ts", System.currentTimeMillis()) })
    }
    fun recall(q: String): List<String> {
        val words = q.lowercase().split(Regex("\\W+")).filter { it.length > 3 }.toSet()
        val all = ArrayList<String>()
        readableDatabase.rawQuery("SELECT txt FROM mem ORDER BY id DESC LIMIT 300", null).use { while (it.moveToNext()) all.add(it.getString(0)) }
        val best = all.drop(4).map { m -> m to words.count { m.lowercase().contains(it) } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(5).map { it.first }
ff        return (best + all.take(4)).distinct()
    }
}

class MainActivity : Activity() {
    private val bg = Executors.newSingleThreadExecutor()
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var mem: Memory
    private lateinit var skills: SkillEngine
    private var tts: TextToSpeech? = null
    private var llm: LlmInference? = null
    private var camUri: Uri? = null
    private lateinit var colView: LinearLayout
    private val btns = ArrayList<Button>()
    @Volatile private var started = false
    @Volatile private var busy = false
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleDelayMs = 5 * 60 * 1000L
    private val root = File(Environment.getExternalStorageDirectory(), "Documents/Nexus")
    private val inbox = File(root, "inbox")
    private val done = File(root, "verarbeitet")
    private val modelFile = File(root, "model.task")
    private val base = "Du bist Nexus, eine persönliche KI auf dem Handy deines Nutzers. Antworte immer auf Deutsch, kurz und klar. " +
        "Wenn du etwas nicht weißt oder nicht weiterkommst, beginne deine Antwort mit 'FRAGE:' und bitte den Nutzer um Hilfe oder Dateien."

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val dp = resources.displayMetrics.density
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(18, 18, 24)); setPadding((8*dp).toInt(), (32*dp).toInt(), (8*dp).toInt(), (8*dp).toInt()) }
        log = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; setPadding(12, 12, 12, 12) }
        scroll = ScrollView(this).apply { addView(log) }
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply { hint = "Nachricht oder /hilfe"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); imeOptions = EditorInfo.IME_ACTION_SEND; setSingleLine(true) }
        val cam = Button(this).apply { text = "📷" }
        val mic = Button(this).apply { text = "🎤" }
        val send = Button(this).apply { text = "➤" }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(cam); row.addView(mic); row.addView(send)
        col.addView(row)
        setContentView(col)
        colView = col; btns.addAll(listOf(cam, mic, send))

        send.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, _, _ -> submit(); true }
        mic.setOnClickListener {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            try { startActivityForResult(i, 1) } catch (e: Exception) { say("Nexus", "Spracheingabe nicht verfügbar.") }
        }
        cam.setOnClickListener {
            try {
                camUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "nexus_${System.currentTimeMillis()}.jpg"); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg") })
                startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, camUri), 3)
            } catch (e: Exception) { say("Nexus", "Kamera nicht verfügbar: ${e.message}") }
        }
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.GERMAN }
        scheduleIdle()
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA), 2)
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager())
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
    }

    override fun onResume() { super.onResume(); start() }

    private val locked = listOf("/Android/", "/.android_secure", "/LOST.DIR")
    private fun storageRoot() = Environment.getExternalStorageDirectory()
    private fun resolveSpace(rel: String): File {
        val f = File(storageRoot(), rel).canonicalFile
        require(f.path.startsWith(storageRoot().canonicalPath)) { "außerhalb des Speichers" }
        require(locked.none { f.path.contains(it) }) { "geschützter Systemordner" }
        return f
    }
    private fun listSpace(rel: String): String = try {
        resolveSpace(rel).listFiles()?.joinToString("\n") { (if (it.isDirectory) "[Ordner] " else "") + it.name }?.ifBlank { "(leer)" } ?: "Ordner nicht gefunden."
    } catch (e: Exception) { "Fehler: ${e.message}" }
    private fun deleteSpace(rel: String): String = try {
        val f = resolveSpace(rel)
        if (!f.exists()) "Nicht gefunden: $rel" else if (f.deleteRecursively()) "Gelöscht: $rel" else "Konnte nicht gelöscht werden: $rel"
    } catch (e: Exception) { "Verweigert: ${e.message}" }

    private fun start() {
        if (started) return
        started = true
        bg.execute {
            try {
                if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                    say("Nexus", "Bitte erlaube 'Zugriff auf alle Dateien'. Danach komm einfach zurück in die App."); started = false; return@execute
                }
                inbox.mkdirs(); done.mkdirs()
                runOnUiThread { applyTheme() }
                mem = Memory(this, File(root, "gedaechtnis.db").path)
                skills = SkillEngine(root, mem)
                val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                File(root, "backup").mkdirs()
                File(root, "gedaechtnis.db").takeIf { it.exists() }?.copyTo(File(root, "backup/gedaechtnis_$day.db"), true)
                if (!modelFile.exists()) {
                    say("Nexus", "Hallo! Mir fehlt noch mein Sprachmodell. Kopiere 'model.task' per Kabel nach Documents/Nexus und öffne mich neu."); started = false; return@execute
                }
                say("Nexus", "Ich lade mein Sprachmodell, einen Moment …")
                llm = LlmInference.createFromOptions(this, LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.path).setMaxTokens(512).build())
                say("Nexus", "Ich bin bereit. Tipp /hilfe für meine Befehle."); checkUpdate()
            } catch (e: Throwable) { say("Nexus", "Fehler beim Start: ${e.message}"); started = false }
        }
    }

    private fun turn(u: String) = "<start_of_turn>user\n$u<end_of_turn>\n<start_of_turn>model\n"
    private fun gen(u: String): String = llm!!.generateResponse(turn(u)).trim()

    private fun sys(): String {
        val rules = File(root, "regeln.txt").takeIf { it.exists() }?.readText() ?: ""
        val sk = skills.list().joinToString("\n") { "- ${it.first}: ${it.second}" }
        return base + (if (rules.isNotBlank()) "\nRegeln des Nutzers:\n$rules" else "") +
            (if (sk.isNotBlank()) "\nFähigkeiten (nur wenn nötig, dann antworte NUR mit 'SKILL: name | argument'):\n$sk" else "")
    }

    private fun readInbox(): Int {
        var n = 0
        inbox.listFiles()?.filter { it.isFile }?.forEach { f ->
            when (f.extension.lowercase()) {
                "js" -> {
                    val code = f.readText()
                    val name = Regex("//\\s*name:\\s*([a-z0-9_]{1,30})").find(code)?.groupValues?.get(1) ?: f.nameWithoutExtension.lowercase().replace(Regex("[^a-z0-9_]"), "_")
                    val err = skills.install(name, code)
                    say("Nexus", if (err == null) "Neue Fähigkeit '$name' aus ${f.name} getestet und installiert." else "FRAGE: Die Datei ${f.name} hat den Test nicht bestanden: $err")
                    n++
                }
                else -> {
                    val looksText = try { f.readBytes().take(2000).count { b -> b.toInt() == 9 || b.toInt() == 10 || b.toInt() == 13 || b.toInt() in 32..126 || b.toInt() < 0 } > 1900 } catch (e: Exception) { false }
                    if (looksText) { ingest(f); n++ } else {
                        val unlesbar = File(root, "unlesbar").apply { mkdirs() }
                        f.renameTo(File(unlesbar, f.name))
                        say("Nexus", "FRAGE: ${f.name} kann ich nicht lesen (kein Text). Ich habe sie nach 'unlesbar' verschoben, damit ich nicht immer wieder danach frage.")
                        return@forEach
                    }
                }
            }
            f.renameTo(File(done, f.name))
        }
        return n
    }

    private fun ingest(f: File) {
        val text = f.readText()
        val chunks = text.chunked(3000)
        say("Nexus", "Lese ${f.name} (${chunks.size} Abschnitt(e)) und lerne daraus …")
        chunks.forEachIndexed { i, chunk ->
            try {
                val facts = gen("Lies diesen Text und schreibe die wichtigsten Fakten als kurze, einzelne Sätze auf Deutsch, einer pro Zeile, keine Einleitung, keine Nummerierung. Finde auch Zusammenhänge zwischen den Fakten, falls welche da sind.\n\nText:\n$chunk")
                facts.lines().map { it.trim().trimStart('-', '*', '•').trim() }.filter { it.length > 4 }
                    .forEach { mem.add("[Wissen aus ${f.name}] $it") }
            } catch (e: Exception) { mem.add("[Datei ${f.name} Teil ${i+1}] ${chunk.take(800)}") }
        }
    }

    private fun submit() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText(""); say("Du", t); idleHandler.removeCallbacks(idleTask)
        bg.execute {
            try {
                if (llm == null) { say("Nexus", "Mein Sprachmodell ist noch nicht geladen."); return@execute }
                val n = readInbox(); if (n > 0) say("Nexus", "$n Datei(en) aus dem Posteingang verarbeitet.")
                if (t.startsWith("/")) { command(t); return@execute }
                val ctx = mem.recall(t).joinToString("\n") { "- $it" }
                var a = gen("${sys()}\n\nWas du dir gemerkt hast:\n$ctx\n\nNutzer: $t")
                if (a.startsWith("SKILL:")) {
                    val p = a.removePrefix("SKILL:").split("|", limit = 2)
                    val name = p[0].trim(); val arg = p.getOrElse(1) { "" }.trim()
                    val res = try { skills.run(name, arg) } catch (e: Throwable) { "Fehler: ${e.message}" }
                    a = gen("Nutzer fragte: $t\nErgebnis der Fähigkeit '$name': $res\nAntworte dem Nutzer kurz auf Deutsch.")
                }
                mem.add("Nutzer: $t | Nexus: $a")
                say("Nexus", a); tts?.speak(a, TextToSpeech.QUEUE_FLUSH, null, "n")
            } catch (e: Throwable) { say("Nexus", "Fehler: ${e.message}") }
        }
    }

    private fun checkUpdate() {
        bg.execute {
            try {
                val slug = repoSlug(); if (slug.isBlank()) return@execute
                val api = "https://api.github.com/repos/$slug/releases/latest"
                val j = org.json.JSONObject(URL(api).readText())
                val tag = j.optString("tag_name", "")
                val cur = packageManager.getPackageInfo(packageName, 0).versionName
                if (tag.isBlank() || tag.removePrefix("v") == cur) return@execute
                val assets = j.getJSONArray("assets")
                var apkUrl = ""
                for (i in 0 until assets.length()) { val a = assets.getJSONObject(i); if (a.getString("name").endsWith(".apk")) apkUrl = a.getString("browser_download_url") }
                if (apkUrl.isBlank()) return@execute
                say("Nexus", "Eine neue Version ($tag) ist verfügbar. Ich lade sie herunter …")
                val f = File(cacheDir, "update.apk")
                (URL(apkUrl).openConnection() as HttpsURLConnection).inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(i)
                say("Nexus", "Bitte bestätige die Installation der neuen Version. Danach starte ich wieder normal.")
            } catch (e: Exception) { }
        }
    }
    private fun repoSlug(): String = File(root, "update_repo.txt").takeIf { it.exists() }?.readText()?.trim() ?: ""

    private fun command(t: String) {
        val cmd = t.substringBefore(" "); val rest = t.substringAfter(" ", "").trim()
        when (cmd) {
            "/lerne" -> learn(rest)
            "/skills" -> say("Nexus", skills.list().joinToString("\n") { "- ${it.first}: ${it.second}" }.ifBlank { "Noch keine Fähigkeiten." })
            "/zurueck" -> say("Nexus", if (skills.rollback(rest)) "'$rest' auf die vorherige Version zurückgesetzt." else "Kein Backup für '$rest'.")
            "/regel" -> addRule(rest)
            "/design" -> design(rest)
            "/designzurueck" -> designBack()
            "/update" -> { if (repoSlug().isBlank()) say("Nexus", "Leg eine Datei update_repo.txt mit 'benutzername/Nexus' in Documents/Nexus, dann kann ich nach Updates sehen.") else { say("Nexus", "Ich sehe nach …"); checkUpdate() } }
            "/dateien" -> say("Nexus", listSpace(rest))
            "/loesche" -> say("Nexus", deleteSpace(rest))
            "/wunsch" -> { File(root, "wunschzettel.txt").appendText("- ${SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date())}: $rest\n"); say("Nexus", "Aufgeschrieben in wunschzettel.txt. Gib den Zettel Claude, dann bekommst du eine neue App-Version.") }
            else -> say("Nexus", "Befehle:\n/lerne <Wunsch> – ich schreibe mir eine neue Fähigkeit, teste sie und installiere sie\n/skills – meine Fähigkeiten\n/zurueck <name> – letzte Version wiederherstellen\n/regel <Text> – neue Verhaltensregel\n/design <Wunsch> – neues Aussehen (getestet, mit Backup)\n/designzurueck – altes Aussehen\n/wunsch <Text> – Wunsch für eine neue App-Version\n/update – nach neuer App-Version suchen und installieren\n/dateien <Ordner> – Dateien auf dem Handy auflisten\n/loesche <Pfad> – Datei oder Ordner löschen\n📷 – Foto analysieren")
        }
    }

    private fun applyTheme() {
        try {
            val f = File(root, "design.json"); if (!f.exists()) return
            val j = org.json.JSONObject(f.readText())
            val tc = Color.parseColor(j.getString("text")); val ac = Color.parseColor(j.getString("accent"))
            colView.setBackgroundColor(Color.parseColor(j.getString("bg"))); log.setTextColor(tc); input.setTextColor(tc)
            log.textSize = j.getInt("size").coerceIn(12, 28).toFloat()
            btns.forEach { it.setBackgroundColor(ac); it.setTextColor(Color.WHITE) }
        } catch (e: Exception) { }
    }

    private fun validTheme(s: String): String? = try {
        val j = org.json.JSONObject(s)
        val b = Color.parseColor(j.getString("bg")); val t = Color.parseColor(j.getString("text")); Color.parseColor(j.getString("accent"))
        val sz = j.getInt("size")
        if (sz !in 12..28) "size muss 12 bis 28 sein"
        else if (Math.abs(Color.luminance(b) - Color.luminance(t)) < 0.4f) "Text und Hintergrund sind zu ähnlich, nicht lesbar"
        else null
    } catch (e: Exception) { e.message ?: "kein gültiges JSON" }

    private fun design(wish: String) {
        var err = ""
        for (i in 1..3) {
            val s = gen("Antworte NUR mit JSON, ohne Erklärung und ohne Backticks, genau mit den Feldern bg, text, accent (Farben als #RRGGBB) und size (Zahl 12 bis 28). Text und Hintergrund brauchen starken Kontrast. Wunsch für das Aussehen: $wish" +
                (if (err.isNotEmpty()) "\nDein letzter Versuch scheiterte: $err" else "")).replace("```json", "").replace("```", "").trim()
            val e = validTheme(s)
            if (e == null) {
                val f = File(root, "design.json")
                if (f.exists()) f.copyTo(File(root, "backup/design_${System.currentTimeMillis()}.json"), true)
                f.writeText(s); runOnUiThread { applyTheme() }
                say("Nexus", "Neues Design getestet und angewendet. Mit /designzurueck gehst du zurück."); return
            }
            err = e
        }
        say("Nexus", "FRAGE: Mein Design-Versuch ist 3 Mal gescheitert ($err). Beschreib es mir bitte genauer, z. B. mit Farbnamen.")
    }

    private fun designBack() {
        val b = File(root, "backup").listFiles()?.filter { it.name.startsWith("design_") }?.maxByOrNull { it.name }
        File(root, "design.json").writeText(b?.readText() ?: "{\"bg\":\"#121218\",\"text\":\"#FFFFFF\",\"accent\":\"#3D5AFE\",\"size\":16}")
        b?.delete(); runOnUiThread { applyTheme() }; say("Nexus", "Vorheriges Design wiederhergestellt.")
    }

    private fun addRule(rule: String) {
        val f = File(root, "regeln.txt"); val old = if (f.exists()) f.readText() else ""
        File(root, "backup/regeln_${System.currentTimeMillis()}.txt").writeText(old)
        f.writeText(old + "- $rule\n")
        try {
            if (gen("${sys()}\n\nNutzer: Sag kurz Hallo.").isBlank()) throw Exception("leere Antwort")
            say("Nexus", "Regel getestet und gespeichert.")
        } catch (e: Throwable) { f.writeText(old); say("Nexus", "Regel verworfen, Test fehlgeschlagen: ${e.message}") }
    }

    private fun learn(wish: String) {
        var err = ""; var code = ""
        say("Nexus", "Ich schreibe eine neue Fähigkeit und teste sie …")
        for (i in 1..3) {
            val p = "Schreibe eine JavaScript-Fähigkeit für eine Android-KI. Antworte NUR mit Code, ohne Erklärung und ohne Backticks.\n" +
                "Format:\n// name: kleinbuchstaben_name\n// beschreibung: ein Satz\nfunction run(arg) { /* gibt einen String zurück */ }\nfunction test() { return run('test') != null; }\n" +
                "Verfügbar: host.httpGet(url), host.readFile(pfad), host.writeFile(pfad, text), host.remember(text), host.recall(suche). Kein import, kein require, kein fetch.\nWunsch: $wish" +
                (if (err.isNotEmpty()) "\nDein letzter Versuch scheiterte: $err\nLetzter Code:\n$code" else "")
            code = gen(p).replace("```javascript", "").replace("```js", "").replace("```", "").trim()
            val name = Regex("//\\s*name:\\s*([a-z0-9_]{1,30})").find(code)?.groupValues?.get(1)
            if (name == null) { err = "Zeile '// name: ...' fehlt"; continue }
            val e = skills.install(name, code)
            if (e == null) { say("Nexus", "Neue Fähigkeit '$name' gelernt und erfolgreich getestet."); return }
            err = e
        }
        say("Nexus", "FRAGE: Ich habe es 3 Mal versucht, letzter Fehler: $err. Kannst du mir helfen oder eine fertige .js-Datei in den Posteingang legen?")
    }

    private fun analyze(uri: Uri) {
        try {
            val img = InputImage.fromFilePath(this, uri)
            ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS).process(img).addOnSuccessListener { ls ->
                val lab = ls.take(6).joinToString { it.text }
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(img).addOnSuccessListener { tx ->
                    bg.execute {
                        try {
                            val a = gen("Das Handy hat ein Foto gemacht. Erkannte Objekte (englisch): $lab. Erkannter Text: ${tx.text.take(500)}. Beschreibe auf Deutsch in höchstens 2 Sätzen, was zu sehen ist.")
                            mem.add("[Foto] $a"); say("Nexus", a); tts?.speak(a, TextToSpeech.QUEUE_FLUSH, null, "n")
                        } catch (e: Throwable) { say("Nexus", "Fehler: ${e.message}") }
                    }
                }
            }.addOnFailureListener { say("Nexus", "Bildanalyse fehlgeschlagen: ${it.message}") }
        } catch (e: Exception) { say("Nexus", "Foto konnte nicht gelesen werden: ${e.message}") }
    }

    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        super.onActivityResult(r, c, d)
        if (r == 1 && c == RESULT_OK) d?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { input.setText(it); submit() }
        if (r == 3 && c == RESULT_OK) camUri?.let { if (llm == null) say("Nexus", "Mein Sprachmodell ist noch nicht geladen.") else analyze(it) }
    }

    private fun say(who: String, text: String) = runOnUiThread {
        log.append("$who: $text\n\n"); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        scheduleIdle()
    }

    private val idleTask = Runnable { selfImprove() }
    private fun scheduleIdle() {
        idleHandler.removeCallbacks(idleTask)
        idleHandler.postDelayed(idleTask, idleDelayMs)
    }

    private fun selfImprove() {
        if (busy || llm == null) { scheduleIdle(); return }
        busy = true
        bg.execute {
            try {
                val n = readInbox()
                if (n > 0) { say("Nexus", "$n Datei(en) in Ruhe gelesen und gelernt."); busy = false; scheduleIdle(); return@execute }
                val recent = mem.recall("").joinToString("\n")
                val idea = gen("Du hast gerade nichts zu tun. Schau dir an, was du über den Nutzer weißt, und überlege, ob eine kleine neue Fähigkeit (/lerne) ihm helfen könnte, oder ob dir etwas fehlt, um besser zu werden. Wenn ja, antworte mit 'IDEE: <ein Satz>'. Wenn dir nichts Sinnvolles einfällt, antworte nur mit 'NICHTS'.\n\nBekanntes:\n$recent")
                if (idea.startsWith("IDEE:")) {
                    val wish = idea.removePrefix("IDEE:").trim()
                    say("Nexus", "Mir ist in Ruhe etwas eingefallen: $wish Ich versuche es.")
                    learn(wish)
                }
            } catch (e: Exception) { } finally { busy = false; scheduleIdle() }
        }
    }
}
