package tech.edgx.prise.indexer.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.parameter.parametersOf
import org.slf4j.LoggerFactory
import tech.edgx.prise.indexer.config.Config
import tech.edgx.prise.indexer.model.prices.PriceDTO
import tech.edgx.prise.indexer.processor.PersistenceService
import tech.edgx.prise.indexer.processor.PriceProcessor
import tech.edgx.prise.indexer.processor.SwapProcessor
import tech.edgx.prise.indexer.service.DbService
import tech.edgx.prise.indexer.service.PoolReserveService
import tech.edgx.prise.indexer.service.chain.ChainService
import tech.edgx.prise.indexer.service.monitoring.MonitoringService
import tech.edgx.prise.indexer.util.Helpers

class EventDispatcher(private val config: Config) : KoinComponent {
    private val log = LoggerFactory.getLogger(this::class.java)
    private val eventBus: EventBus by inject { parametersOf(config) }
    private val swapProcessor: SwapProcessor by inject { parametersOf(config) }
    private val priceProcessor: PriceProcessor by inject { parametersOf(config) }
    private val poolReserveService: PoolReserveService by inject()
    private val dbService: DbService by inject()
    private val persistenceService: PersistenceService by inject { parametersOf(config) }
    private val chainService: ChainService by inject { parametersOf(config) }
    private val monitoringService: MonitoringService by inject { parametersOf(config.metricsServerPort) }
    private val utxoCache: tech.edgx.prise.indexer.service.UtxoCache by inject()

    private val eventPublisher: EventPublisher by inject { parametersOf(config) }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var eventCollectionJob: Job? = null

