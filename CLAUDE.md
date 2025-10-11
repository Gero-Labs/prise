# Prise Indexer - Claude Development Guide

## Project Overview

**Prise Indexer** is a high-performance Cardano blockchain indexer that tracks DEX (Decentralized Exchange) swaps, prices, and liquidity pool reserves in real-time. It processes blocks from the Cardano mainnet, extracts swap transactions from multiple DEXs (Minswap, Sundaeswap, Wingriders), calculates prices, and maintains historical pool reserve data for TVL (Total Value Locked) tracking.

**Version**: 0.4.1 (latest with UTXO caching and performance optimizations)

## Tech Stack & Architecture

### Core Technologies
- **Language**: Kotlin 1.9.25
- **Build System**: Gradle 8.14
- **Database**: PostgreSQL (for application data)
- **Blockchain Sync**: Yaci BlockSync (connects to Cardano node)
- **Dependency Injection**: Koin 3.5.6
- **Database Access**: Ktorm 3.6.0
- **Migrations**: Flyway 9.22.3
- **Concurrency**: Kotlin Coroutines
- **Event System**: Kotlin Flow with MutableSharedFlow
- **Monitoring**: Prometheus metrics (custom implementation)
- **Container**: Docker (multi-stage builds)
- **Orchestration**: Kubernetes

### External Services
- **Blockfrost API**: Fallback for historical UTXO resolution (with 93% cache hit rate reducing calls)
- **Cardano Node**: Direct connection via Yaci BlockSync for real-time blocks (relay1.gerowallet.io:6000)
- **Redis** (optional): For external event publishing

## Project Structure

```
prise/
├── indexer/                          # Main indexer application
│   ├── src/main/
│   │   ├── kotlin/tech/edgx/prise/indexer/
│   │   │   ├── PriseIndexer.kt      # Main entry point, Koin DI setup
│   │   │   ├── PriseRunner.kt       # Application runner
│   │   │   ├── config/              # Configuration management
│   │   │   │   ├── Config.kt        # Configuration data class
│   │   │   │   ├── Configurer.kt    # Loads config from properties/env
│   │   │   │   └── Constants.kt     # Configuration property names
│   │   │   ├── event/               # Event-driven architecture
│   │   │   │   ├── EventBus.kt      # Kotlin Flow event bus
│   │   │   │   ├── EventDispatcher.kt # Main event processor
│   │   │   │   ├── IndexerEvent.kt  # Event type definitions
│   │   │   │   └── EventPublisher.kt # External event publishing
│   │   │   ├── processor/           # Business logic processors
│   │   │   │   ├── SwapProcessor.kt # DEX swap extraction
│   │   │   │   ├── PriceProcessor.kt # Price calculation
│   │   │   │   └── PersistenceService.kt # Data persistence
│   │   │   ├── service/             # Core services
│   │   │   │   ├── chain/
│   │   │   │   │   └── ChainService.kt # Blockchain sync management
│   │   │   │   ├── classifier/      # DEX-specific classifiers
│   │   │   │   │   ├── DexClassifier.kt # Classifier interface
│   │   │   │   │   └── module/
│   │   │   │   │       ├── MinswapClassifier.kt
│   │   │   │   │       ├── MinswapV2Classifier.kt
│   │   │   │   │       ├── SundaeswapClassifier.kt
│   │   │   │   │       └── WingridersClassifier.kt
│   │   │   │   ├── dataprovider/   # Data provider implementations
│   │   │   │   │   ├── ChainDatabaseService.kt # Interface
│   │   │   │   │   └── module/
│   │   │   │   │       ├── blockfrost/BlockfrostService.kt
│   │   │   │   │       ├── koios/KoiosService.kt
│   │   │   │   │       ├── yacistore/YaciStoreService.kt
│   │   │   │   │       ├── carp/CarpJdbcService.kt
│   │   │   │   │       └── hybrid/HybridCachedService.kt # UTXO caching
│   │   │   │   ├── AssetService.kt  # Asset management
│   │   │   │   ├── PriceService.kt  # Price data operations
│   │   │   │   ├── PoolReserveService.kt # Pool reserve persistence
│   │   │   │   ├── TxService.kt     # Transaction management
│   │   │   │   ├── DbService.kt     # Database utilities
│   │   │   │   ├── UtxoCache.kt     # In-memory UTXO cache
│   │   │   │   └── monitoring/MonitoringService.kt # Prometheus metrics
│   │   │   ├── repository/          # Database repositories
│   │   │   │   ├── CarpRepository.kt
│   │   │   │   └── BaseCandleRepository.kt
│   │   │   ├── model/               # Data models
│   │   │   │   ├── dex/
│   │   │   │   │   ├── SwapDTO.kt
│   │   │   │   │   └── PoolReserveDTO.kt
│   │   │   │   └── prices/PriceDTO.kt
│   │   │   ├── domain/              # Domain entities
│   │   │   ├── thread/              # Background workers
│   │   │   │   ├── OutlierDetectionWorker.kt
│   │   │   │   └── MaterialisedViewRefreshWorker.kt
│   │   │   └── util/                # Utility classes
│   │   │       ├── Helpers.kt
│   │   │       └── ExternalProviderException.kt
│   │   └── resources/
│   │       ├── db/migration/        # Flyway migrations
│   │       │   ├── V1.1__init.sql
│   │       │   ├── V1.2__candle_tables.sql
│   │       │   ├── V1.3__candle_procedures.sql
│   │       │   └── V1.4__liquidity_pool_reserves.sql
│   │       └── logback.xml          # Logging configuration
│   ├── Dockerfile                   # Container build
│   ├── prise.properties            # Base configuration
│   └── build.gradle.kts            # Gradle build config
└── README.md
```

