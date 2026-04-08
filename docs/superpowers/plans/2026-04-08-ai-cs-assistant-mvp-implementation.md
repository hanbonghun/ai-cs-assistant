# AI CS Assistant MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a thin Spring Boot MVP that demonstrates inquiry intake, AI/RAG analysis, manual-grounded draft generation, and mandatory human review with a minimal web UI.

**Architecture:** Use a single Spring Boot 3 application with Thymeleaf, PostgreSQL, and pgvector. Keep orchestration in `InquiryAnalysisService`, use JPA for ordinary persistence, and use a dedicated pgvector/JdbcTemplate repository for manual chunk insert/search to avoid JPA vector-mapping complexity in the MVP.

**Tech Stack:** Java 17, Spring Boot 3, Spring Web, Spring Data JPA, Thymeleaf, PostgreSQL, pgvector, WebClient, springdoc-openapi, JUnit 5, MockMvc, Testcontainers

---

## Test Strategy

- Use plain unit tests for chunking, prompt generation, and orchestration collaborators
- Use `@WebMvcTest` for controller and UI route tests
- Use Testcontainers with `pgvector/pgvector:pg16` for repository and integration tests that require PostgreSQL + pgvector
- Keep the early bootstrap context test independent from a real datasource by excluding JDBC/JPA auto-configuration until persistence tasks land

## File Map

### Project bootstrap

- Create: `.gitignore`
- Create: `settings.gradle`
- Create: `build.gradle`
- Create: `src/main/java/com/aicsassistant/AiCsAssistantApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/test/java/com/aicsassistant/AiCsAssistantApplicationTests.java`
- Create: `src/test/resources/application-test.yml`

### Common

- Create: `src/main/java/com/aicsassistant/common/config/AiProperties.java`
- Create: `src/main/java/com/aicsassistant/common/config/WebClientConfig.java`
- Create: `src/main/java/com/aicsassistant/common/config/SwaggerConfig.java`
- Create: `src/main/java/com/aicsassistant/common/exception/ApiException.java`
- Create: `src/main/java/com/aicsassistant/common/exception/GlobalExceptionHandler.java`
- Create: `src/main/java/com/aicsassistant/common/response/ApiErrorResponse.java`
- Create: `src/main/java/com/aicsassistant/common/response/ApiSuccessResponse.java`

### Inquiry

- Create: `src/main/java/com/aicsassistant/inquiry/domain/Inquiry.java`
- Create: `src/main/java/com/aicsassistant/inquiry/domain/InquiryCategory.java`
- Create: `src/main/java/com/aicsassistant/inquiry/domain/InquiryStatus.java`
- Create: `src/main/java/com/aicsassistant/inquiry/domain/UrgencyLevel.java`
- Create: `src/main/java/com/aicsassistant/inquiry/infra/InquiryRepository.java`
- Create: `src/main/java/com/aicsassistant/inquiry/dto/CreateInquiryRequest.java`
- Create: `src/main/java/com/aicsassistant/inquiry/dto/InquiryListResponse.java`
- Create: `src/main/java/com/aicsassistant/inquiry/dto/InquiryDetailResponse.java`
- Create: `src/main/java/com/aicsassistant/inquiry/dto/ReviewInquiryRequest.java`
- Create: `src/main/java/com/aicsassistant/inquiry/application/InquiryService.java`
- Create: `src/main/java/com/aicsassistant/inquiry/application/ReviewService.java`
- Create: `src/main/java/com/aicsassistant/inquiry/api/InquiryController.java`
- Create: `src/test/java/com/aicsassistant/inquiry/infra/InquiryRepositoryTest.java`
- Create: `src/test/java/com/aicsassistant/inquiry/application/InquiryServiceTest.java`
- Create: `src/test/java/com/aicsassistant/inquiry/application/ReviewServiceTest.java`
- Create: `src/test/java/com/aicsassistant/inquiry/api/InquiryControllerTest.java`

### Manual

- Create: `src/main/java/com/aicsassistant/manual/domain/ManualDocument.java`
- Create: `src/main/java/com/aicsassistant/manual/domain/ManualChunk.java`
- Create: `src/main/java/com/aicsassistant/manual/infra/ManualDocumentRepository.java`
- Create: `src/main/java/com/aicsassistant/manual/infra/ManualChunkJdbcRepository.java`
- Create: `src/main/java/com/aicsassistant/manual/dto/CreateManualDocumentRequest.java`
- Create: `src/main/java/com/aicsassistant/manual/dto/UpdateManualDocumentRequest.java`
- Create: `src/main/java/com/aicsassistant/manual/dto/ManualDocumentResponse.java`
- Create: `src/main/java/com/aicsassistant/manual/dto/ManualChunkResponse.java`
- Create: `src/main/java/com/aicsassistant/manual/application/ManualChunker.java`
- Create: `src/main/java/com/aicsassistant/manual/application/ManualService.java`
- Create: `src/main/java/com/aicsassistant/manual/api/ManualController.java`
- Create: `src/test/java/com/aicsassistant/manual/application/ManualChunkerTest.java`
- Create: `src/test/java/com/aicsassistant/manual/application/ManualServiceTest.java`
- Create: `src/test/java/com/aicsassistant/manual/api/ManualControllerTest.java`

