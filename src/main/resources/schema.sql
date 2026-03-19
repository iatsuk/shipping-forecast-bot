CREATE TABLE IF NOT EXISTS user_subscription (
    chat_id BIGINT      NOT NULL,
    area    VARCHAR(100) NOT NULL,
    CONSTRAINT pk_user_subscription PRIMARY KEY (chat_id, area)
);

CREATE TABLE IF NOT EXISTS forecast_cache (
    url        VARCHAR(500) NOT NULL,
    content    CLOB         NOT NULL,
    fetched_at TIMESTAMP    NOT NULL,
    CONSTRAINT pk_forecast_cache PRIMARY KEY (url)
);
