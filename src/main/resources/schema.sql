create extension if not exists vector;

create table if not exists inquiry (
    id bigserial primary key,
    customer_identifier varchar(100) not null,
    title varchar(200) not null,
    content text not null,
    category varchar(50),
    urgency varchar(20),
    status varchar(20) not null,
    ai_draft_answer text,
    final_answer text,
    review_memo text,
    reviewed_by varchar(100),
    created_at timestamp not null,
    updated_at timestamp not null
);

create table if not exists manual_document (
    id bigserial primary key,
    title varchar(200) not null,
    category varchar(50) not null,
    content text not null,
    version integer not null,
    active boolean not null,
    created_at timestamp not null,
    updated_at timestamp not null
);

create table if not exists manual_chunk (
    id bigserial primary key,
    manual_document_id bigint not null references manual_document(id),
    chunk_index integer not null,
    document_version integer not null,
    content text not null,
    token_count integer not null,
    embedding vector(1536),
    active boolean not null,
    created_at timestamp not null
);

create table if not exists inquiry_analysis_log (
    id bigserial primary key,
    inquiry_id bigint not null references inquiry(id),
    request_snapshot text not null,
    classified_category varchar(50),
    classified_urgency varchar(20),
    retrieved_chunk_ids text,
    generated_draft text,
    model_name varchar(100),
    prompt_version varchar(50),
    analysis_status varchar(20) not null,
    error_message text,
    latency_ms bigint,
    agent_steps text,
    created_at timestamp not null
);

create table if not exists inquiry_message (
    id          bigserial primary key,
    inquiry_id  bigint not null references inquiry(id),
    role        varchar(20) not null,
    content     text not null,
    created_at  timestamp not null
);

-- 컬럼 추가 마이그레이션 (테이블이 이미 존재하는 경우)
alter table inquiry_analysis_log add column if not exists agent_steps text;
alter table inquiry_analysis_log add column if not exists latency_ms bigint;
alter table inquiry_analysis_log add column if not exists total_tokens integer;
alter table inquiry add column if not exists related_order_id varchar(50);

create index if not exists idx_manual_chunk_manual_document_id on manual_chunk(manual_document_id);
create index if not exists idx_inquiry_analysis_log_inquiry_id on inquiry_analysis_log(inquiry_id);
create index if not exists idx_inquiry_message_inquiry_id on inquiry_message(inquiry_id);
