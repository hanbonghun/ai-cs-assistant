# AI CS Assistant Design

## 1. Overview

`AI CS Assistant` is a counselor-assist system for handling customer inquiries in a fictional reservation platform.

The product goal is not full automation. It helps a human counselor by:

- classifying the inquiry category
- estimating urgency
- retrieving relevant operation manuals with RAG
- generating a manual-grounded answer draft
- requiring human review before final confirmation

This project is intentionally scoped as a 2-week MVP focused on `AI/RAG demo quality`, not backend completeness.

## 2. MVP Goals

- Register customer inquiries
- Run AI analysis on demand
- Classify category into a fixed enum
- Classify urgency into a fixed enum
- Retrieve relevant manual chunks with pgvector-based similarity search
- Generate a draft response grounded in retrieved manuals
- Let a counselor review and confirm the final response
- Preserve analysis logs for debugging and demo explanation

## 3. Non-Goals

- Fully automatic response sending
- Medical diagnosis or treatment judgment
- Authentication and authorization
- External reservation/CRM integration
- Real-time chat
- Complex workflow or routing engine
- Multi-service distributed architecture

## 4. Core Constraints

- The system is a counselor assist tool, not an autopilot
- Human review is mandatory before final answer confirmation
- Medical judgment must be avoided
- Scope must stay implementable within 2 weeks
- AI orchestration is centralized in `InquiryAnalysisService`

## 5. Recommended Architecture

Recommended approach: `Spring Boot thin monolith + PostgreSQL/pgvector + minimal Thymeleaf UI`

Rationale:

- keeps the original Java/Spring direction
- is still fast enough for a side-project MVP
- keeps API, persistence, RAG, and review UI in one deployable unit
- makes demo flow simple: `Inquiry -> Analyze -> Review -> Confirm`

### High-Level Components

- `Web UI`
  - minimal counselor screens for inquiry list, inquiry detail, AI result review, manual management
- `Application Layer`
  - use-case orchestration and state transition handling
- `AI Layer`
  - category classification, urgency classification, draft generation
- `RAG Layer`
  - chunking, embedding, vector similarity search, evidence selection
- `Persistence Layer`
  - PostgreSQL tables for inquiries, manuals, chunks, analysis logs

## 6. Package Structure

```text
com.aicsassistant
├─ common
│  ├─ config
│  ├─ enums
│  ├─ exception
│  ├─ response
│  └─ util
├─ inquiry
│  ├─ api
│  ├─ application
│  ├─ domain
│  ├─ dto
│  └─ infra
├─ manual
│  ├─ api
│  ├─ application
│  ├─ domain
│  ├─ dto
│  └─ infra
├─ analysis
│  ├─ api
│  ├─ application
│  ├─ domain
│  ├─ dto
│  ├─ rag
│  └─ infra
│     ├─ llm
│     └─ vector
└─ ui
   ├─ controller
   └─ viewmodel
```

### Package Responsibilities

- `common`
  - shared config, exception mapping, response wrapper, enums, shared utilities
- `inquiry`
  - inquiry registration, query, status transitions, final answer confirmation
- `manual`
  - manual document CRUD, chunk generation trigger, manual administration
- `analysis`
  - AI orchestration, prompt generation, RAG retrieval, draft generation, analysis logging
- `ui`
  - minimal counselor review screens

## 7. Domain Model

### 7.1 Inquiry

Purpose: main customer inquiry aggregate and latest analysis/result snapshot.

Key fields:

- `id`
- `customerIdentifier`
- `title`
- `content`
- `category`
- `urgency`
- `status`
- `aiDraftAnswer`
- `finalAnswer`
- `reviewMemo`
- `reviewedBy`
- `createdAt`
- `updatedAt`

### 7.2 ManualDocument

Purpose: source document for operation manuals.

Key fields:

- `id`
- `title`
- `category`
- `content`
- `version`
- `active`
- `createdAt`
- `updatedAt`

### 7.3 ManualChunk

Purpose: RAG retrieval unit derived from `ManualDocument`.

Key fields:

- `id`
- `manualDocumentId`
- `chunkIndex`
- `documentVersion`
- `content`
- `tokenCount`
- `embedding`
- `active`
- `createdAt`

