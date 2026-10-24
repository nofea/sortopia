package com.sortopia

import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.sortopia.game.Progress
import com.sortopia.gen.GeneratedLevel
import com.sortopia.gen.LevelGenerator
import com.sortopia.model.LevelId
import com.sortopia.platform.Platform
import com.sortopia.platform.UiProbe
import com.sortopia.save.LoadOutcome
import com.sortopia.save.SaveData
import com.sortopia.save.SaveStore
import com.sortopia.save.Settings
import com.sortopia.ui.Assets
import com.sortopia.ui.Audio
import com.sortopia.ui.screens.SplashScreen
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class SortopiaGame(
    val platform: Platform,
) : Game() {
    lateinit var batch: SpriteBatch
        private set
    lateinit var shapes: ShapeRenderer
        private set
    lateinit var assets: Assets
        private set
    lateinit var audio: Audio
        private set
    lateinit var progress: Progress
        private set

    /** How the save was loaded; a non-OK outcome is shown once as a notice (SPEC §16). */
    var loadOutcome: LoadOutcome = LoadOutcome.NEW

    private lateinit var store: SaveStore

    val probe: UiProbe get() = UiProbe.current

    private val cache = LinkedHashMap<String, GeneratedLevel>(CACHE_SIZE, LOAD_FACTOR, true)
    private val pending = HashMap<String, LevelJob>()
    private val levelQueue = PriorityBlockingQueue<Runnable>()
    private val levelExecutor: ExecutorService =
        ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, levelQueue, daemonThreads("level-gen"))
    private var jobCount = 0L
    val workExecutor: ExecutorService = Executors.newSingleThreadExecutor(daemonThreads("solver"))

    val settings: Settings get() = progress.data.settings

    override fun create() {
        Gdx.input.setCatchKey(Input.Keys.BACK, true)
        batch = SpriteBatch()
        shapes = ShapeRenderer()
        assets = Assets(platform.fontScale)
        store = SaveStore(platform.saveDir, platform.macProvider)
        val loaded = store.load()
        loadOutcome = loaded.outcome
        progress = Progress(loaded.data)
        if (loaded.outcome == LoadOutcome.RESTORED_BACKUP || loaded.outcome == LoadOutcome.RESET) save()
        audio = Audio(platform) { progress.data.settings }
        setScreen(SplashScreen(this))
    }

    /** Switches screens and disposes the previous one. */
    fun go(next: Screen) {
        val old = screen
        setScreen(next)
        old?.dispose()
    }

    fun save() {
        try {
            store.save(progress.snapshot())
        } catch (e: IOException) {
            Gdx.app.error(TAG, "Saving failed: ${e.javaClass.simpleName}")
        }
    }

    fun updateSave(transform: (SaveData) -> SaveData) {
        progress.update(transform)
        save()
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        updateSave { it.copy(settings = transform(it.settings)) }
        audio.refreshMusic()
    }

    /**
     * Delivers the generated level on the render thread; generation runs in the background.
     * An [urgent] request (a level the player opened) is generated before queued prefetches,
     * which on a slow device can take tens of seconds each.
     */
    fun level(
        id: LevelId,
        urgent: Boolean = true,
        onReady: (GeneratedLevel) -> Unit,
    ) {
        cache[id.key]?.let {
            onReady(it)
            return
        }
        val job = pending[id.key]
        when {
            job == null -> enqueue(LevelJob(id, urgent, jobCount++), onReady)
            // Still queued as a prefetch: requeue it ahead of the other prefetches.
            urgent && !job.urgent && levelQueue.remove(job) -> enqueue(LevelJob(id, true, job.order), onReady, job.waiting)
            else -> job.waiting += onReady
        }
    }

    private fun enqueue(
        job: LevelJob,
        onReady: (GeneratedLevel) -> Unit,
        earlier: List<(GeneratedLevel) -> Unit> = emptyList(),
    ) {
        job.waiting += earlier
        job.waiting += onReady
        pending[job.id.key] = job
        levelExecutor.execute(job)
    }

    /** Generates [id] ahead of time so the next level opens instantly. */
    fun prefetch(id: LevelId?) {
        if (id != null && id.key !in cache && id.key !in pending) level(id, urgent = false) {}
    }

    /** Prefetches [ids] and calls [onDone] on the render thread once all of them are generated. */
    fun prefetchAll(
        ids: List<LevelId>,
        onDone: () -> Unit,
    ) {
        val distinct = ids.distinctBy { it.key }
        if (distinct.isEmpty()) {
            onDone()
            return
        }
        var left = distinct.size
        for (id in distinct) {
            level(id, urgent = false) { if (--left == 0) onDone() }
        }
    }

    override fun pause() {
        super.pause()
        audio.pauseMusic()
        save()
    }

    override fun resume() {
        super.resume()
        audio.refreshMusic()
    }

    override fun dispose() {
        screen?.dispose()
        save()
        levelExecutor.shutdownNow()
        workExecutor.shutdownNow()
        audio.dispose()
        assets.dispose()
        shapes.dispose()
        batch.dispose()
    }

    companion object {
        private const val TAG = "Sortopia"
        private const val CACHE_SIZE = 6
        private const val LOAD_FACTOR = 0.75f

        private fun daemonThreads(name: String) =
            ThreadFactory { r ->
                Thread(r, name).apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1
                }
            }
    }

    /**
     * One level to generate. The queue runs urgent jobs first, then in request [order]; [waiting]
     * is only touched on the render thread.
     */
    private inner class LevelJob(
        val id: LevelId,
        val urgent: Boolean,
        val order: Long,
    ) : Runnable,
        Comparable<LevelJob> {
        val waiting = mutableListOf<(GeneratedLevel) -> Unit>()

        override fun compareTo(other: LevelJob) = compareValuesBy(this, other, { !it.urgent }, { it.order })

        override fun run() {
            val level = LevelGenerator().generate(id)
            Gdx.app.postRunnable {
                cache[id.key] = level
                while (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
                if (pending[id.key] === this) pending.remove(id.key)
                waiting.forEach { it(level) }
            }
        }
    }
}