## Key Architecture Patterns

### 1. **Event-Driven Architecture**

The indexer uses a **publish-subscribe event system** built on Kotlin Flow:

```kotlin
// Event Flow:
BlockReceivedEvent
  → SwapsComputedEvent + PoolReservesComputedEvent
  → PricesCalculatedEvent
  → Signal block processed

// EventBus (EventBus.kt)
- MutableSharedFlow with extraBufferCapacity = 50
- Allows 50 events to be buffered before blocking
- Critical for high-throughput processing

// EventDispatcher (EventDispatcher.kt)
- Single coroutine collects events from EventBus
- Pattern matches on event type
- Processes events sequentially to maintain order
- Signals ChainService when block is fully processed
```

**Critical Pattern - Block Processing Signal:**
```kotlin
// MUST signal block processed for EVERY block
// Otherwise ChainService will wait indefinitely

is PoolReservesComputedEvent -> {
    // ... persist pool reserves ...
    chainService.signalBlockProcessed()  // CRITICAL!
}

is PricesCalculatedEvent -> {
    // ... persist prices ...
    chainService.signalBlockProcessed()  // CRITICAL!
}
```

### 2. **UTXO Resolution & Caching**

**The Problem:**
- Blocks contain transaction inputs as references (tx_hash + output_index)
- To extract swap data, we need the actual input UTXO data (amounts, addresses, datums)
- These UTXOs come from previous blocks (could be thousands of blocks ago)

**The Solution - Hybrid Caching Approach:**

```kotlin
// HybridCachedService (v0.4.0+)
1. Check UtxoCache first (in-memory LRU cache, 100k UTXOs)
2. If cache miss, query Blockfrost API
3. Return combined results

// UtxoCache Population (EventDispatcher)
- Cache ALL outputs from EVERY block as we process them
- Build rolling window of recent ~30,000 UTXOs
- 93% cache hit rate in production!

// Performance Impact:
Before: 100% Blockfrost calls for UTXO resolution
After:  7% Blockfrost calls (93% cache hit rate)
```

### 3. **DEX Classification System**

Each DEX has unique smart contract structures. Classifiers extract swap and pool reserve data:

```kotlin
// DexClassifier Interface
interface DexClassifier {
    fun getDexCode(): Int
    fun getDexName(): String
    fun getPoolScriptHash(): List<String>  // Script addresses for pool contracts
    fun computeSwaps(txDTO: FullyQualifiedTxDTO): List<SwapDTO>
    fun computePoolReserves(txDTO: FullyQualifiedTxDTO): List<PoolReserveDTO>
}

// Supported DEXs:
- Minswap (v1): DEX_CODE = 2
- MinswapV2: DEX_CODE = 2 (same code, different pool hashes)
- Sundaeswap: DEX_CODE = 1
- Wingriders: DEX_CODE = 3

// Pattern:
1. Filter blocks for transactions with outputs to DEX pool addresses
2. Resolve input UTXOs (via cache or Blockfrost)
3. Parse Plutus datums to extract swap details
4. Calculate amounts, direction (buy/sell), prices
5. Extract pool reserves from pool output UTXOs
```