### 7.4 InquiryAnalysisLog

Purpose: immutable execution log for AI analysis attempts.

Key fields:

- `id`
- `inquiryId`
- `requestSnapshot`
- `classifiedCategory`
- `classifiedUrgency`
- `retrievedChunkIds`
- `generatedDraft`
- `modelName`
- `promptVersion`
- `analysisStatus`
- `errorMessage`
- `latencyMs`
- `createdAt`

### Relationships

- `Inquiry` 1:N `InquiryAnalysisLog`
- `ManualDocument` 1:N `ManualChunk`

Design choice:

- `Inquiry` stores the latest user-visible AI result directly for simple reads
- `InquiryAnalysisLog` stores historical attempts for debugging and prompt iteration

## 8. State Model

`InquiryStatus`

- `NEW`
- `AI_PROCESSED`
- `REVIEWED`
- `CLOSED`

Primary transition:

```text
NEW -> AI_PROCESSED -> REVIEWED -> CLOSED
```

Allowed exceptions:

- `NEW -> REVIEWED`
  - counselor answers without AI analysis
- `AI_PROCESSED -> AI_PROCESSED`
  - re-analysis allowed

Guardrails:

- no final automatic send
- no analysis after `CLOSED`
- human action required for `REVIEWED`
- `REVIEWED -> CLOSED` is the standard closure path
- `NEW -> CLOSED` and `AI_PROCESSED -> CLOSED` are not allowed in the MVP

## 9. Enums

### InquiryCategory

- `RESERVATION_CHANGE`
- `RESERVATION_CANCEL`
- `REFUND`
- `PRICE`
- `POST_TREATMENT`
- `MEMBERSHIP`
- `COMPLAINT`
- `GENERAL`

### UrgencyLevel

- `LOW`
- `MEDIUM`
- `HIGH`

## 10. Core User Flow

### 10.1 Inquiry Registration

1. Counselor registers an inquiry
2. `Inquiry(status=NEW)` is saved
3. Inquiry appears in list/detail UI

### 10.2 AI Analysis

1. Counselor opens inquiry detail
2. Counselor clicks `AI 분석`
3. `InquiryAnalysisService` runs:
   - category classification
   - urgency classification
   - manual chunk retrieval
   - draft response generation
   - analysis log persistence
4. Inquiry is updated with latest AI results
5. Status becomes `AI_PROCESSED`

### 10.3 Human Review

1. Counselor reads the original inquiry
2. Counselor checks AI category, urgency, retrieved evidence, and draft answer
3. Counselor edits the response if needed
4. Counselor confirms final answer
5. Status becomes `REVIEWED`

### 10.4 Closure

1. Counselor closes the handled inquiry
2. Status becomes `CLOSED`

## 11. Service Design

### 11.1 Main Services

- `InquiryService`
  - create inquiry
  - list inquiries
  - get inquiry detail
  - basic state transition helpers
- `InquiryAnalysisService`
  - central AI/RAG orchestration entry point
- `ManualService`
  - manual CRUD
  - manual chunk regeneration trigger
- `ReviewService`
  - counselor final answer confirmation
- `AnalysisLogService`
  - analysis log persistence and lookup

### 11.2 AI-Oriented Collaborators

- `InquiryClassifier`
- `UrgencyClassifier`
- `ManualRetrievalService`
- `DraftAnswerService`
- `PromptFactory`
- `LlmClient`

### 11.3 Analysis Orchestration

`InquiryAnalysisService.analyze(inquiryId)`:

1. load inquiry
2. validate current state
3. classify category
4. classify urgency
5. retrieve relevant manual chunks
6. generate draft answer grounded in retrieved chunks
7. update inquiry with latest analysis snapshot
8. save analysis log
9. transition to `AI_PROCESSED`
10. return analysis result DTO

This service is the single place that knows the full analysis workflow.

## 12. Repository Design

- `InquiryRepository`
  - CRUD and filtered list queries
- `ManualDocumentRepository`
  - CRUD for manual documents
- `ManualChunkRepository`
  - document chunk lookup
  - pgvector similarity query
- `InquiryAnalysisLogRepository`
  - log history by inquiry

