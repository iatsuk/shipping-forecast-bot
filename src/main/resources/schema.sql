CREATE TABLE IF NOT EXISTS user_subscription (
    chat_id BIGINT      NOT NULL,
    area    VARCHAR(100) NOT NULL,
    CONSTRAINT pk_user_subscription PRIMARY KEY (chat_id, area)
);