### Analysis

- Create: `src/main/java/com/aicsassistant/analysis/domain/InquiryAnalysisLog.java`
- Create: `src/main/java/com/aicsassistant/analysis/domain/AnalysisStatus.java`
- Create: `src/main/java/com/aicsassistant/analysis/infra/InquiryAnalysisLogRepository.java`
- Create: `src/main/java/com/aicsassistant/analysis/dto/InquiryAnalysisResponse.java`
- Create: `src/main/java/com/aicsassistant/analysis/dto/CategoryResultDto.java`
- Create: `src/main/java/com/aicsassistant/analysis/dto/UrgencyResultDto.java`
- Create: `src/main/java/com/aicsassistant/analysis/dto/RetrievedManualChunkDto.java`
- Create: `src/main/java/com/aicsassistant/analysis/dto/DraftAnswerDto.java`
- Create: `src/main/java/com/aicsassistant/analysis/dto/InquiryAnalysisLogResponse.java`
- Create: `src/main/java/com/aicsassistant/analysis/application/PromptFactory.java`
- Create: `src/main/java/com/aicsassistant/analysis/application/InquiryClassifier.java`
- Create: `src/main/java/com/aicsassistant/analysis/application/UrgencyClassifier.java`
- Create: `src/main/java/com/aicsassistant/analysis/application/ManualRetrievalService.java`
- Create: `src/main/java/com/aicsassistant/analysis/application/DraftAnswerService.java`
- Create: `src/main/java/com/aicsassistant/analysis/application/AnalysisLogService.java`
- Create: `src/main/java/com/aicsassistant/analysis/application/InquiryAnalysisService.java`
- Create: `src/main/java/com/aicsassistant/analysis/infra/llm/LlmClient.java`
- Create: `src/main/java/com/aicsassistant/analysis/infra/llm/EmbeddingClient.java`
- Create: `src/main/java/com/aicsassistant/analysis/infra/llm/OpenAiClient.java`
- Create: `src/main/java/com/aicsassistant/analysis/infra/vector/PgvectorRowMapper.java`
- Create: `src/main/java/com/aicsassistant/analysis/api/InquiryAnalysisController.java`
- Create: `src/test/java/com/aicsassistant/analysis/application/InquiryAnalysisServiceTest.java`
- Create: `src/test/java/com/aicsassistant/analysis/application/PromptFactoryTest.java`
- Create: `src/test/java/com/aicsassistant/analysis/api/InquiryAnalysisControllerTest.java`

### UI and bootstrap data

- Create: `src/main/java/com/aicsassistant/ui/controller/CounselorViewController.java`
- Create: `src/main/java/com/aicsassistant/ui/viewmodel/InquiryDetailViewModel.java`
- Create: `src/main/java/com/aicsassistant/common/bootstrap/LocalSeedDataInitializer.java`
- Create: `src/main/resources/templates/inquiries/list.html`
- Create: `src/main/resources/templates/inquiries/create.html`
- Create: `src/main/resources/templates/inquiries/detail.html`
- Create: `src/main/resources/templates/manuals/list.html`
- Create: `src/main/resources/templates/manuals/detail.html`
- Create: `src/main/resources/static/app.css`
- Create: `src/main/resources/schema.sql`
- Create: `README.md`
- Create: `src/test/java/com/aicsassistant/ui/controller/CounselorViewControllerTest.java`

## Task 1: Bootstrap the Spring project

**Files:**
- Create: `settings.gradle`
- Create: `build.gradle`
- Create: `src/main/java/com/aicsassistant/AiCsAssistantApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/test/java/com/aicsassistant/AiCsAssistantApplicationTests.java`
- Create: `src/test/resources/application-test.yml`

- [ ] **Step 1: Write the failing context-load test**

```java
@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
})
class AiCsAssistantApplicationTests {
    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.aicsassistant.AiCsAssistantApplicationTests" -v`  
Expected: FAIL because Gradle project and application class do not exist yet

- [ ] **Step 3: Add minimal bootstrap files**

```gradle
rootProject.name = 'ai-cs-assistant'
```

```gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-thymeleaf'
    implementation 'org.springframework.boot:spring-boot-starter-webflux'
    implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0'
    implementation 'org.postgresql:postgresql:42.7.4'
    implementation 'com.pgvector:pgvector:0.1.6'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.boot:spring-boot-testcontainers'
    testImplementation 'org.testcontainers:postgresql'
}
```

```java
@SpringBootApplication
public class AiCsAssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiCsAssistantApplication.class, args);
    }
}
```

```yaml
spring:
  main:
    lazy-initialization: true
```

- [ ] **Step 4: Run test to verify bootstrap passes**

