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

    /** The screen open on the TV now (the phone pages take its theme colours). */
    fun currentActivity(): Activity? = current?.get()

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

    /**
     * A phone that joined: its name (from the phone's browser), when it joined, when it was last used,
     * and the last thing it did. [connected] while its page is open (it checks in every 30 s).
     */
    class Phone(val id: String, val name: String, val lastSeen: Long, val joined: Long, val lastAction: String?) {
        val connected get() = System.currentTimeMillis() - lastSeen < CONNECTED_MS
    }

    private const val CONNECTED_MS = 75_000L

    /** Settings → Use Phone shows the phones again when one joins, leaves or does something. */
    @Volatile var onChange: (() -> Unit)? = null
    private fun changed() { main.post { onChange?.invoke() } }

    private fun stored(): JSONObject = runCatching { JSONObject(sp().getString("phones", "{}") ?: "{}") }.getOrDefault(JSONObject())

    fun phones(): List<Phone> {
        val o = stored()
        val now = System.currentTimeMillis()
        return o.keys().asSequence().mapNotNull { id ->
            val p = o.optJSONObject(id) ?: return@mapNotNull null
            val seen = p.optLong("seen")
            Phone(id, p.optString("name", "Phone"), seen, p.optLong("joined", seen), p.optString("did").ifEmpty { null })
                .takeIf { now - it.lastSeen < REMEMBER_MS }
        }.sortedByDescending { it.lastSeen }.toList()
    }

    @Synchronized
    private fun savePhone(id: String, name: String? = null, did: String? = null) {
        val o = stored()
        val now = System.currentTimeMillis()
        val p = o.optJSONObject(id) ?: JSONObject().put("joined", now)
        val wasConnected = now - p.optLong("seen") < CONNECTED_MS
        if (name != null) p.put("name", name)
        if (did != null) p.put("did", did)
        p.put("seen", now)
        o.put(id, p)
        sp().edit().putString("phones", o.toString()).apply()
        if (!wasConnected || name != null || did != null) changed()
    }

    /** "iPhone", "Samsung phone"… from the phone's browser; "Phone" when it doesn't say. */
    private fun nameFrom(agent: String?): String {
        val ua = agent.orEmpty()
        return when {
            "iPhone" in ua -> "iPhone"
            "iPad" in ua -> "iPad"
            "Android" in ua -> {
                val model = Regex("Android [0-9.]+; ([^;)]+?)(?: Build|[;)])").find(ua)?.groupValues?.get(1)?.trim().orEmpty()
                when {
                    model.startsWith("SM-") -> "Samsung phone"
                    model.startsWith("Pixel") -> model.take(30)
                    model.isEmpty() || model == "K" -> "Android phone"
                    else -> "Android phone ($model)".take(30)
                }
            }
            "Macintosh" in ua || "Windows" in ua -> "Computer"
            else -> "Phone"
        }
    }

    @Synchronized
    fun remove(id: String) {
        val o = stored()
        o.remove(id)
        sp().edit().putString("phones", o.toString()).apply()
        Diag.log("phone", "a phone was removed")
        changed()
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
            savePhone(phoneId, name = nameFrom(fields["_ua"]), did = "joined")
            Diag.log("phone", "a phone joined")
        }
        if (phoneId == null) {
            return PhonePage.Response(PhonePage.page("Join", "<p>On the TV, open <b>Settings &rarr; Use Phone</b> and scan the code with this phone.</p>",
                symbol = "phone", heading = "Not connected"))
        }
        if (setCookie == null) savePhone(phoneId)
        return when (path) {
            "/search" -> PhonePage.Response(search(fields["q"].orEmpty()), "application/json; charset=utf-8")
            "/play" -> PhonePage.Response(if (play(phoneId, fields["type"].orEmpty(), fields["id"].orEmpty(), fields["mode"] == "play")) "ok" else "gone", "text/plain")
            "/key" -> { key(fields["k"].orEmpty()); PhonePage.Response("ok", "text/plain") }
            "/action" -> PhonePage.Response(action(fields["a"].orEmpty()), "text/plain; charset=utf-8")
            "/ping" -> PhonePage.Response("ok", "text/plain")
            else -> { warmIndex(); PhonePage.Response(panelHtml(), setCookie = setCookie) }
        }
    }

    /**
     * The remote's screen buttons (owner, 2026-11-13): Mark a problem and Menu (the quick drawer). Answers what happened, for the line under the buttons on the phone.
     */
    private fun action(a: String): String {
        val answer = java.util.concurrent.atomic.AtomicReference("The TV app isn't open.")
        val done = java.util.concurrent.CountDownLatch(1)
        main.post {
            try {
                val act = current?.get() ?: return@post
                when (a) {
                    "problem" -> if (Diag.enabled) {
                        DiagUi.mark("the phone remote", act)
                        answer.set("Problem marked on the TV. Choose its type there.")
                    } else answer.set("Problem reports are off on the TV.")
                    "menu" -> if (act is HomeActivity) { act.toggleDrawer(); answer.set("Menu on the TV") } else {
                        val t = SystemClock.uptimeMillis()
                        act.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU, 0))
                        act.dispatchKeyEvent(KeyEvent(t, t + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU, 0))
                        answer.set("Menu on the TV")
                    }
                    else -> answer.set("")
                }
            } finally { done.countDown() }
        }
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return answer.get()
    }

    // ---- Search: every title's shown name worked out once, then each letter is a quick look-up ----

    private class Entry(val item: StreamItem, val name: String, val key: String)
    private val searchTypes = listOf(ContentType.MOVIE, ContentType.SERIES, ContentType.LIVE)
    @Volatile private var index: List<Entry> = emptyList()
    @Volatile private var indexOf: List<List<StreamItem>?> = emptyList()

    /** Made again only when the playlist's lists change (2026-11-13: each letter used to work out 40,000 names again). */
    @Synchronized
    private fun index(): List<Entry> {
        val lists = searchTypes.map { Session.streamCache["${it.name}:__all__"] }
        if (lists.size == indexOf.size && lists.indices.all { lists[it] === indexOf[it] }) return index
        val built = ArrayList<Entry>(lists.sumOf { it?.size ?: 0 })
        for (list in lists) for (s in list.orEmpty()) {
            val shown = runCatching { Versions.shownName(s) }.getOrDefault(s.name)
            built.add(Entry(s, shown, if (shown == s.name) shown.lowercase() else shown.lowercase() + "|" + s.name.lowercase()))
        }
        index = built
        indexOf = lists
        return built
    }

    /** Ready before the first letter: made while the phone page opens. */
    private fun warmIndex() { Thread({ runCatching { index() } }, "phone-index").apply { isDaemon = true }.start() }

    private fun search(q: String): String {
        val text = q.trim()
        val out = JSONArray()
        if (text.length < 2) return out.toString()
        val q = text.lowercase()
        // Only the matches (600 at most) get their scores; names starting with the text come first.
        index().asSequence()
            .filter { it.key.contains(q) }
            .take(600)
            .map { e -> Triple(e, e.name.startsWith(text, ignoreCase = true), Ratings.scoreOf(e.item)) }
            .sortedWith(compareBy<Triple<Entry, Boolean, Double>> { !it.second }.thenByDescending { it.third })
            .distinctBy { "${it.first.item.type}:${it.first.name.lowercase()}" }
            .take(30)
            .forEach { (e, _, _) ->
                val s = e.item
                val kind = when (s.type) { ContentType.MOVIE -> "Movie"; ContentType.SERIES -> "Series"; ContentType.LIVE -> "Channel" }
                val imdb = Ratings.known(s.type, s.ratingId ?: s.id)?.imdb
                out.put(JSONObject().put("type", s.type.name).put("id", s.id).put("name", e.name)
                    .put("kind", listOfNotNull(kind, s.year, imdb?.let { "IMDb $it" }).joinToString(" · ")).put("poster", s.image ?: ""))
            }
        return out.toString()
    }

    private fun play(phoneId: String, typeName: String, id: String, now: Boolean): Boolean {
        val type = runCatching { ContentType.valueOf(typeName) }.getOrNull() ?: return false
        val item = Session.streamCache["${type.name}:__all__"]?.firstOrNull { it.id == id } ?: return false
        if (current?.get() == null) return false
        savePhone(phoneId, did = "${if (now) "played" else "opened"} “${Versions.shownName(item).take(40)}”")
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
        return true
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

    /**
     * The phone panel: Search and Remote, in the TV's theme (owner, 2026-11-13). Each search answer carries
     * its number, so a slow answer for "th" never covers the newer one for "the".
     */
    private fun panelHtml(): String = PhonePage.page(
        "Phone panel",
        """<style>
.tabs{display:flex;gap:6px;padding:5px;border-radius:18px;background:var(--fill);border:1px solid var(--stroke);margin:14px 0 6px}
.tab{flex:1;display:flex;gap:7px;align-items:center;justify-content:center;padding:10px;border-radius:14px;font-size:15px;color:var(--sub)}
.tab.on{background:var(--acc);color:var(--on);font-weight:600}
.sb{position:relative}.sb .ic{position:absolute;left:14px;top:50%;transform:translateY(-50%);color:var(--sub)}.sb input{padding-left:44px}
.st{font-size:13px;color:var(--sub);margin:10px 4px 2px;min-height:18px}
.r{display:flex;gap:12px;align-items:center;padding:8px;margin-top:8px;border-radius:16px;background:var(--fill);border:1px solid var(--stroke)}
.r img,.r .ph{width:46px;height:68px;object-fit:cover;border-radius:10px;background:var(--stroke);flex:none}
.r .n{flex:1;min-width:0;font-size:15px;line-height:1.3;overflow-wrap:anywhere}.r .n small{display:block;color:var(--sub);font-size:12.5px;margin-top:3px}
.r button{width:46px;height:46px;padding:0;margin:0;border-radius:50%;flex:none}.r button .ic{width:22px;height:22px}
.r button.sent{background:var(--fill);color:var(--good);border:1px solid var(--stroke)}
.pad{display:grid;grid-template-columns:repeat(3,78px);grid-template-rows:repeat(3,78px);gap:12px;justify-content:center;margin:26px 0 18px}
.k{border-radius:50%;background:var(--fill);border:1px solid var(--stroke);color:var(--text);display:flex;align-items:center;justify-content:center;user-select:none;-webkit-user-select:none;-webkit-tap-highlight-color:transparent}
.k:active{transform:scale(.94);border-color:var(--acc)}.k .ic{width:28px;height:28px}
.k.ok{background:var(--acc);color:var(--on);font-size:18px;font-weight:600;border:0}
.row{display:flex;gap:10px}.row .k{flex:1;height:56px;border-radius:18px;gap:8px;font-size:15px}.row .k .ic{width:20px;height:20px}.k.warn{color:var(--bad)}
</style>
<div class=tabs><div class='tab on' id=t1 onclick=show(1)>${PhonePage.icon("search")}Search</div><div class=tab id=t2 onclick=show(2)>${PhonePage.icon("remote")}Remote</div></div>
<div id=p1><div class=sb>${PhonePage.icon("search")}<input id=q type=search placeholder='Movie, series, channel…' autocomplete=off autocorrect=off oninput=find() enterkeyhint=search></div>
<div class=st id=st>Type 2 letters or more.</div><div id=res></div></div>
<div id=p2 style=display:none><div class=pad><span></span><div class=k onclick="k('up')"><svg class=ic viewBox='0 0 24 24' fill=none stroke=currentColor stroke-width=2 stroke-linecap=round stroke-linejoin=round><path d='M6 15l6-6 6 6'/></svg></div><span></span>
<div class=k onclick="k('left')"><svg class=ic viewBox='0 0 24 24' fill=none stroke=currentColor stroke-width=2 stroke-linecap=round stroke-linejoin=round><path d='M15 6l-6 6 6 6'/></svg></div><div class='k ok' onclick="k('ok')">OK</div><div class=k onclick="k('right')"><svg class=ic viewBox='0 0 24 24' fill=none stroke=currentColor stroke-width=2 stroke-linecap=round stroke-linejoin=round><path d='M9 6l6 6-6 6'/></svg></div>
<span></span><div class=k onclick="k('down')"><svg class=ic viewBox='0 0 24 24' fill=none stroke=currentColor stroke-width=2 stroke-linecap=round stroke-linejoin=round><path d='M6 9l6 6 6-6'/></svg></div><span></span></div>
<div class=row><div class=k onclick="k('back')">Back</div><div class=k onclick="k('home')">Home</div><div class=k onclick="k('playpause')">Play / Pause</div></div>
<div class=row style=margin-top:10px><div class=k onclick="act('menu')">${PhonePage.icon("menu")}Menu</div><div class='k warn' onclick="act('problem')">${PhonePage.icon("flag")}Mark a problem</div></div>
<div class=st id=rs style=text-align:center></div></div>""",
        """const base=location.pathname.replace(/[/]?$/,'');
function show(n){p1.style.display=n==1?'':'none';p2.style.display=n==2?'':'none';t1.className='tab'+(n==1?' on':'');t2.className='tab'+(n==2?' on':'')}
function k(x){if(navigator.vibrate)navigator.vibrate(12);fetch(base+'/key',{method:'POST',body:'k='+x})}
function act(x){if(navigator.vibrate)navigator.vibrate(12);rs.textContent='…';fetch(base+'/action',{method:'POST',body:'a='+x}).then(r=>r.text()).then(t=>{rs.textContent=t}).catch(()=>{rs.textContent='Can’t reach the TV.'})}
const PLAY=`${PhonePage.icon("play")}`,OPEN=`${PhonePage.icon("open")}`,OK=`${PhonePage.icon("ok")}`;
let tm,seq=0,ctl;
function find(){clearTimeout(tm);const text=q.value.trim();
 if(text.length<2){seq++;if(ctl)ctl.abort();res.innerHTML='';st.textContent='Type 2 letters or more.';return}
 st.textContent='Searching…';tm=setTimeout(()=>go(text),180)}
function go(text){const mine=++seq;if(ctl)ctl.abort();ctl=window.AbortController?new AbortController():null;
 fetch(base+'/search?q='+encodeURIComponent(text),ctl?{signal:ctl.signal}:{}).then(r=>r.json()).then(list=>{
  if(mine!==seq)return;res.innerHTML='';
  st.textContent=list.length?(list.length+(list.length>=30?'+':'')+' found'):'Nothing found for “'+text+'”.';
  list.forEach(s=>{const d=document.createElement('div');d.className='r';
   let img;if(s.poster){img=document.createElement('img');img.loading='lazy';img.src=s.poster;img.onerror=()=>{img.replaceWith(Object.assign(document.createElement('div'),{className:'ph'}))}}else{img=document.createElement('div');img.className='ph'}
   const n=document.createElement('div');n.className='n';n.textContent=s.name;const sm=document.createElement('small');sm.textContent=s.kind;n.appendChild(sm);
   const b=document.createElement('button');const play=s.type!='SERIES';b.innerHTML=play?PLAY:OPEN;b.setAttribute('aria-label',play?'Play on TV':'Open on TV');
   b.onclick=()=>fetch(base+'/play',{method:'POST',body:'type='+s.type+'&id='+encodeURIComponent(s.id)+'&mode='+(play?'play':'open')}).then(r=>r.text()).then(t=>{
    if(t=='ok'){b.innerHTML=OK;b.className='sent';st.textContent=(play?'Playing “':'Opened “')+s.name+'” on the TV'}else{st.textContent='The TV app isn’t open. Open Xtream TV on the TV and try again.'}});
   d.append(img,n,b);res.appendChild(d)})}).catch(e=>{if(mine===seq&&e.name!=='AbortError')st.textContent='Can’t reach the TV. Is it on the same Wi-Fi?'})}
setInterval(()=>{if(!document.hidden)fetch(base+'/ping')},30000);""",
        symbol = "phone", heading = "Use Phone",
    )
}