    fun start() {
        eventCollectionJob = scope.launch {
            eventBus.events.collect { event ->
                try {
                    when (event) {
                        is BlockReceivedEvent -> {
                            log.debug("EventDispatcher: Received block {}, slot={}", event.block.header.headerBody.blockNumber, event.block.header.headerBody.slot)

                            // Cache UTXOs from this block for future use
                            // CRITICAL: Cache failures degrade performance over time as hit rate drops
                            event.block.transactionBodies.forEach { txBody ->
                                try {
                                    utxoCache.addOutputs(txBody.txHash, txBody.outputs)
                                } catch (e: Exception) {
                                    log.error("Failed to cache UTXOs for tx ${txBody.txHash}: ${e.message}", e)
                                    monitoringService.incrementCounter("utxo_cache_add_failed")
                                    // Continue processing - cache failure shouldn't stop block processing
                                }
                            }

                            val (swapsEvent, poolReservesEvent) = swapProcessor.processBlock(event.block)
                            log.debug("EventDispatcher: Processed block, publishing events")
                            eventBus.publish(swapsEvent)
                            eventBus.publish(poolReservesEvent)
                        }
                        is SwapsComputedEvent -> {
                            log.debug("Processing SwapsComputedEvent with {} swaps", event.swaps.size)
                            val pricesEvent = priceProcessor.processSwaps(event.swaps, event.blockSlot)
                            eventBus.publish(pricesEvent)
                        }
                        is PoolReservesComputedEvent -> {
                            if (event.poolReserves.isNotEmpty()) {
                                try {
                                    poolReserveService.batchInsertOrUpdateCombined(event.poolReserves)
                                    log.info("Persisted {} pool reserves", event.poolReserves.size)
                                } catch (e: Exception) {
                                    log.error("Failed to persist pool reserves", e)
                                    monitoringService.incrementCounter("pool_reserve_persist_failed")
                                }
                            }
                            // Signal block processed only if this is the final event (no swaps to process)
                            if (event.isFinalBlockEvent) {
                                chainService.signalBlockProcessed()
                            }
                        }
                        is PricesCalculatedEvent -> {
                            log.debug("Processing PricesCalculatedEvent with {} prices", event.prices.size)
                            if (event.prices.isNotEmpty()) {
                                persistenceService.persistPricesCombined(event.prices)
                                if (chainService.getIsSynced()) {
                                    // Update all views immediately
                                    val startTime = System.currentTimeMillis()
                                    persistenceService.refreshViews(event.prices)
                                    val duration = System.currentTimeMillis() - startTime
                                    log.info("Refreshed {} views due to {} new price event(s) in {}ms", config.refreshableViews.size, event.prices.size, duration)
                                }
                            }
                            // Publish each Price for external event consumption
                            if (config.eventPublishingEnabled == true) {
                                event.prices.forEach { price ->
                                    try {
                                        val priceDTO = PriceDTO(
                                            asset_id = price.asset_id,
                                            quote_asset_id = price.quote_asset_id,
                                            provider = price.provider,
                                            time = price.time,
                                            tx_id = price.tx_id,
                                            tx_swap_idx = price.tx_swap_idx,
                                            price = price.price,
                                            amount1 = price.amount1,
                                            amount2 = price.amount2,
                                            operation = price.operation
                                        )
                                        eventPublisher.publishPriceEvent(priceDTO)
                                        log.debug("Published price event: {}, {}, {}", priceDTO.time, priceDTO.tx_id, priceDTO.tx_swap_idx)
                                    } catch (e: Exception) {
                                        log.error("Failed to publish price event for ${price.time}", e)
                                        monitoringService.incrementCounter("price_publish_failed")
                                    }
                                }
                            }
                            chainService.signalBlockProcessed()
                        }
                        is RollbackEvent -> {
                            log.debug("Processing RollbackEvent to point {}", event.point)
                            val rollbackPointTime = event.point.slot - Helpers.slotConversionOffset
                            val syncPointTime = dbService.getSyncPointTime()
                            val reInitialisationTime = syncPointTime?.let { minOf(it, rollbackPointTime) } ?: rollbackPointTime
                            log.debug("Reinitialisation time: {}", reInitialisationTime)
                            val rollbackInitialisationState = chainService.determineInitialisationState(reInitialisationTime)
                            log.debug("On rollback, re-initialisation state: $rollbackInitialisationState")
                            //chainService.initialised = false
                            chainService.restartBlockSync(rollbackInitialisationState.chainStartPoint, chainService.blockChainDataListener)
                            chainService.signalRollbackProcessed()
                        }
                    }
                } catch (e: Exception) {
                    val errorContext = when (event) {
                        is BlockReceivedEvent -> "block=${event.block.header.headerBody.blockNumber}, slot=${event.block.header.headerBody.slot}"
                        is SwapsComputedEvent -> "slot=${event.blockSlot}, swaps=${event.swaps.size}"
                        is PoolReservesComputedEvent -> "slot=${event.blockSlot}, reserves=${event.poolReserves.size}"
                        is PricesCalculatedEvent -> "slot=${event.blockSlot}, prices=${event.prices.size}"
                        is RollbackEvent -> "point=${event.point.slot}"
                    }
                    log.error("Error processing event: $errorContext - ${e.message}", e)
                    monitoringService.incrementCounter("event_processing_failed")

                    // Signal appropriate completion based on event type
                    // IMPORTANT: Only signal completion for events that actually complete the block processing cycle
                    // BlockReceivedEvent errors should NOT signal completion as the block was never fully processed
                    when (event) {
                        is RollbackEvent -> chainService.signalRollbackProcessed()
                        is BlockReceivedEvent -> {
                            // DO NOT signal block processed - block processing failed and needs retry
                            log.error("Block processing failed, ChainService will retry or handle error")
                        }
                        is PoolReservesComputedEvent -> {
                            // Only signal if this was the final event for this block
                            if (event.isFinalBlockEvent) {
                                chainService.signalBlockProcessed()
                            }
                        }
                        is PricesCalculatedEvent -> {
                            // PricesCalculatedEvent always signals block processed
                            chainService.signalBlockProcessed()
                        }
                        is SwapsComputedEvent -> {
                            // SwapsComputedEvent doesn't directly signal - it triggers PricesCalculatedEvent
                            // No signal needed here
                        }
                    }
                }
            }
        }
        log.info("Event dispatcher started")
    }

    fun stop() {
        log.info("Stopping event dispatcher")
        eventCollectionJob?.cancel()
        scope.cancel()
        log.info("Event dispatcher stopped")
    }
}