Run: `./gradlew test --tests "com.aicsassistant.AiCsAssistantApplicationTests" -v`  
Expected: PASS

- [ ] **Step 5: Commit bootstrap**

```bash
git add settings.gradle build.gradle src/main/java/com/aicsassistant/AiCsAssistantApplication.java src/main/resources/application.yml src/test/java/com/aicsassistant/AiCsAssistantApplicationTests.java src/test/resources/application-test.yml
git commit -m "chore: bootstrap spring application"
```

## Task 2: Implement common config and error handling

**Files:**
- Create: `src/main/java/com/aicsassistant/common/config/AiProperties.java`
- Create: `src/main/java/com/aicsassistant/common/config/WebClientConfig.java`
- Create: `src/main/java/com/aicsassistant/common/config/SwaggerConfig.java`
- Create: `src/main/java/com/aicsassistant/common/exception/ApiException.java`
- Create: `src/main/java/com/aicsassistant/common/exception/GlobalExceptionHandler.java`
- Create: `src/main/java/com/aicsassistant/common/response/ApiErrorResponse.java`
- Create: `src/main/java/com/aicsassistant/common/response/ApiSuccessResponse.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/aicsassistant/AiCsAssistantApplicationTests.java`

- [ ] **Step 1: Extend the context-load test to bind AI properties**

```java
@Autowired
private AiProperties aiProperties;

@Test
void aiPropertiesBind() {
    assertThat(aiProperties.getProvider()).isEqualTo("openai");
}
```

- [ ] **Step 2: Run test to verify property binding fails**

Run: `./gradlew test --tests "com.aicsassistant.AiCsAssistantApplicationTests.aiPropertiesBind" -v`  
Expected: FAIL because `AiProperties` is missing

- [ ] **Step 3: Add config and global API error types**

```java
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {
    private String provider;
    private String model;
    private String apiKey;
    private String embeddingModel;
}
```

```java
@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrorResponse> handle(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
            .body(new ApiErrorResponse(ex.getCode(), ex.getMessage(), LocalDateTime.now()));
    }
}
```

- [ ] **Step 4: Re-run bootstrap tests**

Run: `./gradlew test --tests "com.aicsassistant.AiCsAssistantApplicationTests" -v`  
Expected: PASS

- [ ] **Step 5: Commit common infrastructure**

```bash
git add src/main/java/com/aicsassistant/common src/main/resources/application.yml src/test/java/com/aicsassistant/AiCsAssistantApplicationTests.java
git commit -m "feat: add common config and exception handling"
```

## Task 3: Add inquiry and analysis persistence model

**Files:**
- Create: `src/main/java/com/aicsassistant/inquiry/domain/InquiryCategory.java`
- Create: `src/main/java/com/aicsassistant/inquiry/domain/UrgencyLevel.java`
- Create: `src/main/java/com/aicsassistant/inquiry/domain/InquiryStatus.java`
- Create: `src/main/java/com/aicsassistant/inquiry/domain/Inquiry.java`
- Create: `src/main/java/com/aicsassistant/manual/domain/ManualDocument.java`
- Create: `src/main/java/com/aicsassistant/manual/domain/ManualChunk.java`
- Create: `src/main/java/com/aicsassistant/analysis/domain/AnalysisStatus.java`
- Create: `src/main/java/com/aicsassistant/analysis/domain/InquiryAnalysisLog.java`
- Create: `src/main/java/com/aicsassistant/inquiry/infra/InquiryRepository.java`
- Create: `src/main/java/com/aicsassistant/manual/infra/ManualDocumentRepository.java`
- Create: `src/main/java/com/aicsassistant/analysis/infra/InquiryAnalysisLogRepository.java`
- Create: `src/main/resources/schema.sql`
- Create: `src/test/java/com/aicsassistant/inquiry/infra/InquiryRepositoryTest.java`
- Create: `src/test/java/com/aicsassistant/support/PostgresVectorIntegrationTest.java`

- [ ] **Step 1: Write a repository-level state-default test**

```java
@DataJpaTest
class InquiryRepositoryTest extends PostgresVectorIntegrationTest {
    @Autowired InquiryRepository inquiryRepository;

    @Test
    void savesInquiryWithNewStatus() {
        Inquiry inquiry = Inquiry.create("cust-001", "예약 변경", "이번 주 토요일로 바꿔주세요.");
        Inquiry saved = inquiryRepository.save(inquiry);
        assertThat(saved.getStatus()).isEqualTo(InquiryStatus.NEW);
    }
}
```

- [ ] **Step 2: Run the focused test and confirm it fails**

Run: `./gradlew test --tests "*InquiryRepositoryTest" -v`  
Expected: FAIL because entities, schema, and repository are missing

- [ ] **Step 3: Implement enums, entities, repositories, and schema**