### 4. **Database Layer**

**Schema (PostgreSQL):**

```sql
-- V1.1__init.sql: Core tables
- asset: Token metadata (policy_id, name, decimals)
- tx: Transaction hashes
- price: Historical DEX prices
- latest_prices: Most recent price for each asset pair

-- V1.2__candle_tables.sql: Price candles
- candle_fifteen: 15-minute OHLC candles
- candle_hourly: Hourly OHLC candles
- candle_daily: Daily OHLC candles
- candle_weekly: Weekly OHLC candles

-- V1.3__candle_procedures.sql: Candle calculation procedures

-- V1.4__liquidity_pool_reserves.sql: Pool reserve tracking
- pool_reserve: Historical pool reserves (pool_id, time, reserve1, reserve2)
  PRIMARY KEY (pool_id, time)
- latest_pool_reserve: Most recent reserves for each pool
  PRIMARY KEY (pool_id)
```

**Key Patterns:**

```kotlin
// Services use Ktorm for type-safe SQL
// Batch operations for performance

// PoolReserveService.batchInsertOrUpdateCombined()
- Uses PostgreSQL CTEs (WITH clauses)
- Batch inserts up to 500 pool reserves at once
- Handles duplicates via ON CONFLICT DO UPDATE
- Deduplicates by (pool_id, time) before inserting
  (multiple txs in same block can touch same pool)
```

### 5. **Configuration Management**

**Environment-based configuration:**

```kotlin
// Priority (highest to lowest):
1. Environment variables (K8s ConfigMaps/Secrets)
2. prise.properties file
3. Default values in Config.kt

// Critical Configuration:
CHAIN_DATABASE_SERVICE_MODULE=hybrid  // Use UTXO caching
FALLBACK_CHAIN_DATABASE_SERVICE_MODULE=blockfrost // Fallback for cache misses
CNODE_ADDRESS=relay1.gerowallet.io
CNODE_PORT=6000
DEX_CLASSIFIERS=Wingriders,Sundaeswap,Minswap,MinswapV2
```

## Performance Characteristics

### Current Performance (v0.4.1)
- **Block Processing**: ~30-50ms per block
- **Throughput**: ~10 blocks in 300-500ms
- **UTXO Cache Hit Rate**: 93%
- **Blockfrost API Calls**: Reduced by 93%
- **EventBus Buffer**: 50 events (prevents blocking)

### Performance Optimizations Applied

**v0.4.0: UTXO Caching**
- LRU cache for 100,000 UTXOs
- Automatic population from processed blocks
- Hybrid fallback to Blockfrost for cache misses
- **Result**: 93% cache hit rate, massive API call reduction

**v0.4.1: Logging & Fast-Path Optimizations**
- Reduced INFO logging to DEBUG for routine operations
- Early exit for blocks with no DEX transactions
- Increased EventBus buffer from 10 to 50
- **Result**: Cleaner logs, ~20% faster processing

**v0.3.3-0.3.5: Event System Fixes**
- Fixed EventBus buffer capacity issue (was dropping events)
- Added retry logic for Blockfrost IOExceptions
- Fixed duplicate pool reserve constraint violations
- **Result**: Stable, error-free operation

## Common Development Tasks

### 1. **Adding a New DEX**

```kotlin
// Step 1: Create classifier (src/service/classifier/module/)
object NewDexClassifier : DexClassifier {
    val DEX_CODE = 4  // Next available code
    val DEX_NAME = "NewDex"
    val POOL_SCRIPT_HASHES = listOf("script_hash_1", "script_hash_2")

    override fun computeSwaps(txDTO: FullyQualifiedTxDTO): List<SwapDTO> {
        // Parse transaction, extract swap data
        // Return list of SwapDTO
    }

    override fun computePoolReserves(txDTO: FullyQualifiedTxDTO): List<PoolReserveDTO> {
        // Extract pool reserves from pool output UTXOs
        // Return list of PoolReserveDTO
    }
}

// Step 2: Register in PriseIndexer.kt
single(named("dexClassifiers")) {
    listOf(
        WingridersClassifier,
        SundaeswapClassifier,
        MinswapClassifier,
        MinswapV2Classifier,
        NewDexClassifier  // Add here
    )
}

// Step 3: Update environment variable
DEX_CLASSIFIERS=Wingriders,Sundaeswap,Minswap,MinswapV2,NewDex
```

### 2. **Adding New Event Types**