Implementation note:

- normal persistence uses Spring Data JPA
- vector search uses native PostgreSQL query

## 13. API Design

### Inquiry APIs

- `POST /api/inquiries`
  - register inquiry
- `GET /api/inquiries`
  - list inquiries with optional filters
- `GET /api/inquiries/{id}`
  - get inquiry detail
- `POST /api/inquiries/{id}/analyze`
  - run AI analysis
- `POST /api/inquiries/{id}/review`
  - confirm counselor-edited final answer
- `POST /api/inquiries/{id}/close`
  - close inquiry

### Manual APIs

- `POST /api/manual-documents`
  - create manual document
- `GET /api/manual-documents`
  - list manual documents
- `GET /api/manual-documents/{id}`
  - get manual document detail
- `PUT /api/manual-documents/{id}`
  - update manual document
- `DELETE /api/manual-documents/{id}`
  - delete or deactivate manual document
- `GET /api/manual-documents/{id}/chunks`
  - inspect generated chunks

## 14. DTO Design

### Inquiry DTOs

- `CreateInquiryRequest`
- `InquiryListResponse`
- `InquiryDetailResponse`
- `ReviewInquiryRequest`

### Analysis DTOs

- `InquiryAnalysisResponse`
- `CategoryResultDto`
- `UrgencyResultDto`
- `RetrievedManualChunkDto`
- `DraftAnswerDto`
- `InquiryAnalysisLogResponse`

### Manual DTOs

- `CreateManualDocumentRequest`
- `UpdateManualDocumentRequest`
- `ManualDocumentResponse`
- `ManualChunkResponse`

### Review Contract

`POST /api/inquiries/{id}/review`

Request:

- `finalAnswer`
- `reviewMemo`
- `confirmedBy`

Validation:

- `finalAnswer` is required
- `confirmedBy` is required
- `reviewMemo` is optional

Behavior:

- persists the counselor-edited `finalAnswer`
- persists optional `reviewMemo`
- records the confirming actor as `confirmedBy`
- transitions the inquiry status to `REVIEWED`

MVP identity rule:

- `confirmedBy` is a required free-text counselor display name supplied from the review UI
- it is not tied to authentication in the MVP
- the value is persisted as `Inquiry.reviewedBy`

Response:

- updated inquiry id
- updated status
- persisted final answer
- updated timestamp

## 15. Error Handling Strategy

Use a centralized `@RestControllerAdvice`.

Recommended custom exceptions:

- `InquiryNotFoundException`
- `ManualDocumentNotFoundException`
- `InvalidInquiryStateException`
- `AiAnalysisFailedException`
- `RagSearchFailedException`
- `EmbeddingGenerationFailedException`
- `ExternalLlmApiException`

Error response shape:

```json
{
  "code": "INQUIRY_NOT_FOUND",
  "message": "문의를 찾을 수 없습니다.",
  "timestamp": "2026-04-08T14:00:00"
}
```

Error-handling rules:

- AI call failure should be logged in `InquiryAnalysisLog`
- missing retrieval results should not always be treated as a system error
- invalid state transitions should fail explicitly

## 16. AI and RAG Design

### 16.1 Retrieval Strategy

- chunk manual documents into manageable text pieces
- store embeddings in `manual_chunk`
- retrieve top `k=3~5` chunks by vector similarity
- show evidence chunks in counselor UI

Embedding strategy for MVP:

- use `OpenAI text-embedding-3-small`
- embedding dimension is fixed to `1536`
- chat/completion provider may be `OpenAI` or `Claude`
- embeddings remain OpenAI-backed even if the chat model is switched

Reason:

- keeps pgvector schema stable
- avoids provider-specific embedding incompatibility in the MVP
- makes implementation planning deterministic

Current chunk selection rule:

- each `ManualChunk` stores `documentVersion`
- each `ManualChunk` stores `active`
- retrieval queries filter by `active=true`
- when a manual is updated, `ManualDocument.version` increments
- previous chunks for that document are marked `active=false`
- newly generated chunks are inserted with the new `documentVersion` and `active=true`

### 16.2 Prompt Strategy

Two-step prompting is recommended.