```java
@Entity
public class Inquiry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING)
    private InquiryStatus status;

    public static Inquiry create(String customerIdentifier, String title, String content) {
        Inquiry inquiry = new Inquiry();
        inquiry.customerIdentifier = customerIdentifier;
        inquiry.title = title;
        inquiry.content = content;
        inquiry.status = InquiryStatus.NEW;
        return inquiry;
    }
}
```

```sql
create extension if not exists vector;

create table if not exists inquiry (
  id bigserial primary key,
  customer_identifier varchar(100),
  title varchar(255) not null,
  content text not null,
  category varchar(50),
  urgency varchar(20),
  status varchar(30) not null,
  ai_draft_answer text,
  final_answer text,
  review_memo text,
  reviewed_by varchar(100),
  created_at timestamp not null,
  updated_at timestamp not null
);

create table if not exists manual_document (
  id bigserial primary key,
  title varchar(255) not null,
  category varchar(50),
  content text not null,
  version integer not null default 1,
  active boolean not null default true,
  created_at timestamp not null,
  updated_at timestamp not null
);

create table if not exists manual_chunk (
  id bigserial primary key,
  manual_document_id bigint not null,
  chunk_index integer not null,
  document_version integer not null,
  content text not null,
  token_count integer,
  embedding vector(1536),
  active boolean not null,
  created_at timestamp not null
);

create table if not exists inquiry_analysis_log (
  id bigserial primary key,
  inquiry_id bigint not null,
  request_snapshot text,
  classified_category varchar(50),
  classified_urgency varchar(20),
  retrieved_chunk_ids text,
  generated_draft text,
  model_name varchar(100),
  prompt_version varchar(50),
  analysis_status varchar(30) not null,
  error_message text,
  latency_ms integer,
  created_at timestamp not null
);

create index if not exists idx_manual_chunk_document_id on manual_chunk(manual_document_id);
create index if not exists idx_inquiry_analysis_log_inquiry_id on inquiry_analysis_log(inquiry_id);
```

```java
@Testcontainers
abstract class PostgresVectorIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
```

- [ ] **Step 4: Re-run repository test**

Run: `./gradlew test --tests "*InquiryRepositoryTest" -v`  
Expected: PASS

- [ ] **Step 5: Commit persistence model**

```bash
git add src/main/java/com/aicsassistant/inquiry/domain src/main/java/com/aicsassistant/manual/domain src/main/java/com/aicsassistant/analysis/domain src/main/java/com/aicsassistant/inquiry/infra src/main/java/com/aicsassistant/manual/infra src/main/java/com/aicsassistant/analysis/infra src/main/resources/schema.sql src/test/java/com/aicsassistant/inquiry/infra/InquiryRepositoryTest.java src/test/java/com/aicsassistant/support/PostgresVectorIntegrationTest.java
git commit -m "feat: add core persistence model"
```

## Task 4: Build manual CRUD and chunk generation

**Files:**
- Create: `src/main/java/com/aicsassistant/manual/dto/CreateManualDocumentRequest.java`
- Create: `src/main/java/com/aicsassistant/manual/dto/UpdateManualDocumentRequest.java`
- Create: `src/main/java/com/aicsassistant/manual/dto/ManualDocumentResponse.java`
- Create: `src/main/java/com/aicsassistant/manual/dto/ManualChunkResponse.java`
- Create: `src/main/java/com/aicsassistant/manual/application/ManualChunker.java`
- Create: `src/main/java/com/aicsassistant/manual/application/ManualService.java`
- Create: `src/main/java/com/aicsassistant/manual/api/ManualController.java`
- Create: `src/main/java/com/aicsassistant/manual/infra/ManualChunkJdbcRepository.java`
- Create: `src/test/java/com/aicsassistant/manual/application/ManualChunkerTest.java`
- Create: `src/test/java/com/aicsassistant/manual/application/ManualServiceTest.java`
- Create: `src/test/java/com/aicsassistant/manual/api/ManualControllerTest.java`

- [ ] **Step 1: Write a failing chunker unit test**

```java
class ManualChunkerTest {
    @Test
    void splitsManualIntoStableChunks() {
        ManualChunker chunker = new ManualChunker(500, 100);
        List<String> chunks = chunker.chunk("A".repeat(1200));
        assertThat(chunks).hasSizeGreaterThan(1);
    }
}
```

Also add a `ManualServiceTest` that verifies deactivating a manual marks the document inactive and excludes its chunks from active retrieval.

- [ ] **Step 2: Run the chunker test and confirm failure**

Run: `./gradlew test --tests "com.aicsassistant.manual.application.ManualChunkerTest" -v`  
Expected: FAIL because chunker does not exist yet

- [ ] **Step 3: Implement manual DTOs, chunker, JDBC chunk repository, service, and controller**

```java
public class ManualChunker {
    public List<String> chunk(String content) {
        // split by window size with overlap, trimming blank output
    }
}
```

```java
public void replaceActiveChunks(Long documentId, int version, List<float[]> embeddings, List<String> contents) {
    jdbcTemplate.update("update manual_chunk set active = false where manual_document_id = ?", documentId);
    // insert new active chunks with document_version = version
}
```

