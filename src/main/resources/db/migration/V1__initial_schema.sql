CREATE TABLE app_user (
    id BIGINT PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE refresh_token (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE plan_catalog (
    id BIGINT PRIMARY KEY,
    code VARCHAR(30) NOT NULL UNIQUE,
    name VARCHAR(60) NOT NULL,
    monthly_price DECIMAL(10,2) NOT NULL,
    annual_price DECIMAL(10,2) NOT NULL,
    translation_limit INTEGER NOT NULL,
    grading_limit INTEGER NOT NULL,
    mnemonic_limit INTEGER NOT NULL,
    custom_domain_limit INTEGER NOT NULL,
    mnemonic_versions INTEGER NOT NULL
);

CREATE TABLE subscription (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE REFERENCES app_user(id) ON DELETE CASCADE,
    plan_code VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    starts_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE translation_domain (
    id BIGINT PRIMARY KEY,
    owner_id BIGINT REFERENCES app_user(id) ON DELETE CASCADE,
    code VARCHAR(40),
    name VARCHAR(60) NOT NULL,
    description VARCHAR(500) NOT NULL,
    built_in BOOLEAN NOT NULL DEFAULT FALSE,
    medical_disclaimer BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_builtin_domain_code UNIQUE(code)
);

CREATE TABLE domain_term (
    id BIGINT PRIMARY KEY,
    domain_id BIGINT NOT NULL REFERENCES translation_domain(id) ON DELETE CASCADE,
    source_term VARCHAR(200) NOT NULL,
    target_term VARCHAR(200) NOT NULL,
    note VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_domain_term UNIQUE(domain_id, source_term)
);

CREATE TABLE translation (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    domain_id BIGINT REFERENCES translation_domain(id) ON DELETE SET NULL,
    source_language VARCHAR(10) NOT NULL,
    target_language VARCHAR(10) NOT NULL,
    source_text TEXT NOT NULL,
    translated_text TEXT NOT NULL,
    detected_language VARCHAR(10) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE translation_term (
    id BIGINT PRIMARY KEY,
    translation_id BIGINT NOT NULL REFERENCES translation(id) ON DELETE CASCADE,
    source_term VARCHAR(200) NOT NULL,
    target_term VARCHAR(200) NOT NULL,
    part_of_speech VARCHAR(40),
    ipa VARCHAR(100),
    explanation VARCHAR(500),
    source_context VARCHAR(1000)
);

CREATE TABLE word_pack (
    id BIGINT PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(300) NOT NULL,
    item_count INTEGER NOT NULL DEFAULT 0,
    pro_only BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE word_pack_entry (
    id BIGINT PRIMARY KEY,
    pack_id BIGINT NOT NULL REFERENCES word_pack(id) ON DELETE CASCADE,
    word VARCHAR(120) NOT NULL,
    part_of_speech VARCHAR(40),
    ipa VARCHAR(100),
    chinese_meaning VARCHAR(500) NOT NULL,
    english_definition VARCHAR(500),
    example_sentence VARCHAR(1000),
    CONSTRAINT uq_pack_word UNIQUE(pack_id, word)
);

CREATE TABLE vocabulary_item (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    word VARCHAR(120) NOT NULL,
    part_of_speech VARCHAR(40),
    ipa VARCHAR(100),
    chinese_meaning VARCHAR(500) NOT NULL,
    english_definition VARCHAR(500),
    example_sentence VARCHAR(1000),
    source_context TEXT,
    source_type VARCHAR(30) NOT NULL,
    source_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_word UNIQUE(user_id, word)
);

CREATE TABLE review_schedule (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    vocabulary_id BIGINT NOT NULL REFERENCES vocabulary_item(id) ON DELETE CASCADE,
    direction VARCHAR(20) NOT NULL,
    repetitions INTEGER NOT NULL DEFAULT 0,
    interval_days INTEGER NOT NULL DEFAULT 0,
    easiness DECIMAL(4,2) NOT NULL DEFAULT 2.50,
    due_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_result VARCHAR(20),
    CONSTRAINT uq_review_direction UNIQUE(user_id, vocabulary_id, direction)
);

CREATE TABLE review_session (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    direction VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    item_limit INTEGER NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);

CREATE TABLE review_attempt (
    id BIGINT PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES review_session(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    vocabulary_id BIGINT NOT NULL REFERENCES vocabulary_item(id) ON DELETE CASCADE,
    direction VARCHAR(20) NOT NULL,
    answer TEXT NOT NULL,
    result VARCHAR(20) NOT NULL,
    feedback VARCHAR(1000),
    confused_with VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE confusion_group (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE confusion_word (
    id BIGINT PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES confusion_group(id) ON DELETE CASCADE,
    word VARCHAR(120) NOT NULL,
    meaning VARCHAR(500),
    ipa VARCHAR(100),
    part_of_speech VARCHAR(40),
    example_sentence VARCHAR(1000),
    CONSTRAINT uq_confusion_word UNIQUE(group_id, word)
);

CREATE TABLE mnemonic (
    id BIGINT PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES confusion_group(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    style VARCHAR(30) NOT NULL,
    slogan VARCHAR(500) NOT NULL,
    visual_hook TEXT,
    it_analogy TEXT,
    contrast_example TEXT,
    recall_hook VARCHAR(500),
    user_note TEXT,
    helpful BOOLEAN,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE daily_usage (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    usage_date DATE NOT NULL,
    translation_count INTEGER NOT NULL DEFAULT 0,
    grading_count INTEGER NOT NULL DEFAULT 0,
    mnemonic_count INTEGER NOT NULL DEFAULT 0,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_daily_usage UNIQUE(user_id, usage_date)
);

CREATE TABLE product_event (
    id BIGINT PRIMARY KEY,
    user_id BIGINT REFERENCES app_user(id) ON DELETE SET NULL,
    event_name VARCHAR(80) NOT NULL,
    properties_json TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_translation_user_created ON translation(user_id, created_at DESC);
CREATE INDEX idx_vocabulary_user ON vocabulary_item(user_id, created_at DESC);
CREATE INDEX idx_review_due ON review_schedule(user_id, due_at);
CREATE INDEX idx_attempt_user_created ON review_attempt(user_id, created_at DESC);
CREATE INDEX idx_event_name_time ON product_event(event_name, occurred_at DESC);

INSERT INTO plan_catalog VALUES
  (1001, 'FREE', '免费版', 0, 0, 10, 30, 3, 1, 1),
  (1002, 'PRO', 'Pro 会员', 19.90, 159.00, 100, 300, 30, 20, 10);

INSERT INTO translation_domain(id, code, name, description, built_in, medical_disclaimer) VALUES
  (2001, 'IT', 'IT / 软件开发', '面向软件开发、云计算、数据库、网络与工程实践的专业翻译。', TRUE, FALSE),
  (2002, 'MEDICAL', '医学', '面向基础医学、临床研究与医学文献阅读的专业翻译。', TRUE, TRUE);

INSERT INTO domain_term VALUES
  (2101, 2001, 'dependency injection', '依赖注入', '软件架构术语', now()),
  (2102, 2001, 'repository', '仓库', '在代码管理语境使用', now()),
  (2103, 2001, 'thread', '线程', '并发编程语境', now()),
  (2201, 2002, 'randomized controlled trial', '随机对照试验', '循证医学术语', now());

INSERT INTO word_pack VALUES (3001, 'IT_CORE', '开发文档高频词', '阅读 API、框架与工程文档最常遇到的核心词汇。', 20, FALSE);
INSERT INTO word_pack_entry VALUES
 (3101,3001,'deprecated','adjective','/ˈdeprəkeɪtɪd/','已弃用的','discouraged from use because a better alternative exists','This API is deprecated and will be removed in the next release.'),
 (3102,3001,'invoke','verb','/ɪnˈvoʊk/','调用；执行','to cause a function or process to run','Invoke the callback after the request completes.'),
 (3103,3001,'immutable','adjective','/ɪˈmjuːtəbl/','不可变的','unable to be changed after creation','The configuration object is immutable.'),
 (3104,3001,'payload','noun','/ˈpeɪloʊd/','有效载荷；请求数据','the actual data carried by a request or message','The JSON payload contains the user profile.'),
 (3105,3001,'concurrent','adjective','/kənˈkɜːrənt/','并发的','happening or executing at the same time','The server handles concurrent requests.'),
 (3106,3001,'throughput','noun','/ˈθruːpʊt/','吞吐量','the amount of work completed in a period','Batching improves write throughput.'),
 (3107,3001,'latency','noun','/ˈleɪtənsi/','延迟','the delay before a transfer or operation begins','Caching reduces request latency.'),
 (3108,3001,'fallback','noun','/ˈfɔːlbæk/','降级方案；后备','an alternative used when the preferred option fails','Use the local cache as a fallback.'),
 (3109,3001,'verbose','adjective','/vɜːrˈboʊs/','冗长的；详细的','using more words or output than necessary','Enable verbose logging while debugging.'),
 (3110,3001,'retrieve','verb','/rɪˈtriːv/','检索；获取','to get stored information','Retrieve the entity by its identifier.'),
 (3111,3001,'persist','verb','/pərˈsɪst/','持久化；持续存在','to store data beyond the current process','Persist the session in PostgreSQL.'),
 (3112,3001,'constraint','noun','/kənˈstreɪnt/','约束','a restriction or rule','Add a unique constraint to the email column.'),
 (3113,3001,'override','verb','/ˌoʊvərˈraɪd/','覆盖；重写','to replace inherited or default behavior','Override the default timeout.'),
 (3114,3001,'compatible','adjective','/kəmˈpætəbl/','兼容的','able to work together without conflict','The client is backward compatible.'),
 (3115,3001,'explicit','adjective','/ɪkˈsplɪsɪt/','明确的；显式的','clearly stated rather than implied','Use an explicit transaction boundary.'),
 (3116,3001,'implicit','adjective','/ɪmˈplɪsɪt/','隐含的；隐式的','suggested or understood without being stated','The framework performs implicit conversion.'),
 (3117,3001,'instantiate','verb','/ɪnˈstænʃieɪt/','实例化','to create an instance of a class','The container instantiates the service.'),
 (3118,3001,'serialize','verb','/ˈsɪriəlaɪz/','序列化','to convert data into a transferable format','Serialize the object as JSON.'),
 (3119,3001,'idempotent','adjective','/ˌaɪdəmˈpoʊtənt/','幂等的','producing the same result when repeated','PUT operations should be idempotent.'),
 (3120,3001,'prerequisite','noun','/priːˈrekwəzɪt/','先决条件','something required before another action','Java 21 is a prerequisite for this project.');
