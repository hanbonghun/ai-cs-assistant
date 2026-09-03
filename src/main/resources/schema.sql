create extension if not exists vector;
create extension if not exists pg_trgm;

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
alter table inquiry_analysis_log add column if not exists ai_draft_rating varchar(10);
alter table inquiry_analysis_log add column if not exists ai_draft_rating_reason varchar(50);
alter table inquiry_analysis_log add column if not exists ai_draft_rating_note text;
alter table inquiry add column if not exists related_order_id varchar(50);

create index if not exists idx_manual_chunk_manual_document_id on manual_chunk(manual_document_id);
create index if not exists idx_manual_chunk_content_trgm on manual_chunk using gin (content gin_trgm_ops);
create index if not exists idx_inquiry_analysis_log_inquiry_id on inquiry_analysis_log(inquiry_id);
create index if not exists idx_inquiry_message_inquiry_id on inquiry_message(inquiry_id);

create table if not exists staged_change (
    id bigserial primary key,
    inquiry_id bigint not null references inquiry(id),
    change_type varchar(20) not null,
    order_id varchar(50) not null,
    amount integer not null,
    reason text not null,
    policy_basis text,
    status varchar(20) not null,
    decided_by varchar(100),
    decided_at timestamp,
    decision_note text,
    created_at timestamp not null
);

create index if not exists idx_staged_change_order_pending
    on staged_change(order_id) where status = 'PENDING';

-- 상담사가 제안 금액을 수정해 승인한 경우의 최종 금액. null 이면 제안 금액을 그대로 승인한 것이다.
alter table staged_change add column if not exists approved_amount integer;

-- 동시 승인 방지용 낙관적 잠금 버전. 두 상담사가 같은 제안을 동시에 승인/거부하는 것을 막는다.
alter table staged_change add column if not exists version bigint not null default 0;
