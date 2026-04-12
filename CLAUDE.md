# CLAUDE.md — AI CS Assistant

Claude Code가 이 프로젝트에서 작업할 때 반드시 따라야 할 규칙과 컨텍스트입니다.

---

## 프로젝트 개요

고객 CS 문의를 AI가 자동 분류·답변하고, 처리 불가 시 상담사에게 에스컬레이션하는 시스템.

- **백엔드**: Spring Boot 3.5 / Java 17 / JPA / JdbcTemplate
- **AI 파이프라인**: ReAct 에이전트 (직접 구현) + RAG (pgvector 코사인 유사도)
- **LLM**: OpenAI API (gpt-4.1-mini)
- **DB**: PostgreSQL + pgvector
- **배포**: Railway (Docker 멀티스테이지 빌드)
- **뷰**: Thymeleaf (어드민/유저 UI)

---

## 패키지 구조

```
com.aicsassistant
├── analysis/       # AI 분석 파이프라인 (에이전트, RAG, 로그)
│   ├── agent/      # ReAct 에이전트 루프, 툴 정의
│   ├── application/# 분석 서비스, 프롬프트 팩토리, 분류기
│   ├── domain/     # InquiryAnalysisLog
│   └── infra/      # 레포지터리, llm/(LlmClient, EmbeddingClient), vector/(RAG 검색)
├── inquiry/        # 문의 도메인
│   ├── api/        # REST 컨트롤러
│   ├── application/# InquiryService, ReviewService
│   ├── domain/     # Inquiry, InquiryMessage, 상태/카테고리 enum
│   └── infra/      # 레포지터리
├── manual/         # 정책 문서 관리 및 임베딩
├── order/          # 주문 조회 (InMemory Mock)
├── ui/             # Thymeleaf 뷰 컨트롤러
│   ├── application/# DashboardService (집계 쿼리)
│   ├── controller/ # CounselorViewController, UserViewController
│   └── viewmodel/  # ViewModel, DTO (뷰 전달용)
└── common/         # 예외, 설정, 공통 응답
```

---

## 레이어 의존성 규칙

**컨트롤러 → 서비스 → 레포지터리** 방향을 반드시 지킨다.

- 컨트롤러에서 Repository를 직접 주입하지 않는다.
- 서비스에서 다른 도메인 서비스는 참조 가능하나, 다른 도메인 레포지터리 직접 참조는 지양한다.
- JdbcTemplate은 복잡한 집계 쿼리(대시보드 등)에 한해 서비스 레이어에서만 사용한다.

---

## 코드 작성 규칙

- 불필요한 추상화 금지 — 단일 사용처를 위한 헬퍼/유틸 클래스 생성 지양
- `findAll()` 후 Java 스트림 필터링 금지 — 반드시 DB 쿼리(JPQL/쿼리 메서드)로 처리
- 에러 핸들링은 `ApiException` + `GlobalExceptionHandler` 패턴 사용
- Thymeleaf 템플릿에서 SpEL 람다(`v -> v`) 사용 불가 — 집계값은 컨트롤러에서 계산해서 모델로 전달

---

## AI 에이전트 설계 원칙

### ReAct 루프
- 최대 스텝: `MAX_STEPS = 8`
- 툴: `search_manual` (RAG 검색), `check_order_status` (주문 조회)
- 에이전트 스텝은 `InquiryAnalysisLog.agentSteps`에 JSON 직렬화하여 저장

### 에스컬레이션 규칙 (`PromptFactory`)
- 고객에게 추가 정보를 최대 **3회**까지 재질문 가능
- 3회 이후에도 정보 미제공 시 → `needsHumanReview: true`
- 모호한 답변("모르겠어요", "기억 안나요")은 유효한 답변으로 처리하지 않음
- 행동이 필요한 케이스(환불 처리, 계정 정지 등)는 무조건 에스컬레이션
- **절대 금지**: 고객에게 "고객센터에 연락하세요" 안내 — 시스템이 직접 상담사에게 넘긴다

### 툴 설계 원칙
- 조회 툴은 에이전트가 자유롭게 호출 가능
- 상태 변경 툴(환불 실행 등)은 에이전트 툴셋에 포함하지 않고 상담사 승인 후 실행

---

## 주요 도메인 상태

### InquiryStatus
| 상태 | 설명 |
|------|------|
| `NEW` | 접수됨, AI 분석 대기 |
| `PENDING_CUSTOMER` | AI가 추가 정보 요청, 고객 답변 대기 |
| `AI_PROCESSED` | AI 분석 완료, 상담사 검토 필요 |
| `AUTO_ANSWERED` | AI가 직접 최종 답변 전송 완료 |
| `REVIEWED` | 상담사 검토 완료 |
| `CLOSED` | 종료 |

---

## 테스트 규칙

### 작업 전
- 수정하는 클래스와 관련된 기존 테스트 파일을 먼저 확인한다.
- 기존 테스트가 현재 코드와 맞지 않으면 함께 수정한다.

### 작업 후
- 새로운 서비스 메서드나 도메인 로직을 추가했다면 해당 테스트를 작성한다.
- 버그를 수정했다면 그 케이스를 재현하는 테스트를 추가한다.

### 테스트 분류
- **단위 테스트**: 순수 비즈니스 로직 (`@ExtendWith(MockitoExtension.class)`) — DB 없이 빠르게
- **통합 테스트**: DB가 필요한 서비스 레이어 (`extends PostgresVectorIntegrationTest`) — 실제 쿼리 검증
- LLM/외부 API는 `FakeLlmClient` 패턴으로 대체한다 (`InquiryAnalysisServiceTest` 참고)
- Thymeleaf 뷰 컨트롤러는 테스트 우선순위 낮음 — 서비스/도메인 레이어에 집중

---

## 커밋/푸시 규칙

- 커밋 및 원격 푸시 전에 반드시 사용자에게 확인을 받는다.
- 커밋 메시지는 한국어로 작성한다.