```java
public void deactivateManual(Long documentId) {
    manualDocumentRepository.findById(documentId).orElseThrow(...).deactivate();
    jdbcTemplate.update("update manual_chunk set active = false where manual_document_id = ?", documentId);
}
```

```sql
select mc.*
from manual_chunk mc
join manual_document md on md.id = mc.manual_document_id
where md.active = true
  and mc.active = true
order by mc.embedding <=> ?
limit ?
```

- [ ] **Step 4: Run manual tests**

Run: `./gradlew test --tests "com.aicsassistant.manual.*" -v`  
Expected: PASS

- [ ] **Step 5: Commit manual module**

```bash
git add src/main/java/com/aicsassistant/manual src/test/java/com/aicsassistant/manual
git commit -m "feat: add manual CRUD and chunk generation"
```

## Task 5: Build inquiry CRUD and review state rules

**Files:**
- Create: `src/main/java/com/aicsassistant/inquiry/dto/CreateInquiryRequest.java`
- Create: `src/main/java/com/aicsassistant/inquiry/dto/InquiryListResponse.java`
- Create: `src/main/java/com/aicsassistant/inquiry/dto/InquiryDetailResponse.java`
- Create: `src/main/java/com/aicsassistant/inquiry/dto/ReviewInquiryRequest.java`
- Create: `src/main/java/com/aicsassistant/inquiry/application/InquiryService.java`
- Create: `src/main/java/com/aicsassistant/inquiry/application/ReviewService.java`
- Create: `src/main/java/com/aicsassistant/inquiry/api/InquiryController.java`
- Create: `src/test/java/com/aicsassistant/inquiry/application/InquiryServiceTest.java`
- Create: `src/test/java/com/aicsassistant/inquiry/application/ReviewServiceTest.java`
- Create: `src/test/java/com/aicsassistant/inquiry/api/InquiryControllerTest.java`

- [ ] **Step 1: Write a failing review state test**

```java
class ReviewServiceTest {
    @Autowired InquiryRepository inquiryRepository;
    @Autowired ReviewService reviewService;
    @Autowired InquiryService inquiryService;

    @Test
    void reviewMovesInquiryToReviewedAndPersistsAnswer() {
        Inquiry inquiry = inquiryRepository.save(Inquiry.create("cust-001", "문의", "환불 가능한가요?"));
        inquiry.markAiProcessed();
        Inquiry saved = inquiryRepository.save(inquiry);
        reviewService.confirm(saved.getId(), new ReviewInquiryRequest("환불 규정상 ...", "정책 근거 확인", "mimi"));
        Inquiry reloaded = inquiryRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(InquiryStatus.REVIEWED);
        assertThat(reloaded.getReviewedBy()).isEqualTo("mimi");
    }

    @Test
    void closeRejectsNewAndAiProcessedInquiries() {
        Inquiry newInquiry = inquiryRepository.save(Inquiry.create("cust-002", "문의", "지금 취소 가능한가요?"));
        Inquiry aiProcessed = inquiryRepository.save(Inquiry.create("cust-004", "문의", "예약 변경 가능한가요?"));
        aiProcessed.markAiProcessed();
        inquiryRepository.save(aiProcessed);

        assertThatThrownBy(() -> inquiryService.close(newInquiry.getId()))
            .isInstanceOf(InvalidInquiryStateException.class);
        assertThatThrownBy(() -> inquiryService.close(aiProcessed.getId()))
            .isInstanceOf(InvalidInquiryStateException.class);
    }
}
```

- [ ] **Step 2: Run the review test and confirm failure**

Run: `./gradlew test --tests "com.aicsassistant.inquiry.application.ReviewServiceTest" -v`  
Expected: FAIL because review service and DTO are missing

- [ ] **Step 3: Implement inquiry services, DTOs, filters, and controller endpoints**

```java
public InquiryDetailResponse getInquiry(Long id) {
    Inquiry inquiry = inquiryRepository.findById(id).orElseThrow(...);
    List<InquiryAnalysisLogResponse> logs = analysisLogRepository.findByInquiryIdOrderByCreatedAtDesc(id)
        .stream().map(InquiryAnalysisLogResponse::from).toList();
    return InquiryDetailResponse.from(inquiry, logs);
}
```

```java
public void confirm(Long inquiryId, ReviewInquiryRequest request) {
    Inquiry inquiry = inquiryRepository.findById(inquiryId).orElseThrow(...);
    inquiry.confirmReview(request.finalAnswer(), request.reviewMemo(), request.confirmedBy());
}
```

```java
@PostMapping("/api/inquiries/{id}/close")
public ApiSuccessResponse<Void> close(@PathVariable Long id) {
    inquiryService.close(id);
    return ApiSuccessResponse.empty();
}
```