#### Step 1. Inquiry Analysis Prompt

Expected structured output:

- `category`
- `urgency`
- `reason`
- `needsHumanReview`
- `needsEscalation`
- `medicalRiskFlag`

Rules:

- classify only using the supported enums
- do not make medical judgments
- escalate when uncertain
- assume human review is required

#### Step 2. Draft Answer Prompt

Inputs:

- inquiry text
- category
- urgency
- retrieved manual chunks
- safety instructions

Outputs:

- counselor-facing draft response
- internal note
- used evidence chunk ids

Rules:

- do not invent policy not grounded in manuals
- do not diagnose or provide medical judgment
- when uncertain, ask for internal confirmation instead of making a hard promise

## 17. Minimal UI Design

### 17.1 Inquiry List Page

- list title, category, urgency, status
- navigate to inquiry detail
- allow new inquiry creation

### 17.2 Inquiry Detail / Review Page

- original inquiry content
- AI category result
- AI urgency result
- retrieved manual evidence
- AI-generated draft answer
- editable final answer field
- buttons for `AI 분석`, `최종 확정`, `종료`

### 17.3 Manual Management Page

- create and update manual documents
- display document detail
- inspect chunks for admin/debug use

UI goal:

- enough to demonstrate human-in-the-loop workflow
- avoid heavy frontend investment

Manual lifecycle rule for MVP:

- manual delete is treated as soft delete by setting `active=false`
- inactive manuals are excluded from retrieval
- existing chunks are preserved for traceability and ignored by retrieval filters once `active=false`
- when a manual document is updated, its previous chunks are superseded by marking them inactive and generating a new active chunk set

## 18. PostgreSQL Schema Summary

### inquiry

- `id`
- `customer_identifier`
- `title`
- `content`
- `category`
- `urgency`
- `status`
- `ai_draft_answer`
- `final_answer`
- `review_memo`
- `reviewed_by`
- `created_at`
- `updated_at`

### manual_document

- `id`
- `title`
- `category`
- `content`
- `version`
- `active`
- `created_at`
- `updated_at`

### manual_chunk

- `id`
- `manual_document_id`
- `chunk_index`
- `document_version`
- `content`
- `token_count`
- `embedding vector(1536)`
- `active`
- `created_at`

### inquiry_analysis_log

- `id`
- `inquiry_id`
- `request_snapshot`
- `classified_category`
- `classified_urgency`
- `retrieved_chunk_ids`
- `generated_draft`
- `model_name`
- `prompt_version`
- `analysis_status`
- `error_message`
- `latency_ms`
- `created_at`

## 19. Seed Data Design

Recommended seed size:

- inquiries: 12
- manual documents: 8
- chunks per document: 3 to 6

Manual categories to include:

- reservation change policy
- reservation cancel / no-show policy
- refund policy
- pricing and payment guidance
- post-treatment general guidance
- membership / coupon policy
- complaint handling guide
- common counselor response principles

## 20. 2-Week MVP Delivery Priority

### Week 1

- initialize project
- implement entities and repositories
- connect PostgreSQL
- implement inquiry CRUD
- implement manual CRUD
- implement manual chunk generation
- build minimal inquiry list/detail UI

### Week 2 Early

- integrate OpenAI or Claude API
- implement `InquiryAnalysisService`
- implement classification and urgency judgment
- implement pgvector retrieval
- implement draft answer generation
- persist analysis logs

### Week 2 Late

- implement counselor review confirmation flow
- refine error handling and logs
- expose Swagger docs
- prepare demo scenarios and sample data

## 21. Extension Points After MVP

- counselor/admin roles
- confidence scoring
- re-analysis comparison view
- evidence highlighting in UI
- external reservation/CRM integration
- asynchronous job processing
- feedback-driven prompt optimization
- provider abstraction for OpenAI and Claude switching

## 22. Recommendation Summary

This project should be positioned as:

`AI/RAG-based counselor assist system with a thin Spring backend and minimal review UI`

Success is measured by:

- believable inquiry classification
- useful urgency estimation
- relevant manual retrieval
- grounded draft generation
- clear human-review guardrail

It should not be judged as a full production CS platform MVP.
