CREATE TABLE IF NOT EXISTS wallet (
    id          VARCHAR(21)    PRIMARY KEY,
    customer_id VARCHAR(64)    NOT NULL UNIQUE,
    balance     NUMERIC(18, 2) NOT NULL DEFAULT 0.00,
    currency    VARCHAR(3)     NOT NULL DEFAULT 'INR',
    status      VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by  VARCHAR(64)    NOT NULL DEFAULT 'system',
    updated_by  VARCHAR(64)    NOT NULL DEFAULT 'system',

    CONSTRAINT chk_wallet_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT chk_wallet_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

CREATE TABLE IF NOT EXISTS wallet_transaction (
    id              VARCHAR(21)    PRIMARY KEY,
    wallet_id       VARCHAR(21)    NOT NULL REFERENCES wallet(id),
    type            VARCHAR(20)    NOT NULL,
    amount          NUMERIC(18, 2) NOT NULL,
    balance_before  NUMERIC(18, 2) NOT NULL,
    balance_after   NUMERIC(18, 2) NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    idempotency_key VARCHAR(128)   UNIQUE,
    reference_id    VARCHAR(128),
    reference_type  VARCHAR(32),
    failure_reason  VARCHAR(255),
    metadata        JSONB,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(64)    NOT NULL DEFAULT 'system',

    CONSTRAINT chk_txn_amount_positive   CHECK (amount > 0),
    CONSTRAINT chk_txn_balance_after_gte CHECK (balance_after >= 0),
    CONSTRAINT chk_txn_type   CHECK (type   IN ('TOPUP', 'DEDUCTION', 'REFUND', 'REVERSAL')),
    CONSTRAINT chk_txn_status CHECK (status IN ('SUCCESS', 'FAILED', 'REVERSED'))
);

CREATE INDEX IF NOT EXISTS idx_wt_wallet_id    ON wallet_transaction(wallet_id);
CREATE INDEX IF NOT EXISTS idx_wt_reference_id ON wallet_transaction(reference_id);
CREATE INDEX IF NOT EXISTS idx_wt_wallet_cursor ON wallet_transaction(wallet_id, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS idempotency_record (
    idempotency_key  VARCHAR(128)  PRIMARY KEY,
    request_hash     VARCHAR(64)   NOT NULL,
    response_body    JSONB         NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