```java
public void close(Long inquiryId) {
    Inquiry inquiry = inquiryRepository.findById(inquiryId).orElseThrow(...);
    if (inquiry.getStatus() != InquiryStatus.REVIEWED) {
        throw new InvalidInquiryStateException("Only REVIEWED inquiries can be closed");
    }
    inquiry.close();
}
```

- [ ] **Step 4: Run inquiry service and controller tests**

Run: `./gradlew test --tests "com.aicsassistant.inquiry.*" -v`  
Expected: PASS

- [ ] **Step 5: Commit inquiry module**

```bash
git add src/main/java/com/aicsassistant/inquiry src/test/java/com/aicsassistant/inquiry
git commit -m "feat: add inquiry CRUD and review flow"
```

## Task 6: Build LLM clients, prompt factory, retrieval, and inquiry analysis orchestration

**Files:**
- Create: `src/main/java/com/aicsassistant/analysis/dto/InquiryAnalysisResponse.java`
- Create: `src/main/java/com/aicsassistant/analysis/dto/CategoryResultDto.java`
- Create: `src/main/java/com/aicsassistant/analysis/dto/UrgencyResultDto.java`
- Create: `src/main/java/com/aicsassistant/analysis/dto/RetrievedManualChunkDto.java`
- Create: `src/main/java/com/aicsassistant/analysis/dto/DraftAnswerDto.java`
- Create: `src/main/java/com/aicsassistant/analysis/dto/InquiryAnalysisLogResponse.java`
- Create: `src/main/java/com/aicsassistant/analysis/application/PromptFactory.java`
- Create: `src/main/java/com/aicsassistant/analysis/application/InquiryClassifier.java`
- Create: `src/main/java/com/aicsassistant/analysis/application/UrgencyClassifier.java`
- Create: `src/main/java/com/aicsassistant/analysis/application/ManualRetrievalService.java`
- Create: `src/main/java/com/aicsassistant/analysis/application/DraftAnswerService.java`
- Create: `src/main/java/com/aicsassistant/analysis/application/AnalysisLogService.java`
- Create: `src/main/java/com/aicsassistant/analysis/application/InquiryAnalysisService.java`
- Create: `src/main/java/com/aicsassistant/analysis/infra/llm/LlmClient.java`
- Create: `src/main/java/com/aicsassistant/analysis/infra/llm/EmbeddingClient.java`
- Create: `src/main/java/com/aicsassistant/analysis/infra/llm/OpenAiClient.java`
- Create: `src/main/java/com/aicsassistant/analysis/infra/vector/PgvectorRowMapper.java`
- Create: `src/main/java/com/aicsassistant/analysis/api/InquiryAnalysisController.java`
- Create: `src/test/java/com/aicsassistant/analysis/application/PromptFactoryTest.java`
- Create: `src/test/java/com/aicsassistant/analysis/application/InquiryAnalysisServiceTest.java`
- Create: `src/test/java/com/aicsassistant/analysis/api/InquiryAnalysisControllerTest.java`

- [ ] **Step 1: Write a failing orchestration test with fake clients**

```java
class InquiryAnalysisServiceTest {
    @Test
    void analyzeUpdatesInquiryAndWritesLog() {
        FakeLlmClient llm = new FakeLlmClient(
            "{\"category\":\"REFUND\",\"urgency\":\"MEDIUM\",\"reason\":\"refund request\",\"needsHumanReview\":true,\"needsEscalation\":false,\"medicalRiskFlag\":false}",
            "{\"answer\":\"안녕하세요. 환불 규정에 따라 ...\",\"internalNote\":\"정책 근거 확인 완료\",\"usedChunkIds\":[1,2]}"
        );
        FakeEmbeddingClient embeddingClient = new FakeEmbeddingClient(new float[] {0.1f, 0.2f});
        InquiryAnalysisResponse response = service.analyze(inquiryId);
        assertThat(response.category().value()).isEqualTo(InquiryCategory.REFUND.name());
        assertThat(response.category().reason()).isEqualTo("refund request");
        assertThat(response.category().needsHumanReview()).isTrue();
        assertThat(response.draft().internalNote()).contains("정책 근거");
        assertThat(response.draft().usedChunkIds()).containsExactly(1L, 2L);
        assertThat(response.retrievedChunks()).isNotEmpty();
    }

    @Test
    void analyzeFailureStillWritesErrorLog() {
        FakeLlmClient llm = new FakeLlmClient(new RuntimeException("upstream timeout"));
        assertThatThrownBy(() -> service.analyze(inquiryId))
            .isInstanceOf(AiAnalysisFailedException.class);
        assertThat(logRepository.findByInquiryIdOrderByCreatedAtDesc(inquiryId).get(0).getAnalysisStatus())
            .isEqualTo(AnalysisStatus.FAILURE);
    }

    @Test
    void analyzeRejectsClosedInquiry() {
        Inquiry saved = inquiryRepository.save(Inquiry.create("cust-003", "문의", "멤버십 사용 문의"));
        saved.markReviewed("답변", null, "mimi");
        saved.close();
        inquiryRepository.save(saved);
        assertThatThrownBy(() -> service.analyze(saved.getId()))
            .isInstanceOf(InvalidInquiryStateException.class);
    }
}
```

