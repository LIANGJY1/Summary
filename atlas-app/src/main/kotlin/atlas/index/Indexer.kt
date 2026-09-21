package atlas.index

import atlas.core.Chunk
import atlas.core.IgnoreRules
import atlas.core.ItemKind
import atlas.core.Log
import atlas.core.Md
import atlas.core.NoteFile
import atlas.core.DocMarker
import atlas.core.Tier
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * 索引器（PRD FR-A2/A3/A5/A6）：扫描 → 三档边界 → items 编目 + chunks + FTS5(trigram) + 事件日志。
 * SQLite 连接由调用方管理（应用级单连接）。
 */
class Indexer(private val conn: Connection) {

    companion object {
        fun connect(dbFile: File): Connection {
            val t0 = System.currentTimeMillis()
            dbFile.parentFile?.mkdirs()
            val c = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
            c.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("""CREATE TABLE IF NOT EXISTS items(
                    path TEXT PRIMARY KEY, title TEXT, kind TEXT, tier TEXT,
                    size INTEGER, mtime INTEGER, marker TEXT)""")
                st.execute("""CREATE TABLE IF NOT EXISTS chunks(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT, section TEXT, ord INTEGER, body TEXT)""")
                st.execute("CREATE INDEX IF NOT EXISTS idx_chunks_path ON chunks(path)")
                st.execute("""CREATE VIRTUAL TABLE IF NOT EXISTS fts USING fts5(
                    body, path UNINDEXED, section UNINDEXED, tokenize='trigram')""")
                st.execute("""CREATE TABLE IF NOT EXISTS events(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, kind TEXT, data TEXT)""")
                st.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)")
            }
            Log.i("SQLite 已连接 db=${dbFile.absolutePath} 初始化耗时=${System.currentTimeMillis() - t0}ms")
            return c
        }
    }

    @Synchronized
    fun logEvent(kind: String, data: String) {
        Log.d("event $kind ${data.take(60)}")
        conn.prepareStatement("INSERT INTO events(ts, kind, data) VALUES(?,?,?)").use {
            it.setLong(1, System.currentTimeMillis()); it.setString(2, kind); it.setString(3, data); it.executeUpdate()
        }
    }

    data class ScanStats(var chunks: Int = 0, var changedFiles: Int = 0)

    /** 增量扫描：以 items.mtime 对比；返回统计。耗时操作，放后台线程调用。 */
    @Synchronized
    fun rescan(root: File, rules: IgnoreRules, full: Boolean = false): ScanStats {
        val t0 = System.currentTimeMillis()
        val st = ScanStats()
        val known = HashMap<String, Long>()
        conn.createStatement().use { q ->
            q.executeQuery("SELECT path, mtime FROM items").use { r ->
                while (r.next()) known[r.getString(1)] = r.getLong(2)
            }
        }
        Log.i("索引扫描开始 root=${root.absolutePath} full=$full 已知条目=${known.size} 线程=${Thread.currentThread().name}")
        val seen = HashSet<String>()
        root.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            val rel = f.relativeTo(root).invariantSeparatorsPath
            if (rel.startsWith("atlas/")) return@forEach // 应用协作目录
            val isMd = Md.isMd(f)
            val size = f.length()
            val tier = rules.tierOf(rel)
            if (tier == Tier.IGNORED) return@forEach
            seen.add(rel)
            val mtime = f.lastModified()
            if (!full && known[rel] == mtime) return@forEach
            st.changedFiles++
            Log.d("索引变更 ${if (known.containsKey(rel)) "更新" else "新增"} $rel (${size}B)")
            val kind = when {
                isMd && f.name == "SKILL.md" -> ItemKind.SKILL
                isMd -> ItemKind.NOTE
                else -> ItemKind.FILE
            }
            val marker = if (isMd) detectMarker(f, rel, rules) else DocMarker.NONE
            val title = if (isMd) (Md.titleOf(f) ?: f.name) else f.name
            upsertItem(rel, title, kind.name, tier.name, size, mtime, marker.name)
            // 内容索引只对 FULL 档的 md
            deleteChunks(rel)
            if (tier == Tier.FULL && isMd && size <= 1_000_000) {
                val text = try { f.readText(Charsets.UTF_8) } catch (e: Exception) { Log.w("索引读取失败，跳过 $rel：${e.message}"); return@forEach }
                val chunks = Md.chunksOf(rel, text)
                insertChunks(chunks)
                st.chunks += chunks.size
            }
        }
        // 清理已消失文件
        val gone = known.keys.filter { it !in seen }
        gone.forEach { deleteItem(it) }
        if (gone.isNotEmpty()) Log.d("清理已消失条目 ${gone.size} 个")
        conn.createStatement().use { it.execute("INSERT INTO meta(key,value) VALUES('lastScan','${System.currentTimeMillis()}') ON CONFLICT(key) DO UPDATE SET value=excluded.value") }
        Log.i("索引扫描完成 扫过文件=${seen.size} 变更=${st.changedFiles} 新增区块=${st.chunks} 清理=${gone.size} 耗时=${System.currentTimeMillis() - t0}ms")
        return st
    }

    private fun detectMarker(f: File, relPath: String, rules: IgnoreRules): DocMarker {
        val head = try { f.bufferedReader(Charsets.UTF_8).useLines { ls -> ls.take(12).joinToString("\n") } } catch (e: Exception) { return DocMarker.NONE }
        return when {
            Regex("""type:\s*retrospective""").containsMatchIn(head) -> DocMarker.RETROSPECTIVE
            Regex("""type:\s*project-experience""").containsMatchIn(head) -> DocMarker.PROJECT_EXPERIENCE
            else -> markerByDir(relPath, rules)
        }
    }

    private fun markerByDir(relPath: String, rules: IgnoreRules): DocMarker {
        val dirs = relPath.split('/').dropLast(1)
        val retro = rules.effectiveRetroDirs()
        return when {
            dirs.any { it in retro } -> DocMarker.RETROSPECTIVE
            dirs.any { it == "project-experience" || it == "project-experiences" } -> DocMarker.PROJECT_EXPERIENCE
            else -> DocMarker.NONE
        }
    }

    private fun upsertItem(path: String, title: String, kind: String, tier: String, size: Long, mtime: Long, marker: String) {
        conn.prepareStatement("""INSERT INTO items(path,title,kind,tier,size,mtime,marker) VALUES(?,?,?,?,?,?,?)
            ON CONFLICT(path) DO UPDATE SET title=excluded.title, kind=excluded.kind, tier=excluded.tier,
            size=excluded.size, mtime=excluded.mtime, marker=excluded.marker""").use {
            it.setString(1, path); it.setString(2, title); it.setString(3, kind); it.setString(4, tier)
            it.setLong(5, size); it.setLong(6, mtime); it.setString(7, marker); it.executeUpdate()
        }
    }

    private fun deleteItem(path: String) {
        deleteChunks(path)
        conn.prepareStatement("DELETE FROM items WHERE path=?").use { it.setString(1, path); it.executeUpdate() }
    }

    private fun deleteChunks(path: String) {
        conn.prepareStatement("DELETE FROM fts WHERE path=?").use { it.setString(1, path); it.executeUpdate() }
        conn.prepareStatement("DELETE FROM chunks WHERE path=?").use { it.setString(1, path); it.executeUpdate() }
    }

    private fun insertChunks(chunks: List<Chunk>) {
        val psChunk = conn.prepareStatement("INSERT INTO chunks(path,section,body) VALUES(?,?,?)")
        val psFts = conn.prepareStatement("INSERT INTO fts(body,path,section) VALUES(?,?,?)")
        chunks.forEach { c ->
            psChunk.setString(1, c.path); psChunk.setString(2, c.section); psChunk.setString(3, c.body); psChunk.addBatch()
            psFts.setString(1, c.body); psFts.setString(2, c.path); psFts.setString(3, c.section); psFts.addBatch()
        }
        psChunk.executeBatch(); psFts.executeBatch()
        psChunk.close(); psFts.close()
    }

    data class Hit(val path: String, val section: String, val snippet: String, val rank: Double)

    /** 检索：<3 字走 LIKE 兜底，>=3 字走 trigram MATCH+bm25（PRD FR-A3） */
    @Synchronized
    fun search(query: String, limit: Int = 50): List<Hit> {
        val t0 = System.currentTimeMillis()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = if (q.length >= 3) {
            val safe = q.replace("\"", "\"\"")
            val sql = """SELECT path, section, snippet(fts, 0, '【', '】', ' … ', 14), bm25(fts)
                FROM fts WHERE fts MATCH ? ORDER BY rank LIMIT ?"""
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, "\"$safe\"")
                ps.setInt(2, limit)
                ps.executeQuery().use { r ->
                    val o = ArrayList<Hit>()
                    while (r.next()) o.add(Hit(r.getString(1), r.getString(2), r.getString(3), r.getDouble(4)))
                    o
                }
            }
        } else {
            val sql = "SELECT path, section, body FROM chunks WHERE body LIKE ? LIMIT ?"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, "%$q%")
                ps.setInt(2, limit)
                ps.executeQuery().use { r ->
                    val o = ArrayList<Hit>()
                    var i = 0
                    while (r.next()) {
                        val body = r.getString(3)
                        val idx = body.indexOf(q)
                        val start = (idx - 30).coerceAtLeast(0)
                        val snip = (if (start > 0) " … " else "") + body.substring(start, (idx + q.length + 60).coerceAtMost(body.length)) + " … "
                        o.add(Hit(r.getString(1), r.getString(2), snip.replace(q, "【$q】"), -(i++).toDouble()))
                    }
                    o
                }
            }
        }
        val ms = System.currentTimeMillis() - t0
        val line = "search q=$q 模式=${if (q.length >= 3) "FTS trigram" else "LIKE"} 命中=${out.size} 耗时=${ms}ms 线程=${Thread.currentThread().name}"
        if (ms >= 300) Log.w("SLOW $line（在 UI 线程执行时会卡界面）") else Log.d(line)
        return out
    }

    /** 上下文包素材：取 top 区块全文（FR-B2） */
    @Synchronized
    fun contextChunks(query: String, n: Int = 8): List<Chunk> {
        return search(query, n).mapNotNull { h ->
            conn.prepareStatement("SELECT body FROM chunks WHERE path=? AND section=?").use { ps ->
                ps.setString(1, h.path); ps.setString(2, h.section)
                ps.executeQuery().use { r -> if (r.next()) Chunk(h.path, h.section, r.getString(1)) else null }
            }
        }
    }

    @Synchronized
    fun itemCount(): Int = conn.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM items").use { r -> r.getInt(1) } }
    @Synchronized
    fun chunkCount(): Int = conn.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM chunks").use { r -> r.getInt(1) } }

    /** 目录树用：全部条目 */
    @Synchronized
    fun allItems(): List<NoteFile> = Log.timed("allItems() 全量条目读取", warnMs = 300, logAlways = false) {
        val out = ArrayList<NoteFile>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT path,title,kind,tier,size,mtime,marker FROM items ORDER BY path").use { r ->
                while (r.next()) out.add(
                    NoteFile(
                        relPath = r.getString(1), title = r.getString(2),
                        kind = ItemKind.valueOf(r.getString(3)), tier = Tier.valueOf(r.getString(4)),
                        sizeBytes = r.getLong(5), mtime = r.getLong(6),
                        marker = try { DocMarker.valueOf(r.getString(7)) } catch (e: Exception) { DocMarker.NONE },
                    )
                )
            }
        }
        out
    }

    /**
     * 预览读取（UI 线程同步调用）。任何文件都被当 UTF-8 文本整读——二进制大文件（PNG/CSV 等）
     * 是「点击卡死」的头号嫌疑：这里记录大小与耗时，配合看门狗即可定位。
     */
    fun readFile(relPath: String, root: File): String {
        val t0 = System.currentTimeMillis()
        val f = File(root, relPath)
        val out = try {
            f.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("readFile 失败 $relPath：${e.message}")
            "(读取失败: ${e.message})"
        }
        Log.i("readFile $relPath 文件=${f.length()}B 字符=${out.length} 耗时=${System.currentTimeMillis() - t0}ms 线程=${Thread.currentThread().name}" +
            if (out.length > 200_000) " ⚠️ 超大文本将整体进入 Markdown 渲染（卡顿高发）" else "")
        return out
    }
}