```kotlin
// Step 1: Define event in IndexerEvent.kt
data class NewEvent(val data: String) : IndexerEvent()

// Step 2: Handle in EventDispatcher.kt
when (event) {
    is NewEvent -> {
        // Process event
        log.info("Processing NewEvent: {}", event.data)
        // ... your logic ...
    }
    // ... other events ...
}

// Step 3: Publish event from processor
eventBus.publish(NewEvent("data"))
```

### 3. **Database Migrations**

```sql
-- Create new migration: src/main/resources/db/migration/V1.5__your_migration.sql
-- Flyway automatically applies on startup

-- Example: Add new index
CREATE INDEX IF NOT EXISTS idx_pool_reserve_provider
ON pool_reserve (provider);

-- Example: Add new column
ALTER TABLE pool_reserve
ADD COLUMN IF NOT EXISTS fee_amount DECIMAL(38,0);
```

### 4. **Adding New Services**

```kotlin
// Step 1: Create service in src/service/
class NewService : KoinComponent {
    private val log = LoggerFactory.getLogger(javaClass)
    private val database: Database by inject(named("appDatabase"))

    fun doSomething(): Result {
        // Your logic
    }
}

// Step 2: Register in PriseIndexer.kt
val priseModules = module {
    single { NewService() }
    // ... other services ...
}

// Step 3: Inject where needed
class SomeClass : KoinComponent {
    private val newService: NewService by inject()
}
```

### 5. **Debugging Block Processing**

```kotlin
// Enable DEBUG logging in logback.xml
<logger name="tech.edgx.prise.indexer.event" level="DEBUG"/>
<logger name="tech.edgx.prise.indexer.processor" level="DEBUG"/>

// Key logs to watch:
- "EventDispatcher: Received block" - Block arrival
- "SwapProcessor.processBlock: qualified X transactions" - DEX tx found
- "Found X swaps" - Swaps extracted
- "Found X pool reserves" - Pool reserves extracted
- "Persisted X pool reserves" - Database write
- "UTXO Cache Stats: hit_rate=X%" - Cache performance
- "Processed 10 Block(s)" - Progress indicator

// Check if blocks are being processed:
kubectl logs deployment/prise-indexer --tail=100 | grep "Processed 10 Block"

// Check UTXO cache performance:
kubectl logs deployment/prise-indexer --tail=1000 | grep "Cache Stats"
```

## Deployment & Operations

### Docker Build

```bash
# Build JAR
./gradlew :indexer:clean :indexer:build -x test

# Build Docker image
cd indexer
docker build --platform linux/amd64 -t edridudi/gero:prise-indexer-vX.X.X .
docker push edridudi/gero:prise-indexer-vX.X.X
```

### Kubernetes Deployment

```bash
# Update deployment
kubectl set image deployment/prise-indexer indexer=edridudi/gero:prise-indexer-vX.X.X

# Check rollout status
kubectl rollout status deployment/prise-indexer --timeout=60s

# View logs
kubectl logs deployment/prise-indexer --tail=100 -f

# Update ConfigMap
kubectl patch configmap prise-config --type merge -p '{"data":{"KEY":"VALUE"}}'

# Restart to apply ConfigMap changes
kubectl rollout restart deployment/prise-indexer
```

### Configuration (Kubernetes)

```yaml
# ConfigMap (prise-config)
CHAIN_DATABASE_SERVICE_MODULE: hybrid
FALLBACK_CHAIN_DATABASE_SERVICE_MODULE: blockfrost
CNODE_ADDRESS: relay1.gerowallet.io
CNODE_PORT: "6000"
DEX_CLASSIFIERS: Wingriders,Sundaeswap,Minswap,MinswapV2
RUN_MODE: livesync

# Secret (prise-secrets)
BLOCKFROST_DATASOURCE_APIKEY: <api-key>
POSTGRES_PASSWORD: <password>
```

### Monitoring

```bash
# Prometheus metrics endpoint
http://prise-indexer:9108/metrics

# Key metrics:
- chain_sync_slot: Current sync progress
- pool_reserve_persist_failed: Pool reserve errors
- price_publish_failed: Price publishing errors
- event_processing_failed: Event processing errors
```

## Troubleshooting

### Issue: Blocks Not Processing

**Symptoms:** No "Processed 10 Block(s)" logs, timeout errors

**Causes:**
1. **EventBus buffer full**: Events being dropped
   - Fix: Increase `extraBufferCapacity` in EventBus.kt
