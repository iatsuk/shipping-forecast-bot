CREATE TABLE IF NOT EXISTS telegram_user (
    chat_id BIGINT NOT NULL,
    CONSTRAINT pk_telegram_user PRIMARY KEY (chat_id)
);

CREATE TABLE IF NOT EXISTS user_subscription (
    chat_id BIGINT       NOT NULL,
    area    VARCHAR(100) NOT NULL,
    CONSTRAINT pk_user_subscription PRIMARY KEY (chat_id, area),
    CONSTRAINT fk_user_subscription_user FOREIGN KEY (chat_id) REFERENCES telegram_user(chat_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS forecast_cache (
    url        VARCHAR(500) NOT NULL,
    content    CLOB         NOT NULL,
    fetched_at TIMESTAMP    NOT NULL,
    CONSTRAINT pk_forecast_cache PRIMARY KEY (url)
);