- [ ] **Step 2: Run focused analysis tests**

Run: `./gradlew test --tests "com.aicsassistant.analysis.application.InquiryAnalysisServiceTest" -v`  
Expected: FAIL because orchestration and fakeable interfaces do not exist

- [ ] **Step 3: Implement prompt factory, LLM interfaces, retrieval, orchestration, and analysis endpoint**

```java
public InquiryAnalysisResponse analyze(Long inquiryId) {
    Inquiry inquiry = inquiryRepository.findById(inquiryId).orElseThrow(...);
    if (inquiry.getStatus() == InquiryStatus.CLOSED) {
        throw new InvalidInquiryStateException("Closed inquiry cannot be analyzed");
    }
    try {
        AnalysisResult result = inquiryClassifier.classify(inquiry.getContent());
        UrgencyResult urgency = urgencyClassifier.classify(inquiry.getContent());
        List<RetrievedManualChunkDto> chunks = manualRetrievalService.retrieve(inquiry.getContent());
        DraftAnswerDto draft = draftAnswerService.generate(inquiry, result, urgency, chunks);
        inquiry.applyAnalysis(result.category(), urgency.level(), draft.answer());
        analysisLogService.logSuccess(inquiry, result, urgency, chunks, draft);
        return InquiryAnalysisResponse.of(inquiry, result, urgency, chunks, draft);
    } catch (RuntimeException ex) {
        analysisLogService.logFailure(inquiry, ex);
        throw new AiAnalysisFailedException("AI analysis failed", ex);
    }
}
```

```java
public record CategoryResultDto(
    String value,
    String reason,
    boolean needsHumanReview,
    boolean needsEscalation,
    boolean medicalRiskFlag
) {}
```

```java
public record DraftAnswerDto(
    String answer,
    String internalNote,
    List<Long> usedChunkIds
) {}
```

```java
String analysisPrompt = promptFactory.analysisPrompt(inquiryContent);
String draftPrompt = promptFactory.draftPrompt(inquiry, analysis, retrievedChunks);
```

Persist these fields into `InquiryAnalysisLog`:

- classified category and urgency
- reason
- needsHumanReview
- needsEscalation
- medicalRiskFlag
- generated draft
- internal note
- used evidence chunk ids
- failure status and error message when exceptions occur

- [ ] **Step 4: Run analysis and API tests**

Run: `./gradlew test --tests "com.aicsassistant.analysis.*" -v`  
Expected: PASS

- [ ] **Step 5: Commit analysis pipeline**

```bash
git add src/main/java/com/aicsassistant/analysis src/test/java/com/aicsassistant/analysis
git commit -m "feat: add inquiry analysis and rag pipeline"
```

## Task 7: Build minimal counselor UI

**Files:**
- Create: `src/main/java/com/aicsassistant/ui/controller/CounselorViewController.java`
- Create: `src/main/java/com/aicsassistant/ui/viewmodel/InquiryDetailViewModel.java`
- Create: `src/main/resources/templates/inquiries/list.html`
- Create: `src/main/resources/templates/inquiries/create.html`
- Create: `src/main/resources/templates/inquiries/detail.html`
- Create: `src/main/resources/templates/manuals/list.html`
- Create: `src/main/resources/templates/manuals/detail.html`
- Create: `src/main/resources/static/app.css`
- Create: `src/test/java/com/aicsassistant/ui/controller/CounselorViewControllerTest.java`

- [ ] **Step 1: Write a failing MVC view test**

```java
@WebMvcTest(CounselorViewController.class)
class CounselorViewControllerTest {
    @Test
    void rendersInquiryDetailPage() throws Exception {
        mvc.perform(get("/ui/inquiries/1"))
            .andExpect(status().isOk())
            .andExpect(view().name("inquiries/detail"));
    }
}
```

- [ ] **Step 2: Run the view test and confirm failure**

Run: `./gradlew test --tests "*CounselorViewControllerTest" -v`  
Expected: FAIL because UI controller and templates do not exist

- [ ] **Step 3: Implement minimal pages and controller bindings**

```html
<section>
  <h2>AI 분석 결과</h2>
  <p th:text="${detail.category}">REFUND</p>
  <p th:text="${detail.urgency}">MEDIUM</p>
  <form th:action="@{'/api/inquiries/' + ${detail.id} + '/analyze'}" method="post">
    <button type="submit">AI 분석</button>
  </form>
  <form th:action="@{'/api/inquiries/' + ${detail.id} + '/review'}" method="post">
    <textarea name="finalAnswer" th:text="${detail.finalAnswer}"></textarea>
    <input type="text" name="confirmedBy" placeholder="상담사 이름" />
    <textarea name="reviewMemo" th:text="${detail.reviewMemo}"></textarea>
    <button type="submit">최종 확정</button>
  </form>
  <form th:action="@{'/api/inquiries/' + ${detail.id} + '/close'}" method="post">
    <button type="submit">종료</button>
  </form>
</section>
```

