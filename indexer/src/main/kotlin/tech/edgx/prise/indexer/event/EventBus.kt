package tech.edgx.prise.indexer.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.slf4j.LoggerFactory
import tech.edgx.prise.indexer.config.Config

/**
 * Event bus for publishing and subscribing to indexer events.
 *
 * **Buffer Configuration**:
 * - Buffer size is configurable via EVENT_BUS_BUFFER_SIZE environment variable (default: 50)
 * - Buffer allows events to queue when processing is slower than emission
 * - If buffer fills, emit() suspends until space is available
 * - Size should be tuned based on block processing rate and event volume
 *
 * **Memory Impact**: Each buffered event is ~1-10KB, so 50 events = ~50-500KB
 */
class EventBus(config: Config) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val bufferSize = config.eventBusBufferSize

    init {
        log.info("EventBus initialized with buffer size: $bufferSize")
    }

    private val _events = MutableSharedFlow<IndexerEvent>(replay = 0, extraBufferCapacity = bufferSize)
    val events = _events.asSharedFlow()

    suspend fun publish(event: IndexerEvent) {
        _events.emit(event)
    }
}