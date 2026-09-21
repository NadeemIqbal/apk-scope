package com.nadeem.apkscope.ui.screens.workmonitor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nadeem.apkscope.core.database.WorkEvidenceDatabaseProvider
import com.nadeem.apkscope.domain.monitor.LiveMonitorAggregator
import com.nadeem.apkscope.poc.apkrepack.FridaTrafficMonitor
import com.nadeem.apkscope.sandbox.SandboxVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

enum class MonitorFilter { ALL, DNS, TCP, BLOCKED, WARNINGS }

data class WorkLiveMonitorUiState(
 val packageName: String = "",
 val durationLabel: String = "00:00:00",
 val connectionCount: Int = 0,
 val domainCount: Int = 0,
 val uploadedBytes: Long = 0L,
 val downloadedBytes: Long = 0L,
 val blockedCount: Int = 0,
 val feed: List<LiveMonitorAggregator.FeedEntry> = emptyList(),
 val selectedFilter: MonitorFilter = MonitorFilter.ALL,
)

/**
 * Checkpoint 5, item 11/12/15/16: the Work-profile-side Live Monitor's real data source — every
 * field here is read from [com.nadeem.apkscope.core.database.WorkNetworkObservationDao], never mock/
 * preview data. Metrics come from the DAO's own `SUM`/`COUNT` queries over the *whole* session
 * (item 15/16 — a real total regardless of session size, not a Kotlin-side fold over every row);
 * only the event feed itself is windowed to [FEED_LIMIT] most-recent rows, matching
 * `observeLatestForSession`'s own bound (item 16's "no unlimited event list in Compose memory").
 */
class WorkLiveMonitorViewModel(application: Application, private val sessionId: String, packageName: String) : AndroidViewModel(application) {
 private val dao = WorkEvidenceDatabaseProvider.get(application).workNetworkObservationDao()
 private val _uiState = MutableStateFlow(WorkLiveMonitorUiState(packageName = packageName))
 val uiState: StateFlow<WorkLiveMonitorUiState> = _uiState.asStateFlow()

 init {
  viewModelScope.launch {
   combine(
    dao.observeConnectionCount(sessionId),
    dao.observeDomainCount(sessionId),
    dao.observeUploadedBytes(sessionId),
    dao.observeDownloadedBytes(sessionId),
    dao.observeBlockedCount(sessionId),
    dao.observeLatestForSession(sessionId, FEED_LIMIT),
   ) { values ->
    @Suppress("UNCHECKED_CAST")
    Metrics(
     connectionCount = values[0] as Int, domainCount = values[1] as Int,
     uploadedBytes = values[2] as Long, downloadedBytes = values[3] as Long,
     blockedCount = values[4] as Int,
     feed = LiveMonitorAggregator.feed(values[5] as List<com.nadeem.apkscope.core.database.WorkNetworkObservationEntity>),
    )
   }.collect { m ->
    _uiState.value = _uiState.value.copy(
     connectionCount = m.connectionCount, domainCount = m.domainCount,
     uploadedBytes = m.uploadedBytes, downloadedBytes = m.downloadedBytes,
     blockedCount = m.blockedCount, feed = m.feed,
    )
   }
  }
  viewModelScope.launch {
   FridaTrafficMonitor.shared.start(getApplication<Application>(), sessionId, packageName)
  }
  viewModelScope.launch {
   // Real elapsed time, ticked locally from the VPN service's own recorded session start — not a
   // fake progress animation (matches LiveMonitorViewModel's identical established idiom).
   while (true) {
    SandboxVpnService.activeSessionStartedAt?.let { updateDuration(it) }
    delay(1000)
   }
  }
 }

 private fun updateDuration(startedAt: Instant) {
  val elapsed = Duration.between(startedAt, Instant.now()).coerceAtLeast(Duration.ZERO)
  val h = elapsed.toHours(); val m = elapsed.toMinutes() % 60; val s = elapsed.seconds % 60
  _uiState.value = _uiState.value.copy(durationLabel = "%02d:%02d:%02d".format(h, m, s))
 }

 fun selectFilter(filter: MonitorFilter) { _uiState.value = _uiState.value.copy(selectedFilter = filter) }

 /** Pure — directly unit-testable independent of the Flow/DB wiring above. */
 fun filteredFeed(state: WorkLiveMonitorUiState): List<LiveMonitorAggregator.FeedEntry> = when (state.selectedFilter) {
  MonitorFilter.ALL -> state.feed
  MonitorFilter.DNS -> state.feed.filter { it.category == LiveMonitorAggregator.FeedCategory.DNS }
  MonitorFilter.TCP -> state.feed.filter { it.category == LiveMonitorAggregator.FeedCategory.TCP }
  MonitorFilter.BLOCKED -> state.feed.filter { it.category == LiveMonitorAggregator.FeedCategory.BLOCKED }
  MonitorFilter.WARNINGS -> state.feed.filter { it.category == LiveMonitorAggregator.FeedCategory.WARNING || it.suspicious }
 }

 private data class Metrics(
  val connectionCount: Int, val domainCount: Int, val uploadedBytes: Long, val downloadedBytes: Long,
  val blockedCount: Int, val feed: List<LiveMonitorAggregator.FeedEntry>,
 )

 companion object { private const val FEED_LIMIT = 500 }
}