```java
@GetMapping("/ui/inquiries")
String inquiryList(Model model) {
    model.addAttribute("items", inquiryService.getInquiries(null, null, null));
    return "inquiries/list";
}

@GetMapping("/ui/inquiries/new")
String inquiryCreateForm() {
    return "inquiries/create";
}

@GetMapping("/ui/inquiries/{id}")
String inquiryDetail(@PathVariable Long id, Model model) {
    model.addAttribute("detail", InquiryDetailViewModel.from(inquiryService.getInquiry(id)));
    return "inquiries/detail";
}

@GetMapping("/ui/manuals")
String manualList(Model model) {
    model.addAttribute("manuals", manualService.getManuals());
    return "manuals/list";
}

@GetMapping("/ui/manuals/{id}")
String manualDetail(@PathVariable Long id, Model model) {
    model.addAttribute("manual", manualService.getManual(id));
    return "manuals/detail";
}

@PostMapping("/ui/manuals")
String createManual(CreateManualDocumentRequest request) {
    ManualDocumentResponse created = manualService.create(request);
    return "redirect:/ui/manuals/" + created.id();
}

@PostMapping("/ui/manuals/{id}")
String updateManual(@PathVariable Long id, UpdateManualDocumentRequest request) {
    manualService.update(id, request);
    return "redirect:/ui/manuals/" + id;
}
```

Implement the pages so that:

- `inquiries/list.html` links to detail and new inquiry registration
- `inquiries/create.html` posts a new inquiry
- `inquiries/detail.html` shows retrieved evidence and the three counselor actions
- `manuals/list.html` contains a create form posting to `/ui/manuals` and links to detail
- `manuals/detail.html` contains an update form posting to `/ui/manuals/{id}` and an active/inactive control

- [ ] **Step 4: Run UI test and app smoke test**

Run: `./gradlew test --tests "*CounselorViewControllerTest" -v`  
Expected: PASS

Run: `./gradlew bootRun --args='--spring.profiles.active=local'`  
Expected: `/ui/inquiries`, `/ui/inquiries/new`, `/ui/inquiries/{id}`, `/ui/manuals`, and `/ui/manuals/{id}` render without server errors

- [ ] **Step 5: Commit minimal UI**

```bash
git add src/main/java/com/aicsassistant/ui src/main/resources/templates src/main/resources/static
git commit -m "feat: add minimal counselor review UI"
```

## Task 8: Add local seed data, README, and end-to-end verification

**Files:**
- Create: `src/main/java/com/aicsassistant/common/bootstrap/LocalSeedDataInitializer.java`
- Create: `README.md`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Write a failing seed-data bootstrap test**

```java
@SpringBootTest
@ActiveProfiles("local")
class LocalSeedDataInitializerTest {
    @Autowired InquiryRepository inquiryRepository;
    @Autowired ManualDocumentRepository manualDocumentRepository;

    @Test
    void insertsDemoRecords() {
        assertThat(inquiryRepository.count()).isGreaterThanOrEqualTo(8);
        assertThat(manualDocumentRepository.count()).isGreaterThanOrEqualTo(5);
    }
}
```

- [ ] **Step 2: Run the seed-data test and confirm failure**

Run: `./gradlew test --tests "*LocalSeedDataInitializerTest" -v`  
Expected: FAIL because local bootstrap data does not exist

- [ ] **Step 3: Implement local seed data and runbook docs**

```java
@Profile("local")
@Component
class LocalSeedDataInitializer implements CommandLineRunner {
    @Override
    public void run(String... args) {
        // create 12 inquiries, 8 manuals, generate active chunks and embeddings when empty
    }
}
```

```md
## Run locally
1. Start PostgreSQL with pgvector
2. Export `OPENAI_API_KEY`
3. Run `./gradlew bootRun --args='--spring.profiles.active=local'`
```

- [ ] **Step 4: Run full verification**

Run: `./gradlew test`  
Expected: PASS

Run: `./gradlew bootRun --args='--spring.profiles.active=local'`  
Expected: application starts, local seed data loads, Swagger and `/ui/inquiries` render

- [ ] **Step 5: Commit seed data and docs**

```bash
git add src/main/java/com/aicsassistant/common/bootstrap README.md src/main/resources/application.yml
git commit -m "feat: add local seed data and runbook"
```

## Notes for the Implementer

- Keep `InquiryAnalysisService` as the only workflow orchestrator
- Do not add authentication in the MVP
- Use human-review guardrails in prompts and UI labels
- For chat model switching, keep embeddings fixed to OpenAI `text-embedding-3-small`
- Prefer stubs/fakes in analysis unit tests rather than live API calls
- Use `ManualChunkJdbcRepository` for vector insert/search instead of forcing full JPA mapping for pgvector
