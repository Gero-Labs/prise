-- Liquidity Pool Reserves Table
-- Tracks the reserves of liquidity pools over time to calculate TVL
CREATE TABLE IF NOT EXISTS pool_reserve(
    pool_id VARCHAR(255) NOT NULL,  -- Unique identifier for the pool (asset1_unit:asset2_unit:dex)
    asset1_id BIGINT NOT NULL,       -- Asset 1 in the pool
    asset2_id BIGINT NOT NULL,       -- Asset 2 in the pool
    provider INT NOT NULL,           -- DEX provider (Minswap, Sundaeswap, etc.)
    time BIGINT NOT NULL,            -- Unix timestamp in seconds
    reserve1 DECIMAL(38,0) NOT NULL, -- Reserve amount of asset1
    reserve2 DECIMAL(38,0) NOT NULL, -- Reserve amount of asset2
    tx_id BIGINT NOT NULL,           -- Transaction that updated the reserves
    PRIMARY KEY (pool_id, time),
    CONSTRAINT fk_pool_reserve_asset1 FOREIGN KEY (asset1_id) REFERENCES asset(id) ON DELETE CASCADE,
    CONSTRAINT fk_pool_reserve_asset2 FOREIGN KEY (asset2_id) REFERENCES asset(id) ON DELETE CASCADE,
    CONSTRAINT fk_pool_reserve_tx FOREIGN KEY (tx_id) REFERENCES tx(id) ON DELETE CASCADE
);

-- Index for querying latest reserves by pool
CREATE INDEX IF NOT EXISTS idx_pool_reserve_latest ON pool_reserve (pool_id, time DESC);

-- Index for querying reserves by time range
CREATE INDEX IF NOT EXISTS idx_pool_reserve_time ON pool_reserve (time DESC);

-- Latest Pool Reserves Table
-- Stores the most recent reserves for each pool (for fast TVL queries)
CREATE TABLE IF NOT EXISTS latest_pool_reserve(
    pool_id VARCHAR(255) NOT NULL PRIMARY KEY,
    asset1_id BIGINT NOT NULL,
    asset2_id BIGINT NOT NULL,
    provider INT NOT NULL,
    time BIGINT NOT NULL,
    reserve1 DECIMAL(38,0) NOT NULL,
    reserve2 DECIMAL(38,0) NOT NULL,
    tx_id BIGINT NOT NULL,
    CONSTRAINT fk_latest_pool_reserve_asset1 FOREIGN KEY (asset1_id) REFERENCES asset(id) ON DELETE CASCADE,
    CONSTRAINT fk_latest_pool_reserve_asset2 FOREIGN KEY (asset2_id) REFERENCES asset(id) ON DELETE CASCADE,
    CONSTRAINT fk_latest_pool_reserve_tx FOREIGN KEY (tx_id) REFERENCES tx(id) ON DELETE CASCADE
);

-- Index for querying by provider
CREATE INDEX IF NOT EXISTS idx_latest_pool_reserve_provider ON latest_pool_reserve (provider);

-- Index for querying by assets
CREATE INDEX IF NOT EXISTS idx_latest_pool_reserve_assets ON latest_pool_reserve (asset1_id, asset2_id);