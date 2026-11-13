package com.osama.xtreamtv

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Package 5, item 2 (approved 2026-11-13): the phone as a small control panel for the TV, on the home
 * Wi-Fi only, while the app runs. A phone joins by scanning the TV's code once (Settings → Use my
 * phone) and is remembered for 30 days; only phones that joined get anything. It searches the
 * playlist, plays or opens titles on the TV, and works as a remote. The phone never sees logins,
 * addresses or keys: titles and controls only.
 */
object PhonePanel {

    private const val PORT = 8098
    private const val REMEMBER_MS = 30L * 24 * 3600 * 1000
    private const val JOIN_MS = 5L * 60 * 1000

    private lateinit var app: Application
    private var page: PhonePage? = null
    private var current: WeakReference<Activity>? = null
    private val main = Handler(Looper.getMainLooper())

    /** The join code shown as a QR code, and until when it works. */
    @Volatile private var joinCode: String? = null
    @Volatile private var joinUntil = 0L

    private fun sp() = app.getSharedPreferences("phone_panel", Context.MODE_PRIVATE)

    fun init(application: Application) {
        app = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { current = WeakReference(a) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        // Phones already joined can connect as long as the app runs.
        if (phones().isNotEmpty()) ensureRunning()
    }

    /** The panel's fixed address part (so remembered phones keep working). */
    private fun pathToken(): String = sp().getString("path", null) ?: PhonePage.newToken().also { sp().edit().putString("path", it).apply() }

    private fun ensureRunning(): PhonePage? {
        page?.takeIf { it.running }?.let { return it }
        val p = PhonePage(PORT, Long.MAX_VALUE / 4, ::answer, pathToken())
        if (!p.start()) return null
        page = p
        return p
    }

    /** A new join code for the QR code on the TV: the address to scan, or null off the home Wi-Fi. */
    fun joinAddress(): String? {
        val p = ensureRunning() ?: return null
        joinCode = PhonePage.newToken().take(8)
        joinUntil = SystemClock.elapsedRealtime() + JOIN_MS
        // Test builds: the join path for the test tools (works 5 minutes, once).
        if (BuildConfig.DEBUG) android.util.Log.d("PhonePanel", "join /p/${p.token}/?join=$joinCode")
        return p.address()?.let { "$it/?join=$joinCode" }
    }

    // ---- Remembered phones ----

    class Phone(val id: String, val name: String, val lastSeen: Long)

    fun phones(): List<Phone> {
        val o = runCatching { JSONObject(sp().getString("phones", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val now = System.currentTimeMillis()
        return o.keys().asSequence().mapNotNull { id ->
            val p = o.optJSONObject(id) ?: return@mapNotNull null
            Phone(id, p.optString("name", "Phone"), p.optLong("seen")).takeIf { now - it.lastSeen < REMEMBER_MS }
        }.sortedByDescending { it.lastSeen }.toList()
    }

    private fun savePhone(id: String, name: String) {
        val o = runCatching { JSONObject(sp().getString("phones", "{}") ?: "{}") }.getOrDefault(JSONObject())
        o.put(id, JSONObject().put("name", name).put("seen", System.currentTimeMillis()))
        sp().edit().putString("phones", o.toString()).apply()
    }

    fun remove(id: String) {
        val o = runCatching { JSONObject(sp().getString("phones", "{}") ?: "{}") }.getOrDefault(JSONObject())
        o.remove(id)
        sp().edit().putString("phones", o.toString()).apply()
        Diag.log("phone", "a phone was removed")
    }

    private fun known(cookie: String?): Boolean = cookie != null && phones().any { it.id == cookie }

    // ---- The page ----

    private fun answer(path: String, method: String, fields: Map<String, String>, cookie: String?): PhonePage.Response {
        var phoneId = cookie?.takeIf { known(it) }
        var setCookie: String? = null
        // Joining with the code from the TV's QR code (5 minutes).
        val join = fields["join"]
        if (phoneId == null && join != null && join == joinCode && SystemClock.elapsedRealtime() < joinUntil) {
            phoneId = PhonePage.newToken()
            setCookie = phoneId
            joinCode = null
            savePhone(phoneId, fields["name"]?.take(30)?.ifBlank { null } ?: "Phone")
            Diag.log("phone", "a phone joined")
        }
        if (phoneId == null) {
            return PhonePage.Response(PhonePage.page("Join", "<h1>Not connected</h1><p>On the TV, open <b>Settings &rarr; Use Phone</b> and scan the code with this phone.</p>"))
        }
        savePhone(phoneId, phones().firstOrNull { it.id == phoneId }?.name ?: "Phone")
        return when (path) {
            "/search" -> PhonePage.Response(search(fields["q"].orEmpty()), "application/json; charset=utf-8")
            "/play" -> { play(fields["type"].orEmpty(), fields["id"].orEmpty(), fields["mode"] == "play"); PhonePage.Response("ok", "text/plain") }
            "/key" -> { key(fields["k"].orEmpty()); PhonePage.Response("ok", "text/plain") }
            else -> PhonePage.Response(panelHtml(), setCookie = setCookie)
        }
    }

    private fun search(q: String): String {
        val text = q.trim()
        val out = JSONArray()
        if (text.length < 2) return out.toString()
        val lists = listOf(ContentType.MOVIE, ContentType.SERIES, ContentType.LIVE).flatMap { Session.streamCache["${it.name}:__all__"].orEmpty() }
        lists.asSequence()
            .filter { Versions.shownName(it).contains(text, ignoreCase = true) }
            .sortedWith(compareBy<StreamItem> { !Versions.shownName(it).startsWith(text, ignoreCase = true) }.thenByDescending { Ratings.scoreOf(it) })
            .distinctBy { "${it.type}:${Versions.shownName(it).lowercase()}" }
            .take(30)
            .forEach { s ->
                val kind = when (s.type) { ContentType.MOVIE -> "Movie"; ContentType.SERIES -> "Series"; ContentType.LIVE -> "Channel" }
                val imdb = Ratings.known(s.type, s.id)?.imdb
                out.put(JSONObject().put("type", s.type.name).put("id", s.id).put("name", Versions.shownName(s))
                    .put("kind", listOfNotNull(kind, s.year, imdb?.let { "IMDb $it" }).joinToString(" · ")).put("poster", s.image ?: ""))
            }
        return out.toString()
    }

    private fun play(typeName: String, id: String, now: Boolean) {
        val type = runCatching { ContentType.valueOf(typeName) }.getOrNull() ?: return
        val item = Session.streamCache["${type.name}:__all__"]?.firstOrNull { it.id == id } ?: return
        main.post {
            val ctx = current?.get() ?: return@post
            val api = Session.api(ctx) ?: return@post
            val prefs = Prefs(ctx)
            val intent = when {
                type == ContentType.LIVE -> { Session.zapList = listOf(item); Session.zapTitle = "From your phone"; PlayerActivity.liveIntent(ctx, 0) }
                type == ContentType.MOVIE && now -> {
                    val key = "movie_${item.id}"
                    val entry = WatchEntry(key, ContentType.MOVIE, item.id, item.ext, Versions.shownName(item), null, item.image)
                    PlayerActivity.vodIntent(ctx, api.movieUrl(item.id, item.ext), Versions.shownName(item), key, prefs.position(key), entry)
                }
                else -> DetailsActivity.intent(ctx, item)
            }
            ctx.startActivity(intent)
            Diag.log("phone", "${if (now) "played" else "opened"} from the phone${Diag.title(item.name)}")
        }
    }

    private fun key(k: String) {
        main.post {
            val a = current?.get() ?: return@post
            if (k == "home") {
                a.startActivity(Intent(a, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                return@post
            }
            val code = when (k) {
                "up" -> KeyEvent.KEYCODE_DPAD_UP; "down" -> KeyEvent.KEYCODE_DPAD_DOWN
                "left" -> KeyEvent.KEYCODE_DPAD_LEFT; "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
                "ok" -> KeyEvent.KEYCODE_DPAD_CENTER; "back" -> KeyEvent.KEYCODE_BACK
                "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                else -> return@post
            }
            // Nothing highlighted (touch mode): the first press brings the highlight back, like the remote does.
            if (a.currentFocus == null && code != KeyEvent.KEYCODE_BACK) {
                val all = ArrayList<android.view.View>()
                a.window.decorView.addFocusables(all, android.view.View.FOCUS_DOWN, android.view.View.FOCUSABLES_ALL)
                all.firstOrNull { it.isShown }?.requestFocusFromTouch()
                if (code != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) return@post
            }
            val t = SystemClock.uptimeMillis()
            val handled = a.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
            // Moving the highlight is Android's own step after the app's key handling: done here for arrows
            // the screen didn't use itself.
            if (!handled) {
                val dir = when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> android.view.View.FOCUS_UP; KeyEvent.KEYCODE_DPAD_DOWN -> android.view.View.FOCUS_DOWN
                    KeyEvent.KEYCODE_DPAD_LEFT -> android.view.View.FOCUS_LEFT; KeyEvent.KEYCODE_DPAD_RIGHT -> android.view.View.FOCUS_RIGHT
                    else -> 0
                }
                if (dir != 0) a.currentFocus?.focusSearch(dir)?.requestFocus(dir)
            }
            a.dispatchKeyEvent(KeyEvent(t, t + 50, KeyEvent.ACTION_UP, code, 0))
        }
    }

    private fun panelHtml(): String = PhonePage.page(
        "Phone panel",
        """<style>.tabs{display:flex;gap:8px;margin:10px 0}.tab{flex:1;padding:10px;border-radius:10px;border:1px solid #c5ccd4;text-align:center;font-size:15px}
.tab.on{background:#2f6fd6;color:#fff;border-color:#2f6fd6}.r{display:flex;gap:10px;align-items:center;padding:8px 0;border-bottom:1px solid #e3e7ec}
.r img{width:44px;height:64px;object-fit:cover;border-radius:6px;background:#dde3ea}.r .n{flex:1;font-size:15px}.r .n small{display:block;color:#6b7480}
.r button{width:auto;margin:0;padding:8px 10px;font-size:13px}.pad{display:grid;grid-template-columns:repeat(3,72px);grid-template-rows:repeat(3,72px);gap:10px;justify-content:center;margin:18px 0}
.k{border-radius:50%;border:1px solid #c5ccd4;background:#fff;font-size:24px;display:flex;align-items:center;justify-content:center;user-select:none}
.ok{background:#2f6fd6;color:#fff;font-size:17px}.row{display:flex;gap:10px}.row button{margin:0}</style>
<div class=tabs><div class='tab on' id=t1 onclick=show(1)>Search</div><div class=tab id=t2 onclick=show(2)>Remote</div></div>
<div id=p1><input id=q placeholder='Movie, series, channel…' autocomplete=off oninput=find()><div id=res></div></div>
<div id=p2 style=display:none><div class=pad><span></span><div class=k onclick="k('up')">&#9650;</div><span></span>
<div class=k onclick="k('left')">&#9664;</div><div class='k ok' onclick="k('ok')">OK</div><div class=k onclick="k('right')">&#9654;</div>
<span></span><div class=k onclick="k('down')">&#9660;</div><span></span></div>
<div class=row><button onclick="k('back')">Back</button><button onclick="k('home')">Home</button><button onclick="k('playpause')">&#9199;</button></div></div>""",
        """const base=location.pathname.replace(/[/]?$/,'');function show(n){p1.style.display=n==1?'':'none';p2.style.display=n==2?'':'none';t1.className='tab'+(n==1?' on':'');t2.className='tab'+(n==2?' on':'')}
function k(x){fetch(base+'/key',{method:'POST',body:'k='+x})}
let tm;function find(){clearTimeout(tm);tm=setTimeout(()=>{fetch(base+'/search?q='+encodeURIComponent(q.value)).then(r=>r.json()).then(list=>{res.innerHTML='';list.forEach(s=>{const d=document.createElement('div');d.className='r';
const img=document.createElement('img');if(s.poster)img.src=s.poster;const n=document.createElement('div');n.className='n';n.textContent=s.name;const sm=document.createElement('small');sm.textContent=s.kind;n.appendChild(sm);
const b=document.createElement('button');const play=s.type!='SERIES';b.textContent=play?'Play on TV':'Open on TV';b.onclick=()=>fetch(base+'/play',{method:'POST',body:'type='+s.type+'&id='+encodeURIComponent(s.id)+'&mode='+(play?'play':'open')});
d.append(img,n,b);res.appendChild(d)})})},300)}""",
    )
}
