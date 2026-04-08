create extension if not exists vector;

create table if not exists inquiry (
    id bigserial primary key,
    customer_identifier varchar(100) not null,
    title varchar(200) not null,
    content text not null,
    category varchar(50) not null,
    urgency varchar(20) not null,
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
    version varchar(50) not null,
    active boolean not null,
    created_at timestamp not null,
    updated_at timestamp not null
);

create table if not exists manual_chunk (
    id bigserial primary key,
    document_id bigint not null references manual_document(id),
    chunk_index integer not null,
    document_version varchar(50) not null,
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
    classified_category varchar(50) not null,
    classified_urgency varchar(20) not null,
    retrieved_chunk_ids text,
    generated_draft text,
    model_name varchar(100),
    prompt_version varchar(50),
    analysis_status varchar(20) not null,
    error_message text,
    latency_ms bigint,
    created_at timestamp not null
);

create index if not exists idx_manual_chunk_document_id on manual_chunk(document_id);
create index if not exists idx_inquiry_analysis_log_inquiry_id on inquiry_analysis_log(inquiry_id);