2. **Missing `signalBlockProcessed()` call**: ChainService waiting forever
   - Fix: Ensure EVERY event path calls `chainService.signalBlockProcessed()`
3. **Cardano node unreachable**: Connection issues
   - Check: `CNODE_ADDRESS` and `CNODE_PORT` configuration
   - Test: `telnet relay1.gerowallet.io 6000`

### Issue: Duplicate Pool Reserve Errors

**Symptoms:** `ON CONFLICT DO UPDATE command cannot affect row a second time`

**Cause:** Multiple transactions in same block touch same pool

**Fix:** Deduplicate by (pool_id, time) before inserting (v0.3.4+)

```kotlin
// PoolReserveService.kt
val deduplicatedReserves = processedReserves
    .groupBy { it.poolId to it.time }
    .map { it.value.last() }  // Keep last occurrence (final state)
```

### Issue: Blockfrost GOAWAY Errors

**Symptoms:** `IOException: GOAWAY received`

**Cause:** HTTP/2 connection closed by Blockfrost server

**Fix:** Retry logic with exponential backoff (v0.3.5+)

```kotlin
// BlockfrostService.kt
catch (e: java.io.IOException) {
    log.warn("Blockfrost IOException (attempt $attempts): ${e.message}")
    if (attempts >= MAX_UTXO_ATTEMPTS) throw ExternalProviderException(...)
    attempts++
    delay(TimeUnit.SECONDS.toMillis(5))
    response = null
}
```

### Issue: Low UTXO Cache Hit Rate

**Symptoms:** Cache hit rate < 80%, many Blockfrost calls

**Causes:**
1. Cache size too small: Increase `maxSize` in UtxoCache
2. Old UTXOs being referenced: Normal for historical sync
3. Cache not populating: Check EventDispatcher caching logic

**Fix:**
```kotlin
// PriseIndexer.kt
single { tech.edgx.prise.indexer.service.UtxoCache(maxSize = 200000) }  // Increase
```

### Issue: Slow Sync Speed

**Optimizations:**
1. ✅ UTXO caching (93% hit rate)
2. ✅ Reduced logging (v0.4.1)
3. ✅ Fast-path for empty blocks (v0.4.1)
4. ✅ Increased EventBus buffer (v0.4.1)
5. ⏳ Parallel block processing (not implemented - complex)
6. ⏳ Batch database writes (partially implemented)

**Current Performance:** ~30-50ms per block is excellent for this architecture

## Critical Code Sections

### EventDispatcher.kt (Lines 45-80)

**Why Critical:** Main event processing loop, must handle all events correctly

**Key Requirements:**
- MUST call `chainService.signalBlockProcessed()` for every block
- MUST NOT throw unhandled exceptions (blocks sync forever)
- MUST populate UTXO cache from every block

### EventBus.kt (Line 7)

**Why Critical:** Buffer capacity determines max concurrent events

```kotlin
private val _events = MutableSharedFlow<IndexerEvent>(replay = 0, extraBufferCapacity = 50)
```

If buffer is too small: Events get dropped, blocks never complete
If buffer is too large: Memory usage increases (minimal impact)

### PoolReserveService.kt (Lines 101-104)

**Why Critical:** Deduplication prevents constraint violations

```kotlin
val deduplicatedReserves = processedReserves
    .groupBy { it.poolId to it.time }
    .map { it.value.last() }
```

Without deduplication: Duplicate key errors crash block processing

### HybridCachedService.kt (Lines 32-93)

**Why Critical:** UTXO resolution with caching - 93% performance improvement

**Pattern:**
1. Check cache first (fast)
2. Query Blockfrost for misses (slow)
3. Combine results maintaining order
4. Log performance metrics every 100 queries

## Version History

### v0.4.1 (Current - 2025-01-11)
- Reduced logging overhead (INFO → DEBUG for routine operations)
- Fast-path for empty blocks
- Increased EventBus buffer to 50 events
- **Result:** ~20% faster processing, cleaner logs

### v0.4.0 (2025-01-11)
- Implemented UTXO caching (UtxoCache + HybridCachedService)
- 93% cache hit rate achieved
- Massive reduction in Blockfrost API dependency
- **Result:** 93% fewer external API calls

### v0.3.5 (2025-01-11)
- Added IOException retry logic for Blockfrost
- Handles GOAWAY and connection errors gracefully
- **Result:** Resilient to transient network issues

### v0.3.4 (2025-01-11)
- Fixed duplicate pool reserve constraint violations
- Deduplication by (pool_id, time)
- **Result:** No more database constraint errors

### v0.3.3 (2025-01-11)
- Fixed EventBus buffer capacity (1 → 10)
- Enabled PoolReservesComputedEvent processing
- **Result:** Events flow correctly through system

### v0.3.0-0.3.2 (2025-01-11)
- Debugging block processing issues
- Added comprehensive logging
- Identified event signal issues

### v0.2.6-0.2.9 (2025-01-11)
- Initial pool reserve functionality
- Dockerfile and configuration fixes
- DEX classifier setup

## Best Practices

### 1. **Event Processing**
- Always call `chainService.signalBlockProcessed()`
- Use try-catch in event handlers
- Log errors with context

### 2. **Database Operations**
- Use batch operations (500 items per batch)
- Deduplicate before inserting
- Use CTEs for complex operations

### 3. **Performance**
- Cache frequently accessed data
- Use DEBUG logging for routine operations
- Fast-path for common cases (empty blocks)

### 4. **Error Handling**
- Retry transient network errors
- Log errors with full context
- Increment monitoring counters

### 5. **Testing**
```bash
# Always test locally first
./gradlew :indexer:build

# Check for compilation errors
./gradlew :indexer:compileKotlin

# Run specific tests
./gradlew :indexer:test --tests "SwapProcessorTest"

# Deploy to dev environment first
kubectl set image deployment/prise-indexer indexer=...:vX.X.X -n dev
```

## External Dependencies

### Cardano Node
- **relay1.gerowallet.io:6000** (mainnet)
- Provides real-time blocks via Yaci BlockSync
- Critical: Must be reachable and synced

### Blockfrost API
- **https://cardano-mainnet.blockfrost.io/api/v0**
- Used for historical UTXO resolution (cache misses)
- Rate limits: Check Blockfrost documentation
- Fallback: Can switch to Koios or self-hosted Yaci Store

### PostgreSQL Database
- Application data, prices, pool reserves
- Connection pool: 20 max connections
- Migrations: Automatic via Flyway

## Security Considerations

1. **API Keys**: Store in Kubernetes Secrets, never in code
2. **Database Credentials**: Environment variables only
3. **Network**: Internal services only (no public exposure)
4. **Logging**: Never log sensitive data (API keys, credentials)

## Future Enhancements

### Potential Optimizations
1. **Parallel Block Processing**: Process multiple blocks concurrently (complex)
2. **Persistent UTXO Cache**: Store cache in Redis (survives restarts)
3. **Smart Cache Eviction**: Keep frequently accessed UTXOs longer
4. **Database Sharding**: Split data across multiple databases

### Potential Features
1. **Order Book DEX Support**: Track limit orders from Genius Yield, Axo
2. **More DEXs**: MuesliSwap, SundaeSwap V3, etc.
3. **Real-time Alerts**: Price movement notifications
4. **GraphQL API**: Query historical data
5. **WebSocket Streaming**: Real-time price feeds

## Appendix: Key Libraries

### Blockchain
- `cardano-yaci-core`: 0.6.1 - Block sync and Cardano primitives
- `cardano-yaci-helper`: 0.6.1 - Utilities for Yaci

### Database
- `ktorm-core`: 3.6.0 - Type-safe SQL DSL
- `ktorm-support-postgresql`: 3.6.0 - PostgreSQL support
- `postgresql`: 42.7.3 - PostgreSQL JDBC driver
- `flyway-core`: 9.22.3 - Database migrations
- `HikariCP`: 5.1.0 - Connection pooling

### Dependency Injection
- `koin-core`: 3.5.6 - Lightweight DI framework

### Coroutines
- `kotlinx-coroutines-core`: 1.9.0 - Coroutine primitives
- `kotlinx-coroutines-jdk8`: 1.9.0 - JDK8 integration

### HTTP
- `java.net.http.HttpClient`: Built-in - HTTP/2 client for Blockfrost

### Serialization
- `gson`: 2.11.0 - JSON parsing

### Logging
- `logback-classic`: 1.5.6 - Logging implementation
- `slf4j-api`: 2.0.13 - Logging API

### Monitoring
- Custom Prometheus metrics implementation

---

**Last Updated**: 2025-01-11 (v0.4.1 with UTXO caching and performance optimizations)

**Maintainer**: Development team at Gero Labs

**Documentation**: This guide is maintained alongside the codebase. Update when making significant architectural changes.