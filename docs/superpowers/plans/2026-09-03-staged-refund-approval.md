# Staged Refund + 상담사 승인 게이트 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AI 에이전트가 환불을 *제안*만 하고, 상담사가 전용 승인 표면에서 승인해야 실제로 실행되는 게이트를 만든다.

**Architecture:** 에이전트는 새 `stage_refund` 툴로 환불 제안을 올린다. 가드레일 4종은 툴이 아니라 `ToolCallInterceptor`에서 검사한다 (툴은 `ToolCallContext`를 볼 수 없기 때문). 통과한 제안은 `staged_change` 테이블에 `PENDING`으로 저장되고, 상담사가 전용 엔드포인트에서 승인하면 그때 mock 주문 상태가 바뀌고 고객에게 알림이 저장된다. 문의 상태 머신(`InquiryStatus`)은 건드리지 않는다.

**Tech Stack:** Spring Boot 3.5 / Java 17 / JPA / PostgreSQL / Thymeleaf / JUnit 5 + AssertJ + Mockito / Testcontainers (pgvector)

**Spec:** `docs/superpowers/specs/2026-09-03-staged-refund-approval-design.md`

## Global Constraints

- 레이어 방향 준수: 컨트롤러 → 서비스 → 레포지터리. 컨트롤러에 Repository 직접 주입 금지.
- `findAll()` 후 Java 스트림 필터링 금지 — DB 쿼리 메서드로 처리.
- 에러는 `ApiException` + `GlobalExceptionHandler` 패턴.
- Thymeleaf 템플릿에서 SpEL 람다(`v -> v`) 사용 불가.
- 단일 사용처를 위한 헬퍼/유틸 클래스 생성 금지.
- 단위 테스트는 `@ExtendWith(MockitoExtension.class)`, 통합 테스트는 `extends PostgresVectorIntegrationTest` + `@SpringBootTest`.
- 커밋 메시지는 한국어. 커밋 전 사용자 확인 필요 (CLAUDE.md).
- 금액 단위는 KRW `int` — `OrderInfo.amount`와 동일 타입.
- 거부 대상 주문 상태 문자열(정확히 이 값들): `취소완료`, `취소처리중`, `반품완료`.
- staged change 상태값: `PENDING` / `APPROVED` / `REJECTED`.
- `change_type` 값은 현재 `REFUND` 하나.

---

## File Structure

**신규 (`com.aicsassistant.staging`)**

| 파일 | 책임 |
|---|---|
| `staging/domain/StagedChange.java` | 엔티티. 제안 생성 + 승인/거부 상태 전이와 그 가드 |
| `staging/domain/ChangeType.java` | enum `REFUND` |
| `staging/domain/StagedChangeStatus.java` | enum `PENDING`/`APPROVED`/`REJECTED` |
| `staging/infra/StagedChangeRepository.java` | 쿼리 메서드 2개 |
| `staging/application/StagedChangeApprovalService.java` | 승인·거부. 재검사 → 실행 → 알림 → 상태 기록 |
| `staging/dto/StagedChangeDecisionRequest.java` | `{decidedBy, decisionNote}` |
| `staging/dto/StagedChangeResponse.java` | 뷰·API 공용 응답 |
| `staging/api/StagedChangeController.java` | approve / reject 엔드포인트 |
| `analysis/agent/tool/StageRefundTool.java` | 제안 저장 툴 |
| `analysis/agent/interceptor/OrderProvenanceInterceptor.java` | 조회 성공한 주문 ID 기록 |
| `analysis/agent/interceptor/RefundGuardrailInterceptor.java` | 가드레일 4종 + staging 발생 표시 |

**수정**

| 파일 | 변경 |
|---|---|
| `resources/schema.sql` | `staged_change` 테이블 + 부분 인덱스 추가 |
| `analysis/agent/ToolCallContext.java` | `observedOrderIds`, `stagedChange` 추가 |
| `analysis/agent/InquiryAgentService.java` | 툴 등록, ctx 생성 순서, 선주입 provenance, `buildFinalAnswer`에 ctx |
| `analysis/application/PromptFactory.java` | 환불 라우팅 규칙 + `PROMPT_VERSION` v4 |
| `order/InMemoryOrderRepository.java` | `ORDERS` 가변화 + `markRefunded` |
| `ui/viewmodel/InquiryDetailViewModel.java` | `stagedChanges` 필드 |
| `ui/application/InquiryDetailAssembler.java` | `stage_refund` 액션 라벨 |
| `ui/controller/CounselorViewController.java` | ViewModel 조립에 staged change 전달 |
| `resources/templates/inquiries/detail.html` | 제안 카드 + 이력 + 승인/거부 JS |
| `test/support/PostgresVectorIntegrationTest.java` | truncate 목록에 `staged_change` |
| `CLAUDE.md` | 툴 설계 원칙 문구 갱신 |
| `README.md` | 아키텍처 mermaid에 staging 경로 |

---

### Task 1: 도메인 & 스키마

**Files:**
- Create: `src/main/java/com/aicsassistant/staging/domain/StagedChange.java`
- Create: `src/main/java/com/aicsassistant/staging/domain/ChangeType.java`
- Create: `src/main/java/com/aicsassistant/staging/domain/StagedChangeStatus.java`
- Create: `src/main/java/com/aicsassistant/staging/domain/RefundGuardrails.java`
- Create: `src/main/java/com/aicsassistant/staging/infra/StagedChangeRepository.java`
- Modify: `src/main/resources/schema.sql` (파일 맨 끝에 추가)
- Modify: `src/test/java/com/aicsassistant/support/PostgresVectorIntegrationTest.java` (truncate 목록)
- Test: `src/test/java/com/aicsassistant/staging/domain/StagedChangeTest.java`
- Test: `src/test/java/com/aicsassistant/staging/infra/StagedChangeRepositoryTest.java`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces:
  - `StagedChange.propose(Long inquiryId, ChangeType type, String orderId, int amount, String reason, String policyBasis)` → `StagedChange`
  - `StagedChange#approve(String decidedBy, String decisionNote)` → `void`
  - `StagedChange#reject(String decidedBy, String decisionNote)` → `void`
  - getter: `getId()`, `getInquiryId()`, `getChangeType()`, `getOrderId()`, `getAmount()`, `getReason()`, `getPolicyBasis()`, `getStatus()`, `getDecidedBy()`, `getDecidedAt()`, `getDecisionNote()`, `getCreatedAt()`
  - `StagedChangeRepository#existsByOrderIdAndStatus(String orderId, StagedChangeStatus status)` → `boolean`
  - `StagedChangeRepository#findByInquiryIdOrderByCreatedAtDesc(Long inquiryId)` → `List<StagedChange>`
  - `RefundGuardrails.REFUND_BLOCKING_STATUSES` → `Set<String>` (`"취소완료"`, `"취소처리중"`, `"반품완료"`)
  - `RefundGuardrails.ALREADY_REFUNDED_STATUS` → `String` (`"환불완료"`)

- [ ] **Step 1: enum 2개와 스키마를 먼저 만든다**

`ChangeType.java`:

```java
package com.aicsassistant.staging.domain;

public enum ChangeType {
    REFUND
}
```

`StagedChangeStatus.java`:

```java
package com.aicsassistant.staging.domain;

public enum StagedChangeStatus {
    PENDING,
    APPROVED,
    REJECTED
}
```

`RefundGuardrails.java` — 환불을 막아야 하는 주문 상태. 제안 시점(`RefundGuardrailInterceptor`, Task 4)과
승인 시점(`StagedChangeApprovalService`, Task 6) 두 곳이 같은 목록을 봐야 하므로 도메인에 둔다.
두 패키지 모두 `staging`을 참조하는 방향이라 패키지 순환이 생기지 않는다:

```java
package com.aicsassistant.staging.domain;

import java.util.Set;

/** 환불 제안·승인의 공통 판정 기준. 제안 시점과 승인 시점이 같은 목록을 봐야 한다. */
public final class RefundGuardrails {

    /**
     * 이미 환불이 끝났거나 진행 중인 주문 상태 — 환불을 제안·승인할 수 없다.
     *
     * <p>ponytail: 부분환불완료는 남은 금액 환불이 정당할 수 있어 제외한다. 대신 금액 상한이
     * 결제금액 전액이라 이미 환불된 몫을 차감하지 못한다 — mock 데이터가 부분환불 금액을
     * {@code note} 텍스트에만 갖고 있기 때문이다. 남은 판단은 승인 화면의 상담사에게 있다.
     * 주문 도메인이 환불 이력을 구조적으로 제공하면 상한을 (결제금액 - 기환불액)으로 좁힌다.
     */
    public static final Set<String> REFUND_BLOCKING_STATUSES = Set.of("취소완료", "취소처리중", "반품완료");

    /** 환불이 이미 실행된 주문 상태. 승인 시점 재검사에서 쓴다. */
    public static final String ALREADY_REFUNDED_STATUS = "환불완료";

    private RefundGuardrails() {
    }
}
```

`schema.sql` **맨 끝**에 추가 (기존 `alter table` 줄들 뒤):

```sql

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
```

- [ ] **Step 2: 도메인 상태 전이 테스트를 쓴다 (실패해야 함)**

`src/test/java/com/aicsassistant/staging/domain/StagedChangeTest.java`:

```java
package com.aicsassistant.staging.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicsassistant.common.exception.ApiException;
import org.junit.jupiter.api.Test;

class StagedChangeTest {

    private StagedChange pendingRefund() {
        return StagedChange.propose(1L, ChangeType.REFUND, "ORD-20260405-002", 45_000,
                "배송완료 4일 경과, 반품 가능 기간 이내", "반품 정책 3조");
    }

    @Test
    void proposeStartsAsPending() {
        StagedChange change = pendingRefund();

        assertThat(change.getStatus()).isEqualTo(StagedChangeStatus.PENDING);
        assertThat(change.getAmount()).isEqualTo(45_000);
        assertThat(change.getDecidedBy()).isNull();
        assertThat(change.getDecidedAt()).isNull();
    }

    @Test
    void approveRecordsDeciderAndTimestamp() {
        StagedChange change = pendingRefund();

        change.approve("counselor-demo", null);

        assertThat(change.getStatus()).isEqualTo(StagedChangeStatus.APPROVED);
        assertThat(change.getDecidedBy()).isEqualTo("counselor-demo");
        assertThat(change.getDecidedAt()).isNotNull();
    }

    @Test
    void rejectRequiresNote() {
        StagedChange change = pendingRefund();

        assertThatThrownBy(() -> change.reject("counselor-demo", "  "))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("거부 사유");
    }

    @Test
    void rejectRecordsNote() {
        StagedChange change = pendingRefund();

        change.reject("counselor-demo", "고객 주장과 배송 기록이 불일치");

        assertThat(change.getStatus()).isEqualTo(StagedChangeStatus.REJECTED);
        assertThat(change.getDecisionNote()).isEqualTo("고객 주장과 배송 기록이 불일치");
    }

    @Test
    void cannotDecideTwice() {
        StagedChange change = pendingRefund();
        change.approve("counselor-demo", null);

        assertThatThrownBy(() -> change.approve("other", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ALREADY_DECIDED");
        assertThatThrownBy(() -> change.reject("other", "사유"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ALREADY_DECIDED");
    }
}
```

- [ ] **Step 3: 테스트를 돌려 실패를 확인한다**

Run: `./gradlew test --tests "com.aicsassistant.staging.domain.StagedChangeTest"`
Expected: FAIL — `StagedChange` 클래스가 없어 컴파일 에러

- [ ] **Step 4: 엔티티를 구현한다**

`src/main/java/com/aicsassistant/staging/domain/StagedChange.java`:

```java
package com.aicsassistant.staging.domain;

import com.aicsassistant.common.exception.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 에이전트가 제안했고 아직 실행되지 않은 상태 변경.
 *
 * <p>실행 권한은 이 레코드에 없다. 상담사가 승인 표면에서 승인해야
 * {@code StagedChangeApprovalService}가 실행한다. 결정 이력(누가·언제·왜)이
 * 이 레코드에 남으므로 별도 감사 테이블을 두지 않는다.
 */
@Getter
@Entity
@Table(name = "staged_change")
public class StagedChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "inquiry_id", nullable = false)
    private Long inquiryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20)
    private ChangeType changeType;

    @Column(name = "order_id", nullable = false, length = 50)
    private String orderId;

    @Column(nullable = false)
    private int amount;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "policy_basis", columnDefinition = "text")
    private String policyBasis;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StagedChangeStatus status;

    @Column(name = "decided_by", length = 100)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_note", columnDefinition = "text")
    private String decisionNote;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected StagedChange() {
    }

    public static StagedChange propose(
            Long inquiryId, ChangeType changeType, String orderId,
            int amount, String reason, String policyBasis) {
        StagedChange change = new StagedChange();
        change.inquiryId = inquiryId;
        change.changeType = changeType;
        change.orderId = orderId;
        change.amount = amount;
        change.reason = reason;
        change.policyBasis = policyBasis;
        change.status = StagedChangeStatus.PENDING;
        return change;
    }

    public void approve(String decidedBy, String decisionNote) {
        requirePending();
        this.status = StagedChangeStatus.APPROVED;
        this.decidedBy = decidedBy;
        this.decisionNote = decisionNote;
        this.decidedAt = LocalDateTime.now();
    }

    public void reject(String decidedBy, String decisionNote) {
        requirePending();
        if (decisionNote == null || decisionNote.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DECISION_NOTE_REQUIRED",
                    "거부 사유는 필수입니다.");
        }
        this.status = StagedChangeStatus.REJECTED;
        this.decidedBy = decidedBy;
        this.decisionNote = decisionNote;
        this.decidedAt = LocalDateTime.now();
    }

    private void requirePending() {
        if (status != StagedChangeStatus.PENDING) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ALREADY_DECIDED",
                    "이미 " + status + " 상태인 제안입니다. (ALREADY_DECIDED)");
        }
    }

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 5: 도메인 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests "com.aicsassistant.staging.domain.StagedChangeTest"`
Expected: PASS (5건)

- [ ] **Step 6: 레포지터리 통합 테스트를 쓴다 (실패해야 함)**

먼저 `PostgresVectorIntegrationTest`의 truncate 목록에 새 테이블을 넣는다:

```java
    @BeforeEach
    void clearDatabase() {
        jdbcTemplate.execute("truncate table staged_change, inquiry_analysis_log, manual_chunk, inquiry, manual_document restart identity cascade");
    }
```

`src/test/java/com/aicsassistant/staging/infra/StagedChangeRepositoryTest.java`:

```java
package com.aicsassistant.staging.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.infra.InquiryRepository;
import com.aicsassistant.staging.domain.ChangeType;
import com.aicsassistant.staging.domain.StagedChange;
import com.aicsassistant.staging.domain.StagedChangeStatus;
import com.aicsassistant.support.PostgresVectorIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class StagedChangeRepositoryTest extends PostgresVectorIntegrationTest {

    @Autowired
    StagedChangeRepository stagedChangeRepository;

    @Autowired
    InquiryRepository inquiryRepository;

    private Long newInquiryId() {
        return inquiryRepository.save(Inquiry.create("cust-001", "문의", "환불 요청")).getId();
    }

    @Test
    void savesAndReadsBackProposal() {
        Long inquiryId = newInquiryId();

        StagedChange saved = stagedChangeRepository.save(StagedChange.propose(
                inquiryId, ChangeType.REFUND, "ORD-20260405-002", 45_000, "기간 이내", "반품 정책 3조"));

        StagedChange found = stagedChangeRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(StagedChangeStatus.PENDING);
        assertThat(found.getOrderId()).isEqualTo("ORD-20260405-002");
        assertThat(found.getAmount()).isEqualTo(45_000);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void existsByOrderIdAndStatusFindsOnlyPending() {
        Long inquiryId = newInquiryId();
        StagedChange change = stagedChangeRepository.save(StagedChange.propose(
                inquiryId, ChangeType.REFUND, "ORD-20260405-002", 45_000, "기간 이내", null));

        assertThat(stagedChangeRepository
                .existsByOrderIdAndStatus("ORD-20260405-002", StagedChangeStatus.PENDING)).isTrue();

        change.reject("counselor-demo", "기록 불일치");
        stagedChangeRepository.save(change);

        assertThat(stagedChangeRepository
                .existsByOrderIdAndStatus("ORD-20260405-002", StagedChangeStatus.PENDING)).isFalse();
    }

    @Test
    void findsByInquiryNewestFirst() {
        Long inquiryId = newInquiryId();
        stagedChangeRepository.save(StagedChange.propose(
                inquiryId, ChangeType.REFUND, "ORD-A", 10_000, "첫 제안", null));
        stagedChangeRepository.save(StagedChange.propose(
                inquiryId, ChangeType.REFUND, "ORD-B", 20_000, "두 번째 제안", null));

        List<StagedChange> found = stagedChangeRepository.findByInquiryIdOrderByCreatedAtDesc(inquiryId);

        assertThat(found).hasSize(2);
        assertThat(found).extracting(StagedChange::getOrderId).containsExactlyInAnyOrder("ORD-A", "ORD-B");
    }
}
```

- [ ] **Step 7: 테스트를 돌려 실패를 확인한다**

Run: `./gradlew test --tests "com.aicsassistant.staging.infra.StagedChangeRepositoryTest"`
Expected: FAIL — `StagedChangeRepository` 없음

- [ ] **Step 8: 레포지터리를 구현한다**

`src/main/java/com/aicsassistant/staging/infra/StagedChangeRepository.java`:

```java
package com.aicsassistant.staging.infra;

import com.aicsassistant.staging.domain.StagedChange;
import com.aicsassistant.staging.domain.StagedChangeStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StagedChangeRepository extends JpaRepository<StagedChange, Long> {

    boolean existsByOrderIdAndStatus(String orderId, StagedChangeStatus status);

    List<StagedChange> findByInquiryIdOrderByCreatedAtDesc(Long inquiryId);
}
```

- [ ] **Step 9: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests "com.aicsassistant.staging.*"`
Expected: PASS (8건: 도메인 5 + 레포 3)

- [ ] **Step 10: 커밋**

```bash
git add src/main/java/com/aicsassistant/staging src/main/resources/schema.sql \
        src/test/java/com/aicsassistant/staging src/test/java/com/aicsassistant/support/PostgresVectorIntegrationTest.java
git commit -m "staged_change 도메인·스키마·레포지터리 추가

제안 하나가 자기 레코드를 갖고, 결정 이력(누가·언제·왜)이 같은 레코드에 남는다.
거부는 사유 필수, 이미 결정된 제안은 재결정 불가(ALREADY_DECIDED)."
```

---

### Task 2: provenance 수집

**Files:**
- Modify: `src/main/java/com/aicsassistant/analysis/agent/ToolCallContext.java`
- Create: `src/main/java/com/aicsassistant/analysis/agent/interceptor/OrderProvenanceInterceptor.java`
- Modify: `src/main/java/com/aicsassistant/analysis/agent/InquiryAgentService.java`
- Test: `src/test/java/com/aicsassistant/analysis/agent/interceptor/OrderProvenanceInterceptorTest.java`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `ToolCallContext#recordObservedOrder(String orderId)` → `void`
  - `ToolCallContext#hasObservedOrder(String orderId)` → `boolean`
  - `ToolCallContext#markStagedChange()` → `void`
  - `ToolCallContext#stagedChange()` → `boolean`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/aicsassistant/analysis/agent/interceptor/OrderProvenanceInterceptorTest.java`:

```java
package com.aicsassistant.analysis.agent.interceptor;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicsassistant.analysis.agent.ToolCallContext;
import com.aicsassistant.analysis.agent.ToolErrorCategory;
import com.aicsassistant.analysis.agent.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class OrderProvenanceInterceptorTest {

    private final OrderProvenanceInterceptor interceptor = new OrderProvenanceInterceptor();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolCallContext ctx = new ToolCallContext(1L, "cust-001");

    private ObjectNode inputWithOrderId(String orderId) {
        ObjectNode node = mapper.createObjectNode();
        node.put("orderId", orderId);
        return node;
    }

    @Test
    void recordsOrderIdOnSuccessfulLookup() {
        interceptor.afterExecute("check_order_status", inputWithOrderId("ORD-20260410-001"),
                ToolResult.success("주문번호: ORD-20260410-001"), ctx);

        assertThat(ctx.hasObservedOrder("ORD-20260410-001")).isTrue();
    }

    @Test
    void doesNotRecordOnFailedLookup() {
        interceptor.afterExecute("check_order_status", inputWithOrderId("ORD-UNKNOWN"),
                ToolResult.error(ToolErrorCategory.NOT_FOUND, false, "없음"), ctx);

        assertThat(ctx.hasObservedOrder("ORD-UNKNOWN")).isFalse();
    }

    @Test
    void ignoresOtherTools() {
        interceptor.afterExecute("search_manual", inputWithOrderId("ORD-20260410-001"),
                ToolResult.success("정책 본문"), ctx);

        assertThat(ctx.hasObservedOrder("ORD-20260410-001")).isFalse();
    }

    @Test
    void returnsResultUnchanged() {
        ToolResult original = ToolResult.success("주문번호: ORD-20260410-001");

        ToolResult returned = interceptor.afterExecute(
                "check_order_status", inputWithOrderId("ORD-20260410-001"), original, ctx);

        assertThat(returned).isSameAs(original);
    }
}
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

Run: `./gradlew test --tests "com.aicsassistant.analysis.agent.interceptor.OrderProvenanceInterceptorTest"`
Expected: FAIL — `OrderProvenanceInterceptor` 없음, `hasObservedOrder` 없음

- [ ] **Step 3: `ToolCallContext`를 확장한다**

`ToolCallContext.java` — 기존 필드 아래에 추가:

```java
    private final Set<String> observedOrderIds = new HashSet<>();
    private boolean stagedChange;

    /** 이번 실행에서 조회에 성공한 주문. 환불 제안의 provenance 근거가 된다. */
    public void recordObservedOrder(String orderId) {
        observedOrderIds.add(orderId);
    }

    public boolean hasObservedOrder(String orderId) {
        return observedOrderIds.contains(orderId);
    }

    /** 이번 실행에서 제안이 접수되었음을 표시한다. finalAnswer의 상담사 검토를 강제하는 데 쓴다. */
    public void markStagedChange() {
        stagedChange = true;
    }

    public boolean stagedChange() {
        return stagedChange;
    }
```

import 추가: `java.util.HashSet`, `java.util.Set`.

- [ ] **Step 4: 인터셉터를 구현한다**

`src/main/java/com/aicsassistant/analysis/agent/interceptor/OrderProvenanceInterceptor.java`:

```java
package com.aicsassistant.analysis.agent.interceptor;

import com.aicsassistant.analysis.agent.ToolCallContext;
import com.aicsassistant.analysis.agent.ToolCallInterceptor;
import com.aicsassistant.analysis.agent.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * 조회에 성공한 주문 ID를 컨텍스트에 기록한다.
 *
 * <p>{@code stage_refund}의 provenance 가드레일이 이 기록을 근거로 쓴다 — 모델이 주문번호를
 * 만들어내 환불을 제안하는 것을 막는다. 결과는 변형하지 않는다.
 */
@Component
public class OrderProvenanceInterceptor implements ToolCallInterceptor {

    private static final String TARGET_TOOL = "check_order_status";

    @Override
    public ToolResult afterExecute(String toolName, JsonNode input, ToolResult result, ToolCallContext ctx) {
        if (!TARGET_TOOL.equals(toolName) || !result.ok()) {
            return result;
        }
        String orderId = input.path("orderId").asText("").strip();
        if (!orderId.isBlank()) {
            ctx.recordObservedOrder(orderId);
        }
        return result;
    }
}
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests "com.aicsassistant.analysis.agent.interceptor.OrderProvenanceInterceptorTest"`
Expected: PASS (4건)

- [ ] **Step 6: 선주입 주문도 provenance에 넣는다**

`InquiryAgentService.runAgentLoop` — `callContext` 생성을 `buildInitialMessage` 호출보다 **앞으로** 옮기고, 선주입 성공 시 기록한다. 현재 코드:

```java
        messages.add(ChatMessage.user(buildInitialMessage(inquiry, orderTool)));
        ...
        List<AgentStep> steps = new ArrayList<>();
        int totalTokens = 0;
        ToolCallContext callContext = new ToolCallContext(inquiry.getId(), inquiry.getCustomerIdentifier());
```

바꾼 뒤:

```java
        ToolCallContext callContext = new ToolCallContext(inquiry.getId(), inquiry.getCustomerIdentifier());
        messages.add(ChatMessage.user(buildInitialMessage(inquiry, orderTool, callContext)));

        // 이전 대화 히스토리 주입 (CUSTOMER → user, AI → assistant)
        for (InquiryMessage msg : conversationHistory) { ... }

        List<AgentStep> steps = new ArrayList<>();
        int totalTokens = 0;
```

`buildInitialMessage` 시그니처에 ctx를 추가하고 성공 분기에서 기록한다:

```java
    private String buildInitialMessage(Inquiry inquiry, CheckOrderStatusTool orderTool, ToolCallContext ctx) {
        StringBuilder sb = new StringBuilder();

        String orderId = inquiry.getRelatedOrderId();
        if (orderId != null && !orderId.isBlank()) {
            try {
                ToolResult orderResult = orderTool.execute(new CheckOrderStatusTool.Input(orderId));
                if (orderResult.ok()) {
                    // 인터셉터를 지나가지 않는 경로라 여기서 직접 기록한다.
                    // 문의의 relatedOrderId 이고 소유자 검증을 통과한 고객 자기 주문이라 정당하다.
                    ctx.recordObservedOrder(orderId);
                    sb.append("[관련 주문 정보]\n").append(orderResult.data()).append("\n\n");
                } else {
                    sb.append("[관련 주문 조회 실패] ").append(orderResult.errorMessage()).append("\n\n");
                }
            } catch (Exception e) {
                log.warn("[Agent] 주문 정보 선주입 실패 orderId={}", orderId, e);
            }
        }

        sb.append(promptFactory.fenceCustomerText(
                "고객 문의 제목: " + inquiry.getTitle() + "\n\n[문의 내용]\n" + inquiry.getContent()));
        return sb.toString();
    }
```

- [ ] **Step 7: 기존 에이전트 테스트가 여전히 통과하는지 확인한다**

Run: `./gradlew test --tests "com.aicsassistant.analysis.agent.*"`
Expected: PASS — 기존 19건 + 신규 4건

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/aicsassistant/analysis/agent src/test/java/com/aicsassistant/analysis/agent
git commit -m "주문 조회 provenance 를 ToolCallContext 에 기록

환불 제안 가드레일이 '이번 실행에서 실제로 조회한 주문인가'를 판정할 근거를 모은다.
인터셉터를 지나가지 않는 선주입 경로는 buildInitialMessage 에서 직접 기록한다."
```

---

### Task 3: `stage_refund` 툴 등록

**Files:**
- Create: `src/main/java/com/aicsassistant/analysis/agent/tool/StageRefundTool.java`
- Modify: `src/main/java/com/aicsassistant/analysis/agent/InquiryAgentService.java` (툴 목록)
- Modify: `src/main/java/com/aicsassistant/analysis/application/PromptFactory.java` (라우팅 규칙 + 버전)
- Test: `src/test/java/com/aicsassistant/analysis/agent/tool/StageRefundToolTest.java`
- Test: `src/test/java/com/aicsassistant/analysis/application/PromptFactoryTest.java` (1건 추가)

**Interfaces:**
- Consumes: `StagedChange.propose(...)`, `StagedChangeRepository` (Task 1)
- Produces:
  - `StageRefundTool(StagedChangeRepository stagedChangeRepository, Long inquiryId)` 생성자
  - `StageRefundTool.Input(String orderId, Integer amount, String reason, String policyBasis)` record
  - 툴 이름 문자열 `"stage_refund"`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/aicsassistant/analysis/agent/tool/StageRefundToolTest.java`:

```java
package com.aicsassistant.analysis.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aicsassistant.analysis.agent.ToolErrorCategory;
import com.aicsassistant.analysis.agent.ToolResult;
import com.aicsassistant.staging.domain.ChangeType;
import com.aicsassistant.staging.domain.StagedChange;
import com.aicsassistant.staging.infra.StagedChangeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StageRefundToolTest {

    @Mock
    StagedChangeRepository stagedChangeRepository;

    private StageRefundTool tool() {
        return new StageRefundTool(stagedChangeRepository, 7L);
    }

    @Test
    void savesPendingProposalAndSaysItIsNotExecuted() {
        when(stagedChangeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ToolResult result = tool().execute(new StageRefundTool.Input(
                "ORD-20260405-002", 45_000, "배송완료 4일 경과", "반품 정책 3조"));

        assertThat(result.ok()).isTrue();
        assertThat(result.data())
                .contains("ORD-20260405-002")
                .contains("45,000")
                .contains("아직 실행되지 않았")
                .contains("상담사 승인");

        ArgumentCaptor<StagedChange> captor = ArgumentCaptor.forClass(StagedChange.class);
        verify(stagedChangeRepository).save(captor.capture());
        StagedChange saved = captor.getValue();
        assertThat(saved.getInquiryId()).isEqualTo(7L);
        assertThat(saved.getChangeType()).isEqualTo(ChangeType.REFUND);
        assertThat(saved.getAmount()).isEqualTo(45_000);
    }

    @Test
    void rejectsMissingOrderId() {
        ToolResult result = tool().execute(new StageRefundTool.Input(" ", 45_000, "사유", null));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ToolErrorCategory.VALIDATION);
        assertThat(result.errorMessage()).contains("orderId");
        verify(stagedChangeRepository, never()).save(any());
    }

    @Test
    void rejectsNonPositiveAmount() {
        ToolResult result = tool().execute(new StageRefundTool.Input("ORD-A", 0, "사유", null));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ToolErrorCategory.VALIDATION);
        assertThat(result.errorMessage()).contains("amount");
    }

    @Test
    void rejectsNullAmount() {
        ToolResult result = tool().execute(new StageRefundTool.Input("ORD-A", null, "사유", null));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ToolErrorCategory.VALIDATION);
    }

    @Test
    void rejectsBlankReason() {
        ToolResult result = tool().execute(new StageRefundTool.Input("ORD-A", 45_000, "  ", null));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCategory()).isEqualTo(ToolErrorCategory.VALIDATION);
        assertThat(result.errorMessage()).contains("reason");
    }

    @Test
    void exposesSurfaceFieldsForLlm() {
        StageRefundTool tool = tool();

        assertThat(tool.name()).isEqualTo("stage_refund");
        assertThat(tool.inputType()).isEqualTo(StageRefundTool.Input.class);
        assertThat(tool.inputSchema()).contains("orderId").contains("amount").contains("reason");
        assertThat(tool.whenToUse()).isNotBlank();
        assertThat(tool.usageBoundary()).contains("check_order_status");
        assertThat(tool.successOutputHint()).contains("승인");
        assertThat(tool.failureBehavior()).contains("PERMISSION");
    }
}
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

Run: `./gradlew test --tests "com.aicsassistant.analysis.agent.tool.StageRefundToolTest"`
Expected: FAIL — `StageRefundTool` 없음

- [ ] **Step 3: 툴을 구현한다**

`src/main/java/com/aicsassistant/analysis/agent/tool/StageRefundTool.java`:

```java
package com.aicsassistant.analysis.agent.tool;

import com.aicsassistant.analysis.agent.AgentTool;
import com.aicsassistant.analysis.agent.ToolErrorCategory;
import com.aicsassistant.analysis.agent.ToolResult;
import com.aicsassistant.staging.domain.ChangeType;
import com.aicsassistant.staging.domain.StagedChange;
import com.aicsassistant.staging.infra.StagedChangeRepository;

/**
 * 환불 제안을 접수하는 도구. <b>환불을 실행하지 않는다.</b>
 *
 * <p>가드레일(provenance·금액·주문상태·중복)은 이 툴이 아니라
 * {@code RefundGuardrailInterceptor}가 검사한다 — 툴은 {@code ToolCallContext}를 볼 수 없다.
 * 여기 도달한 호출은 이미 가드를 통과했으므로 입력 형식만 검증하고 저장한다.
 */
public class StageRefundTool implements AgentTool<StageRefundTool.Input> {

    /** 도구 입력 — 금액은 부분 환불을 허용하므로 결제금액과 다를 수 있다. */
    public record Input(String orderId, Integer amount, String reason, String policyBasis) {}

    private final StagedChangeRepository stagedChangeRepository;
    private final Long inquiryId;

    public StageRefundTool(StagedChangeRepository stagedChangeRepository, Long inquiryId) {
        this.stagedChangeRepository = stagedChangeRepository;
        this.inquiryId = inquiryId;
    }

    @Override
    public String name() {
        return "stage_refund";
    }

    @Override
    public String description() {
        return "Submits a refund proposal for counselor approval. Does NOT execute the refund.";
    }

    @Override
    public String whenToUse() {
        return "Call when the customer requests a refund AND you have already looked up the order with "
                + "check_order_status AND the policy supports a refund. Submit the amount you believe is "
                + "correct with your reasoning — a counselor decides whether to execute it.";
    }

    @Override
    public String usageBoundary() {
        return "Do NOT use for: (1) orders you have not looked up with check_order_status in this "
                + "conversation (the call will be blocked), (2) order cancellation or exchange (not supported "
                + "yet — set needsHumanReview: true instead), (3) telling the customer the refund is done. "
                + "This tool only files a proposal; nothing is refunded until a counselor approves.";
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public String inputSchema() {
        return "{\"orderId\": \"string (required) — order looked up in this conversation\", "
                + "\"amount\": \"integer (required) — KRW to refund, must be > 0 and <= the order's paid amount\", "
                + "\"reason\": \"string (required) — Korean explanation of how you arrived at this amount\", "
                + "\"policyBasis\": \"string (optional) — the policy clause you relied on\"}";
    }

    @Override
    public String successOutputHint() {
        return "A Korean confirmation that the proposal was FILED and is awaiting 상담사 승인 — "
                + "it explicitly states the refund has not been executed. Never tell the customer the refund "
                + "is complete based on this result.";
    }

    @Override
    public String failureBehavior() {
        return "PERMISSION (provenance / order state / duplicate): do NOT retry. Produce finalAnswer with "
                + "needsHumanReview: true, and if the message says to look up the order first, call "
                + "check_order_status before proposing again. "
                + "VALIDATION (amount or reason): fix actionInput and retry once.";
    }

    @Override
    public ToolResult execute(Input input) {
        String orderId = input.orderId() == null ? "" : input.orderId().strip();
        if (orderId.isBlank()) {
            return ToolResult.error(ToolErrorCategory.VALIDATION, false, "'orderId' field is required.");
        }
        if (input.amount() == null || input.amount() <= 0) {
            return ToolResult.error(ToolErrorCategory.VALIDATION, false,
                    "'amount' must be a positive integer (KRW).");
        }
        String reason = input.reason() == null ? "" : input.reason().strip();
        if (reason.isBlank()) {
            return ToolResult.error(ToolErrorCategory.VALIDATION, false,
                    "'reason' field is required — explain how you arrived at this amount.");
        }

        StagedChange saved = stagedChangeRepository.save(StagedChange.propose(
                inquiryId, ChangeType.REFUND, orderId, input.amount(), reason, input.policyBasis()));

        return ToolResult.success(
                "환불 제안 #%s 접수됨 (주문 %s, %,d원). 아직 실행되지 않았으며 상담사 승인이 필요합니다."
                        .formatted(saved.getId(), orderId, input.amount()));
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests "com.aicsassistant.analysis.agent.tool.StageRefundToolTest"`
Expected: PASS (6건)

- [ ] **Step 5: 툴을 에이전트에 등록한다**

`InquiryAgentService` — 필드에 레포지터리를 주입하고 `run()`에서 툴을 만든다:

```java
    private final StagedChangeRepository stagedChangeRepository;
```

```java
    public AgentResult run(Inquiry inquiry, List<InquiryMessage> conversationHistory) {
        CheckOrderStatusTool orderTool = new CheckOrderStatusTool(orderRepository, inquiry.getCustomerIdentifier());
        SearchManualTool searchTool = new SearchManualTool(manualRetrievalService);
        SearchFaqTool faqTool = new SearchFaqTool(faqRepository);
        StageRefundTool refundTool = new StageRefundTool(stagedChangeRepository, inquiry.getId());
        List<AgentTool<?>> tools = List.of(faqTool, searchTool, orderTool, refundTool);
```

import 추가: `com.aicsassistant.analysis.agent.tool.StageRefundTool`, `com.aicsassistant.staging.infra.StagedChangeRepository`.

**필드는 `tracer` 뒤에 선언한다.** 이 클래스는 `@RequiredArgsConstructor`라서 필드 선언 순서가 곧 생성자 파라미터 순서다. 따라서 생성자는:

```java
new InquiryAgentService(llmClient, manualRetrievalService, promptFactory, objectMapper,
        orderRepository, faqRepository, interceptors, tracer, stagedChangeRepository)
```

`InquiryAgentServiceTest`에 `@Mock StagedChangeRepository stagedChangeRepository`를 추가하고, `setUp()`의 생성자 호출과 테스트 안에서 서비스를 새로 만드는 지점(`interceptorCanBlockToolCallBeforeExecution` 등) 전부에 마지막 인자로 넘긴다.

- [ ] **Step 6: 프롬프트에 환불 라우팅 규칙을 추가한다**

`PromptFactory` — `PROMPT_VERSION`을 `"v4"`로 올리고, `## Guidelines` 블록 끝에 두 줄을 추가한다:

```
                - For refund requests: call check_order_status first, then search_manual/search_faq for the refund policy, then stage_refund with the amount you believe is correct. A staged proposal is NOT an executed refund
                - After stage_refund succeeds, tell the customer "담당자가 확인 후 처리해 드리겠습니다" — never "환불되었습니다" or "환불 처리 완료" (nothing has been refunded yet)
```

- [ ] **Step 7: 프롬프트 테스트를 1건 추가한다**

`PromptFactoryTest`에 추가:

```java
    @Test
    void systemPromptForbidsClaimingRefundIsDone() {
        String prompt = promptFactory.buildAgentSystemPrompt(List.<AgentTool<?>>of());

        assertThat(prompt)
                .contains("stage_refund")
                .contains("NOT an executed refund")
                .contains("never \"환불되었습니다\"");
    }
```

- [ ] **Step 8: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests "com.aicsassistant.analysis.*"`
Expected: PASS

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/aicsassistant/analysis src/test/java/com/aicsassistant/analysis
git commit -m "stage_refund 툴 추가 — 제안만 하고 실행하지 않는다

성공 observation 에 '아직 실행되지 않았으며 상담사 승인이 필요합니다' 를 박아둔다.
이게 없으면 에이전트가 staging 을 실행 완료로 오인해 고객에게 '환불되었습니다' 라고
답할 수 있다. 프롬프트에도 같은 금지 규칙을 넣고 PROMPT_VERSION v4 로 올린다."
```

---

### Task 4: 가드레일 4종

**Files:**
- Create: `src/main/java/com/aicsassistant/analysis/agent/interceptor/RefundGuardrailInterceptor.java`
- Test: `src/test/java/com/aicsassistant/analysis/agent/interceptor/RefundGuardrailInterceptorTest.java`

**Interfaces:**
- Consumes: `ToolCallContext#hasObservedOrder`, `#markStagedChange`, `#customerIdentifier` (Task 2), `StagedChangeRepository#existsByOrderIdAndStatus` 및 `RefundGuardrails.REFUND_BLOCKING_STATUSES` (Task 1), `InMemoryOrderRepository#findById(String, String)`
- Produces: 없음 (스프링 빈으로 인터셉터 체인에 자동 편입)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`src/test/java/com/aicsassistant/analysis/agent/interceptor/RefundGuardrailInterceptorTest.java`:

```java
package com.aicsassistant.analysis.agent.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.aicsassistant.analysis.agent.ToolCallContext;
import com.aicsassistant.analysis.agent.ToolErrorCategory;
import com.aicsassistant.analysis.agent.ToolResult;
import com.aicsassistant.order.InMemoryOrderRepository;
import com.aicsassistant.order.InMemoryOrderRepository.OrderInfo;
import com.aicsassistant.staging.domain.StagedChangeStatus;
import com.aicsassistant.staging.infra.StagedChangeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefundGuardrailInterceptorTest {

    @Mock
    InMemoryOrderRepository orderRepository;

    @Mock
    StagedChangeRepository stagedChangeRepository;

    @InjectMocks
    RefundGuardrailInterceptor interceptor;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolCallContext ctx = new ToolCallContext(1L, "cust-001");

    private ObjectNode input(String orderId, int amount) {
        ObjectNode node = mapper.createObjectNode();
        node.put("orderId", orderId);
        node.put("amount", amount);
        return node;
    }

    private OrderInfo order(String status, int amount) {
        return new OrderInfo("ORD-A", "상품", status, amount, "2026-04-05", null, null, null, null);
    }

    private void givenObservedOrder(String status, int amount) {
        ctx.recordObservedOrder("ORD-A");
        lenient().when(orderRepository.findById("ORD-A", "cust-001"))
                .thenReturn(Optional.of(order(status, amount)));
    }

    @Test
    void allowsWhenAllGuardrailsPass() {
        givenObservedOrder("배송완료", 45_000);
        when(stagedChangeRepository.existsByOrderIdAndStatus("ORD-A", StagedChangeStatus.PENDING))
                .thenReturn(false);

        Optional<ToolResult> blocked = interceptor.beforeExecute("stage_refund", input("ORD-A", 45_000), ctx);

        assertThat(blocked).isEmpty();
    }

    @Test
    void blocksOrderNotObservedInThisRun() {
        Optional<ToolResult> blocked = interceptor.beforeExecute("stage_refund", input("ORD-A", 45_000), ctx);

        assertThat(blocked).isPresent();
        assertThat(blocked.get().errorCategory()).isEqualTo(ToolErrorCategory.PERMISSION);
        assertThat(blocked.get().isRetryable()).isFalse();
        assertThat(blocked.get().errorMessage()).contains("check_order_status");
    }

    @Test
    void blocksAmountAboveOrderTotal() {
        givenObservedOrder("배송완료", 45_000);

        Optional<ToolResult> blocked = interceptor.beforeExecute("stage_refund", input("ORD-A", 450_000), ctx);

        assertThat(blocked).isPresent();
        assertThat(blocked.get().errorCategory()).isEqualTo(ToolErrorCategory.VALIDATION);
        assertThat(blocked.get().errorMessage()).contains("45,000");
    }

    @Test
    void blocksAlreadyCancelledOrder() {
        givenObservedOrder("취소완료", 45_000);

        Optional<ToolResult> blocked = interceptor.beforeExecute("stage_refund", input("ORD-A", 45_000), ctx);

        assertThat(blocked).isPresent();
        assertThat(blocked.get().errorCategory()).isEqualTo(ToolErrorCategory.PERMISSION);
        assertThat(blocked.get().errorMessage()).contains("취소완료");
    }

    @Test
    void blocksDuplicatePendingProposal() {
        givenObservedOrder("배송완료", 45_000);
        when(stagedChangeRepository.existsByOrderIdAndStatus("ORD-A", StagedChangeStatus.PENDING))
                .thenReturn(true);

        Optional<ToolResult> blocked = interceptor.beforeExecute("stage_refund", input("ORD-A", 45_000), ctx);

        assertThat(blocked).isPresent();
        assertThat(blocked.get().errorCategory()).isEqualTo(ToolErrorCategory.PERMISSION);
        assertThat(blocked.get().errorMessage()).contains("대기");
    }

    @Test
    void allowsPartiallyRefundedOrder() {
        givenObservedOrder("부분환불완료", 215_000);
        when(stagedChangeRepository.existsByOrderIdAndStatus("ORD-A", StagedChangeStatus.PENDING))
                .thenReturn(false);

        Optional<ToolResult> blocked = interceptor.beforeExecute("stage_refund", input("ORD-A", 32_000), ctx);

        assertThat(blocked).isEmpty();
    }

    @Test
    void ignoresOtherTools() {
        Optional<ToolResult> blocked = interceptor.beforeExecute("search_manual", input("ORD-A", 1), ctx);

        assertThat(blocked).isEmpty();
    }

    @Test
    void marksStagedChangeOnSuccess() {
        interceptor.afterExecute("stage_refund", input("ORD-A", 45_000),
                ToolResult.success("환불 제안 #1 접수됨"), ctx);

        assertThat(ctx.stagedChange()).isTrue();
    }

    @Test
    void doesNotMarkStagedChangeOnFailure() {
        interceptor.afterExecute("stage_refund", input("ORD-A", 45_000),
                ToolResult.error(ToolErrorCategory.VALIDATION, false, "잘못된 금액"), ctx);

        assertThat(ctx.stagedChange()).isFalse();
    }
}
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

Run: `./gradlew test --tests "com.aicsassistant.analysis.agent.interceptor.RefundGuardrailInterceptorTest"`
Expected: FAIL — `RefundGuardrailInterceptor` 없음

- [ ] **Step 3: 인터셉터를 구현한다**

`src/main/java/com/aicsassistant/analysis/agent/interceptor/RefundGuardrailInterceptor.java`:

```java
package com.aicsassistant.analysis.agent.interceptor;

import com.aicsassistant.analysis.agent.ToolCallContext;
import com.aicsassistant.analysis.agent.ToolCallInterceptor;
import com.aicsassistant.analysis.agent.ToolErrorCategory;
import com.aicsassistant.analysis.agent.ToolResult;
import com.aicsassistant.order.InMemoryOrderRepository;
import com.aicsassistant.order.InMemoryOrderRepository.OrderInfo;
import static com.aicsassistant.staging.domain.RefundGuardrails.REFUND_BLOCKING_STATUSES;

import com.aicsassistant.staging.domain.StagedChangeStatus;
import com.aicsassistant.staging.infra.StagedChangeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code stage_refund} 호출을 실행 전에 검사한다.
 *
 * <p>가드레일 4종을 툴 밖에 두는 이유: 툴은 {@code execute(Input)} 만 받아
 * {@link ToolCallContext}(provenance)를 볼 수 없다. 프롬프트 지시가 아니라 코드로 막는다.
 *
 * <p>{@code afterExecute}에서 제안 접수를 컨텍스트에 표시해 finalAnswer 의 상담사 검토를 강제한다 —
 * 가드레일의 마지막 단계다.
 */
@Component
@RequiredArgsConstructor
public class RefundGuardrailInterceptor implements ToolCallInterceptor {

    private static final String TARGET_TOOL = "stage_refund";

    private final InMemoryOrderRepository orderRepository;
    private final StagedChangeRepository stagedChangeRepository;

    @Override
    public Optional<ToolResult> beforeExecute(String toolName, JsonNode input, ToolCallContext ctx) {
        if (!TARGET_TOOL.equals(toolName)) {
            return Optional.empty();
        }

        String orderId = input.path("orderId").asText("").strip();
        int amount = input.path("amount").asInt(0);

        // (1) provenance — 모델이 주문번호를 만들어내는 것을 막는다
        if (!ctx.hasObservedOrder(orderId)) {
            return blocked(ToolErrorCategory.PERMISSION,
                    "주문 [" + orderId + "]은 이번 대화에서 조회되지 않았습니다. "
                            + "환불을 제안하기 전에 check_order_status로 주문을 먼저 조회하세요.");
        }

        OrderInfo order = orderRepository.findById(orderId, ctx.customerIdentifier()).orElse(null);
        if (order == null) {
            return blocked(ToolErrorCategory.PERMISSION,
                    "주문 [" + orderId + "] 정보를 확인할 수 없습니다. finalAnswer에서 needsHumanReview: true로 설정하세요.");
        }

        // (2) 주문 상태
        if (REFUND_BLOCKING_STATUSES.contains(order.status())) {
            return blocked(ToolErrorCategory.PERMISSION,
                    "주문 상태가 [" + order.status() + "]로 이미 환불이 완료되었거나 진행 중입니다. "
                            + "환불을 다시 제안하지 말고 finalAnswer에서 needsHumanReview: true로 설정하세요.");
        }

        // (3) 금액
        if (amount > order.amount()) {
            return blocked(ToolErrorCategory.VALIDATION,
                    "제안 금액 %,d원이 결제금액 %,d원을 초과합니다. 결제금액 이하로 수정해 다시 시도하세요."
                            .formatted(amount, order.amount()));
        }

        // (4) 중복
        if (stagedChangeRepository.existsByOrderIdAndStatus(orderId, StagedChangeStatus.PENDING)) {
            return blocked(ToolErrorCategory.PERMISSION,
                    "주문 [" + orderId + "]에는 이미 승인 대기 중인 환불 제안이 있습니다. "
                            + "중복 제안하지 말고 finalAnswer에서 needsHumanReview: true로 설정하세요.");
        }

        return Optional.empty();
    }

    @Override
    public ToolResult afterExecute(String toolName, JsonNode input, ToolResult result, ToolCallContext ctx) {
        if (TARGET_TOOL.equals(toolName) && result.ok()) {
            ctx.markStagedChange();
        }
        return result;
    }

    private Optional<ToolResult> blocked(ToolErrorCategory category, String message) {
        return Optional.of(ToolResult.error(category, false, message));
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests "com.aicsassistant.analysis.agent.interceptor.*"`
Expected: PASS (기존 인터셉터 테스트 + 신규 9건)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/aicsassistant/analysis/agent/interceptor src/test/java/com/aicsassistant/analysis/agent/interceptor
git commit -m "환불 제안 가드레일 4종 추가

provenance(이번 대화에서 조회한 주문만) / 금액(결제금액 이하) /
주문 상태(취소완료·취소처리중·반품완료 거부) / 중복(대기 중 제안 있으면 거부).
부분환불완료는 남은 금액 환불이 정당할 수 있어 거부하지 않는다.

프롬프트 지시가 아니라 코드로 막는다 — LLM 이 규칙을 어기면 툴 호출이 차단된다."
```

---

### Task 5: staging 시 상담사 검토 강제

**Files:**
- Modify: `src/main/java/com/aicsassistant/analysis/agent/InquiryAgentService.java`
- Test: `src/test/java/com/aicsassistant/analysis/agent/InquiryAgentServiceTest.java` (1건 추가)

**Interfaces:**
- Consumes: `ToolCallContext#stagedChange()` (Task 2), `AgentResult.FinalAnswer#withHumanReview()` (기존)
- Produces: `buildFinalAnswer(JsonNode, List<AgentStep>, List<RetrievedManualChunkDto>, int, ToolCallContext)` — ctx 파라미터가 추가된 시그니처

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`InquiryAgentServiceTest`에 추가:

```java
    @Test
    void forcesHumanReviewWhenRefundWasStaged() {
        // stage_refund 가 성공하면 모델이 needsHumanReview: false 를 줘도 무시한다
        when(stagedChangeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        givenLlmResponds(
                toolCall("check_order_status", "{\"orderId\":\"ORD-20260410-001\"}"),
                toolCall("stage_refund",
                        "{\"orderId\":\"ORD-20260410-001\",\"amount\":89000,\"reason\":\"불량\"}"),
                finalAnswer("환불 요청을 접수했습니다.", "REFUND", "MEDIUM", false)
        );

        AgentResult result = agentService.run(inquiry("ORD-20260410-001 불량이라 환불해주세요"), List.of());

        AgentResult.FinalAnswer answer = (AgentResult.FinalAnswer) result;
        assertThat(answer.needsHumanReview()).isTrue();
    }
```

> 이 테스트는 인터셉터 없이(`List.of()`) 돌아가는 기본 `agentService`를 쓰므로 `ctx.markStagedChange()`가 호출되지 않는다. `RefundGuardrailInterceptor`를 실제로 넣은 서비스가 필요하다. 따라서 테스트 안에서 서비스를 새로 만든다:

```java
    @Test
    void forcesHumanReviewWhenRefundWasStaged() {
        when(stagedChangeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        InMemoryOrderRepository orders = new InMemoryOrderRepository();
        InquiryAgentService service = new InquiryAgentService(
                llmClient, manualRetrievalService, promptFactory, new ObjectMapper(),
                orders, new InMemoryFaqRepository(),
                List.of(new OrderProvenanceInterceptor(),
                        new RefundGuardrailInterceptor(orders, stagedChangeRepository)),
                noopTracer, stagedChangeRepository);
        when(stagedChangeRepository.existsByOrderIdAndStatus(any(), any())).thenReturn(false);
        givenLlmResponds(
                toolCall("check_order_status", "{\"orderId\":\"ORD-20260410-001\"}"),
                toolCall("stage_refund",
                        "{\"orderId\":\"ORD-20260410-001\",\"amount\":89000,\"reason\":\"불량\"}"),
                finalAnswer("환불 요청을 접수했습니다.", "REFUND", "MEDIUM", false)
        );

        AgentResult result = service.run(inquiry("ORD-20260410-001 불량이라 환불해주세요"), List.of());

        assertThat(((AgentResult.FinalAnswer) result).needsHumanReview()).isTrue();
    }
```

import 추가: `com.aicsassistant.analysis.agent.interceptor.OrderProvenanceInterceptor`, `...interceptor.RefundGuardrailInterceptor`.
(`ORD-20260410-001`은 `cust-001` 소유, 상태 `배송중`, 결제금액 89,000원이므로 가드레일 4종을 모두 통과한다.)

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

Run: `./gradlew test --tests "com.aicsassistant.analysis.agent.InquiryAgentServiceTest.forcesHumanReviewWhenRefundWasStaged"`
Expected: FAIL — `needsHumanReview`가 `false`

- [ ] **Step 3: `buildFinalAnswer`에 ctx를 넘겨 강제한다**

`buildFinalAnswer` 시그니처와 본문:

```java
    private AgentResult.FinalAnswer buildFinalAnswer(
            JsonNode node, List<AgentStep> steps, List<RetrievedManualChunkDto> chunks,
            int totalTokens, ToolCallContext ctx) {
        AgentResult.FinalAnswer answer = new AgentResult.FinalAnswer(
                requiredText(node, "finalAnswer"),
                validCategory(requiredText(node, "category")),
                validUrgency(requiredText(node, "urgency")),
                node.path("needsHumanReview").asBoolean(true),
                node.path("needsEscalation").asBoolean(false),
                node.path("fraudRiskFlag").asBoolean(false),
                node.path("reason").asText(""),
                List.copyOf(steps),
                chunks,
                totalTokens
        );
        // 제안이 접수된 실행은 무조건 상담사가 본다 — 프롬프트 지시에 맡기지 않는다
        return ctx.stagedChange() ? answer.withHumanReview() : answer;
    }
```

호출 지점 2곳을 고친다:

1. 정상 종료 (`runAgentLoop` 루프 안):
```java
                    AgentResult.FinalAnswer result = buildFinalAnswer(
                            node, steps, searchTool.getCollectedChunks(), totalTokens, callContext);
```

2. 강제 종료 (`forceFinalAnswerWithoutTools`) — 이 메서드에 `ToolCallContext ctx` 파라미터를 추가하고 `runAgentLoop`의 호출 지점에서 `callContext`를 넘긴다:
```java
        return forceFinalAnswerWithoutTools(inquiry, messages, steps, searchTool, totalTokens, agentSpan, callContext);
```
```java
            if (node.has("finalAnswer")) {
                answer = buildFinalAnswer(node, steps, searchTool.getCollectedChunks(), tokens, ctx)
                        .withHumanReview();
            }
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests "com.aicsassistant.analysis.agent.*"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/aicsassistant/analysis/agent/InquiryAgentService.java \
        src/test/java/com/aicsassistant/analysis/agent/InquiryAgentServiceTest.java
git commit -m "환불 제안이 접수된 실행은 상담사 검토를 코드로 강제

모델이 needsHumanReview: false 를 반환해도 ctx.stagedChange() 가 true 면 무시한다.
프롬프트 지시에 맡기지 않는 이 프로젝트의 기존 방식을 따른다."
```

---

### Task 6: 승인·거부 서비스와 API

**Files:**
- Create: `src/main/java/com/aicsassistant/staging/dto/StagedChangeDecisionRequest.java`
- Create: `src/main/java/com/aicsassistant/staging/dto/StagedChangeResponse.java`
- Create: `src/main/java/com/aicsassistant/staging/application/StagedChangeApprovalService.java`
- Create: `src/main/java/com/aicsassistant/staging/api/StagedChangeController.java`
- Modify: `src/main/java/com/aicsassistant/order/InMemoryOrderRepository.java`
- Test: `src/test/java/com/aicsassistant/staging/application/StagedChangeApprovalServiceTest.java`

**Interfaces:**
- Consumes: Task 1 전부, `InquiryRepository`, `InquiryMessageRepository`
- Produces:
  - `StagedChangeApprovalService#approve(Long inquiryId, Long changeId, StagedChangeDecisionRequest req)` → `StagedChangeResponse`
  - `StagedChangeApprovalService#reject(Long inquiryId, Long changeId, StagedChangeDecisionRequest req)` → `StagedChangeResponse`
  - `StagedChangeApprovalService#findByInquiry(Long inquiryId)` → `List<StagedChangeResponse>`
  - `InMemoryOrderRepository#markRefunded(String orderId)` → `void`
  - `StagedChangeResponse(Long id, String changeType, String orderId, int amount, String reason, String policyBasis, String status, String decidedBy, LocalDateTime decidedAt, String decisionNote, LocalDateTime createdAt)`

- [ ] **Step 1: 주문 저장소를 가변으로 바꾸고 `markRefunded`를 추가한다**

`InMemoryOrderRepository`:

```java
    // ponytail: static mutable — 데모 전체가 공유하고 재시작하면 초기화된다.
    // 실제 서비스에서는 주문 도메인 API 호출로 대체된다.
    private static final Map<String, OrderInfo> ORDERS = new ConcurrentHashMap<>(Map.ofEntries(
        ... 기존 엔트리 그대로 ...
    ));
```

파일 끝에 추가:

```java
    /** 환불 승인 실행 — 주문 상태만 바꾼다. 금액 이력은 남기지 않는다(mock 한계). */
    public void markRefunded(String orderId) {
        ORDERS.computeIfPresent(orderId, (id, o) -> new OrderInfo(
                o.orderId(), o.productName(), "환불완료", o.amount(), o.orderedAt(),
                o.courier(), o.trackingNumber(), o.estimatedDelivery(), o.note()));
    }
```

import 추가: `java.util.concurrent.ConcurrentHashMap`.

- [ ] **Step 2: DTO 2개를 만든다**

`src/main/java/com/aicsassistant/staging/dto/StagedChangeDecisionRequest.java`:

```java
package com.aicsassistant.staging.dto;

import jakarta.validation.constraints.NotBlank;

/** 거부 시 {@code decisionNote}는 필수 — 도메인에서 검증한다. */
public record StagedChangeDecisionRequest(
        @NotBlank String decidedBy,
        String decisionNote
) {}
```

`src/main/java/com/aicsassistant/staging/dto/StagedChangeResponse.java`:

```java
package com.aicsassistant.staging.dto;

import com.aicsassistant.staging.domain.StagedChange;
import java.time.LocalDateTime;

public record StagedChangeResponse(
        Long id,
        String changeType,
        String orderId,
        int amount,
        String reason,
        String policyBasis,
        String status,
        String decidedBy,
        LocalDateTime decidedAt,
        String decisionNote,
        LocalDateTime createdAt
) {
    public static StagedChangeResponse from(StagedChange change) {
        return new StagedChangeResponse(
                change.getId(),
                change.getChangeType().name(),
                change.getOrderId(),
                change.getAmount(),
                change.getReason(),
                change.getPolicyBasis(),
                change.getStatus().name(),
                change.getDecidedBy(),
                change.getDecidedAt(),
                change.getDecisionNote(),
                change.getCreatedAt());
    }
}
```

- [ ] **Step 3: 실패하는 통합 테스트를 쓴다**

`src/test/java/com/aicsassistant/staging/application/StagedChangeApprovalServiceTest.java`:

```java
package com.aicsassistant.staging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static com.aicsassistant.staging.domain.RefundGuardrails.ALREADY_REFUNDED_STATUS;
import static com.aicsassistant.staging.domain.RefundGuardrails.REFUND_BLOCKING_STATUSES;

import com.aicsassistant.common.exception.ApiException;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryMessage;
import com.aicsassistant.inquiry.domain.InquiryMessageRole;
import com.aicsassistant.inquiry.infra.InquiryMessageRepository;
import com.aicsassistant.inquiry.infra.InquiryRepository;
import com.aicsassistant.order.InMemoryOrderRepository;
import com.aicsassistant.staging.domain.ChangeType;
import com.aicsassistant.staging.domain.StagedChange;
import com.aicsassistant.staging.domain.StagedChangeStatus;
import com.aicsassistant.staging.dto.StagedChangeDecisionRequest;
import com.aicsassistant.staging.dto.StagedChangeResponse;
import com.aicsassistant.staging.infra.StagedChangeRepository;
import com.aicsassistant.support.PostgresVectorIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class StagedChangeApprovalServiceTest extends PostgresVectorIntegrationTest {

    private static final String ORDER_ID = "ORD-20260405-002";   // cust-001 소유, 배송완료, 45,000원

    @Autowired StagedChangeApprovalService approvalService;
    @Autowired StagedChangeRepository stagedChangeRepository;
    @Autowired InquiryRepository inquiryRepository;
    @Autowired InquiryMessageRepository messageRepository;
    @Autowired InMemoryOrderRepository orderRepository;

    private StagedChange pendingProposal(int amount) {
        Long inquiryId = inquiryRepository
                .save(Inquiry.create("cust-001", "문의", "환불 요청", null, null, ORDER_ID)).getId();
        return stagedChangeRepository.save(StagedChange.propose(
                inquiryId, ChangeType.REFUND, ORDER_ID, amount, "배송완료 4일 경과", "반품 정책 3조"));
    }

    @Test
    void approveExecutesRefundAndNotifiesCustomer() {
        StagedChange proposal = pendingProposal(45_000);

        StagedChangeResponse response = approvalService.approve(
                proposal.getInquiryId(), proposal.getId(),
                new StagedChangeDecisionRequest("counselor-demo", null));

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.decidedBy()).isEqualTo("counselor-demo");

        StagedChange reloaded = stagedChangeRepository.findById(proposal.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(StagedChangeStatus.APPROVED);
        assertThat(reloaded.getDecidedAt()).isNotNull();

        assertThat(orderRepository.findById(ORDER_ID, "cust-001").orElseThrow().status())
                .isEqualTo("환불완료");

        List<InquiryMessage> messages =
                messageRepository.findByInquiryIdOrderByCreatedAtAsc(proposal.getInquiryId());
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getRole()).isEqualTo(InquiryMessageRole.AI);
        assertThat(messages.get(0).getContent()).contains("45,000").contains("환불");
    }

    @Test
    void rejectLeavesOrderAndSendsNoMessage() {
        StagedChange proposal = pendingProposal(45_000);

        StagedChangeResponse response = approvalService.reject(
                proposal.getInquiryId(), proposal.getId(),
                new StagedChangeDecisionRequest("counselor-demo", "배송 기록과 불일치"));

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.decisionNote()).isEqualTo("배송 기록과 불일치");
        assertThat(orderRepository.findById(ORDER_ID, "cust-001").orElseThrow().status())
                .isEqualTo("배송완료");
        assertThat(messageRepository.findByInquiryIdOrderByCreatedAtAsc(proposal.getInquiryId())).isEmpty();
    }

    @Test
    void secondDecisionIsRejected() {
        StagedChange proposal = pendingProposal(45_000);
        approvalService.approve(proposal.getInquiryId(), proposal.getId(),
                new StagedChangeDecisionRequest("counselor-demo", null));

        assertThatThrownBy(() -> approvalService.approve(proposal.getInquiryId(), proposal.getId(),
                new StagedChangeDecisionRequest("other", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ALREADY_DECIDED");
    }

    @Test
    void rejectsMismatchedInquiryId() {
        StagedChange proposal = pendingProposal(45_000);
        Long otherInquiryId = inquiryRepository.save(Inquiry.create("cust-002", "다른 문의", "내용")).getId();

        assertThatThrownBy(() -> approvalService.approve(otherInquiryId, proposal.getId(),
                new StagedChangeDecisionRequest("counselor-demo", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("STAGED_CHANGE_NOT_FOUND");
    }

    @Test
    void reChecksGuardrailsAtApprovalTime() {
        StagedChange proposal = pendingProposal(45_000);
        // 제안 이후 주문이 이미 환불되었다면 승인이 거절되어야 한다
        orderRepository.markRefunded(ORDER_ID);

        assertThatThrownBy(() -> approvalService.approve(proposal.getInquiryId(), proposal.getId(),
                new StagedChangeDecisionRequest("counselor-demo", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("GUARDRAIL_FAILED");

        assertThat(stagedChangeRepository.findById(proposal.getId()).orElseThrow().getStatus())
                .isEqualTo(StagedChangeStatus.PENDING);
    }

    @Test
    void findByInquiryReturnsProposals() {
        StagedChange proposal = pendingProposal(45_000);

        List<StagedChangeResponse> found = approvalService.findByInquiry(proposal.getInquiryId());

        assertThat(found).hasSize(1);
        assertThat(found.get(0).orderId()).isEqualTo(ORDER_ID);
        assertThat(found.get(0).status()).isEqualTo("PENDING");
    }
}
```

> `markRefunded`가 static 상태를 바꾸므로 이 테스트 클래스는 순서 의존이 생긴다. `reChecksGuardrailsAtApprovalTime`이 `ORD-20260405-002`를 `환불완료`로 바꿔 다른 테스트를 깨뜨릴 수 있다. `@AfterEach`로 원상복구한다:

```java
    @org.junit.jupiter.api.AfterEach
    void restoreOrderState() {
        orderRepository.resetForTest();
    }
```

`InMemoryOrderRepository`에 테스트용 복구 메서드를 추가한다:

```java
    /** ponytail: static mutable 상태를 테스트 간 격리하기 위한 복구 훅. 운영 코드에서 호출하지 않는다. */
    public void resetForTest() {
        ORDERS.clear();
        ORDERS.putAll(INITIAL_ORDERS);
    }
```

이를 위해 초기 엔트리를 별도 상수로 분리한다:

```java
    private static final Map<String, OrderInfo> INITIAL_ORDERS = Map.ofEntries( ... 기존 엔트리 ... );
    private static final Map<String, OrderInfo> ORDERS = new ConcurrentHashMap<>(INITIAL_ORDERS);
```

- [ ] **Step 4: 테스트를 돌려 실패를 확인한다**

Run: `./gradlew test --tests "com.aicsassistant.staging.application.StagedChangeApprovalServiceTest"`
Expected: FAIL — `StagedChangeApprovalService` 없음

- [ ] **Step 5: 승인 서비스를 구현한다**

`src/main/java/com/aicsassistant/staging/application/StagedChangeApprovalService.java`:

```java
package com.aicsassistant.staging.application;

import com.aicsassistant.common.exception.ApiException;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.inquiry.domain.InquiryMessage;
import com.aicsassistant.inquiry.domain.InquiryMessageRole;
import com.aicsassistant.inquiry.infra.InquiryMessageRepository;
import com.aicsassistant.inquiry.infra.InquiryRepository;
import com.aicsassistant.order.InMemoryOrderRepository;
import com.aicsassistant.order.InMemoryOrderRepository.OrderInfo;
import com.aicsassistant.staging.domain.StagedChange;
import com.aicsassistant.staging.dto.StagedChangeDecisionRequest;
import com.aicsassistant.staging.dto.StagedChangeResponse;
import com.aicsassistant.staging.infra.StagedChangeRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상담사의 승인 표면. 에이전트가 올린 제안은 여기를 통해서만 실행된다.
 *
 * <p>가드레일을 승인 시점에 다시 검사한다 — 제안 시점과 승인 시점 사이에 주문 상태가 바뀔 수 있다.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StagedChangeApprovalService {

    private final StagedChangeRepository stagedChangeRepository;
    private final InquiryRepository inquiryRepository;
    private final InquiryMessageRepository messageRepository;
    private final InMemoryOrderRepository orderRepository;

    public List<StagedChangeResponse> findByInquiry(Long inquiryId) {
        return stagedChangeRepository.findByInquiryIdOrderByCreatedAtDesc(inquiryId).stream()
                .map(StagedChangeResponse::from)
                .toList();
    }

    @Transactional
    public StagedChangeResponse approve(Long inquiryId, Long changeId, StagedChangeDecisionRequest request) {
        StagedChange change = loadForInquiry(inquiryId, changeId);
        Inquiry inquiry = loadInquiry(inquiryId);

        reCheckGuardrails(change, inquiry);

        change.approve(request.decidedBy(), request.decisionNote());
        // ponytail: DB 쓰기(제안 상태·알림 메시지)는 이 트랜잭션 안이지만 mock 주문 변경은 밖이다.
        // 실행을 마지막에 두어 실무상 어긋날 확률을 없앴을 뿐, 실제 결제 시스템이면 아웃박스가 필요하다.
        orderRepository.markRefunded(change.getOrderId());
        messageRepository.save(InquiryMessage.of(inquiryId, InquiryMessageRole.AI,
                "요청하신 환불이 승인되어 처리되었습니다. 주문 %s · 환불 금액 %,d원입니다. 카드 취소는 2~3 영업일이 소요될 수 있습니다."
                        .formatted(change.getOrderId(), change.getAmount())));

        log.info("[StagedChange approved] changeId={} inquiryId={} orderId={} amount={} by={}",
                changeId, inquiryId, change.getOrderId(), change.getAmount(), request.decidedBy());
        return StagedChangeResponse.from(change);
    }

    @Transactional
    public StagedChangeResponse reject(Long inquiryId, Long changeId, StagedChangeDecisionRequest request) {
        StagedChange change = loadForInquiry(inquiryId, changeId);

        change.reject(request.decidedBy(), request.decisionNote());

        log.info("[StagedChange rejected] changeId={} inquiryId={} by={} note={}",
                changeId, inquiryId, request.decidedBy(), request.decisionNote());
        return StagedChangeResponse.from(change);
    }

    /**
     * 승인 시점 재검사. provenance 는 에이전트 실행 세션 개념이라 대응물이 없고, 중복 검사는 이 제안
     * 자신이 유일한 PENDING 이라 무의미하므로 금액·주문상태 2종만 본다.
     */
    private void reCheckGuardrails(StagedChange change, Inquiry inquiry) {
        OrderInfo order = orderRepository
                .findById(change.getOrderId(), inquiry.getCustomerIdentifier())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "GUARDRAIL_FAILED",
                        "주문 정보를 확인할 수 없어 승인할 수 없습니다. (GUARDRAIL_FAILED)"));

        if (REFUND_BLOCKING_STATUSES.contains(order.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GUARDRAIL_FAILED",
                    "주문 상태가 [" + order.status() + "]로 바뀌어 승인할 수 없습니다. (GUARDRAIL_FAILED)");
        }
        if (order.status().equals(ALREADY_REFUNDED_STATUS)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GUARDRAIL_FAILED",
                    "이미 환불된 주문입니다. (GUARDRAIL_FAILED)");
        }
        if (change.getAmount() > order.amount()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GUARDRAIL_FAILED",
                    "제안 금액이 결제금액을 초과해 승인할 수 없습니다. (GUARDRAIL_FAILED)");
        }
    }

    private StagedChange loadForInquiry(Long inquiryId, Long changeId) {
        StagedChange change = stagedChangeRepository.findById(changeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "STAGED_CHANGE_NOT_FOUND",
                        "제안을 찾을 수 없습니다. (STAGED_CHANGE_NOT_FOUND)"));
        // 경로 위조로 남의 문의 제안을 승인하는 것을 막는다
        if (!change.getInquiryId().equals(inquiryId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "STAGED_CHANGE_NOT_FOUND",
                    "제안을 찾을 수 없습니다. (STAGED_CHANGE_NOT_FOUND)");
        }
        return change;
    }

    private Inquiry loadInquiry(Long inquiryId) {
        return inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INQUIRY_NOT_FOUND",
                        "Inquiry not found"));
    }
}
```

> 거부 상태 목록은 Task 1의 `RefundGuardrails` 상수를 static import 해서 쓴다. 제안 시점(인터셉터)과 승인 시점(이 서비스)이 같은 목록을 보게 하려는 것이며, 두 패키지 모두 `staging.domain`을 참조하는 방향이라 패키지 순환이 없다.

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests "com.aicsassistant.staging.application.StagedChangeApprovalServiceTest"`
Expected: PASS (6건)

- [ ] **Step 7: 컨트롤러를 만든다**

`src/main/java/com/aicsassistant/staging/api/StagedChangeController.java`:

```java
package com.aicsassistant.staging.api;

import com.aicsassistant.staging.application.StagedChangeApprovalService;
import com.aicsassistant.staging.dto.StagedChangeDecisionRequest;
import com.aicsassistant.staging.dto.StagedChangeResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inquiries/{inquiryId}/staged-changes")
@RequiredArgsConstructor
public class StagedChangeController {

    private final StagedChangeApprovalService approvalService;

    @GetMapping
    public List<StagedChangeResponse> list(@PathVariable Long inquiryId) {
        return approvalService.findByInquiry(inquiryId);
    }

    @PostMapping("/{changeId}/approve")
    public StagedChangeResponse approve(
            @PathVariable Long inquiryId,
            @PathVariable Long changeId,
            @Valid @RequestBody StagedChangeDecisionRequest request) {
        return approvalService.approve(inquiryId, changeId, request);
    }

    @PostMapping("/{changeId}/reject")
    public StagedChangeResponse reject(
            @PathVariable Long inquiryId,
            @PathVariable Long changeId,
            @Valid @RequestBody StagedChangeDecisionRequest request) {
        return approvalService.reject(inquiryId, changeId, request);
    }
}
```

- [ ] **Step 8: 전체 테스트를 돌린다**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/aicsassistant/staging src/main/java/com/aicsassistant/order src/test/java/com/aicsassistant/staging
git commit -m "환불 제안 승인·거부 API 와 실행 추가

승인: inquiryId 일치 확인 → PENDING 확인 → 가드레일 재검사 → mock 주문 환불완료 →
고객 알림 저장 → APPROVED 기록. 거부는 실행 없이 사유만 남긴다.

가드레일을 승인 시점에 다시 보는 이유: 제안 이후 주문 상태가 바뀔 수 있다.
InMemoryOrderRepository 를 가변으로 바꿨다 — static mutable 이라 데모 전체가 공유한다."
```

---

### Task 7: 상담사 화면

**Files:**
- Modify: `src/main/java/com/aicsassistant/ui/viewmodel/InquiryDetailViewModel.java`
- Modify: `src/main/java/com/aicsassistant/ui/application/InquiryDetailAssembler.java`
- Modify: `src/main/java/com/aicsassistant/ui/controller/CounselorViewController.java`
- Modify: `src/main/resources/templates/inquiries/detail.html`
- Test: `src/test/java/com/aicsassistant/ui/application/InquiryDetailAssemblerTest.java` (1건 추가)

**Interfaces:**
- Consumes: `StagedChangeApprovalService#findByInquiry` (Task 6), `StagedChangeResponse` (Task 6)
- Produces: `InquiryDetailViewModel.from(InquiryDetailResponse, List<EvidenceChunkView>, List<InquiryMessage>, List<AgentStepView>, List<StagedChangeResponse>)`

- [ ] **Step 1: 액션 라벨 테스트를 추가한다 (실패해야 함)**

`InquiryDetailAssemblerTest`에 추가:

```java
    @Test
    void loadAgentSteps_labelsStageRefundStep() {
        String stepsJson = """
                [{"thought":"환불 제안","action":"stage_refund",\
                "actionInput":"{}","observation":"{\\"ok\\":true,\\"data\\":\\"환불 제안 #1 접수됨\\"}",\
                "referencedChunks":[]}]
                """;
        when(analysisLogService.getLatestAgentStepsJson(1L)).thenReturn(Optional.of(stepsJson));

        List<AgentStepView> steps = assembler.loadAgentSteps(1L);

        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).actionLabel()).isEqualTo("환불 제안");
    }
```

> 기존 테스트의 `@Mock AnalysisLogService analysisLogService` / `@InjectMocks InquiryDetailAssembler assembler` 필드를 그대로 쓴다. 메서드 이름은 그 파일의 관례(`loadAgentSteps_<상황>`)를 따랐다.

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

Run: `./gradlew test --tests "com.aicsassistant.ui.application.InquiryDetailAssemblerTest"`
Expected: FAIL — `actionLabel`이 `"stage_refund"` (원문)

- [ ] **Step 3: 라벨을 추가한다**

`InquiryDetailAssembler.toStepView`의 switch에 한 줄:

```java
        String label = switch (step.action()) {
            case "search_manual"      -> "정책 문서 검색";
            case "check_order_status" -> "주문 조회";
            case "search_faq"         -> "FAQ 검색";
            case "stage_refund"       -> "환불 제안";
            default                   -> step.action();
        };
```

> `search_faq` 라벨은 현재 없어서 원문(`search_faq`)이 화면에 노출된다. 같은 switch를 건드리는 지금 함께 채운다.

- [ ] **Step 4: ViewModel에 필드를 추가한다**

`InquiryDetailViewModel`:

```java
public record InquiryDetailViewModel(
        InquiryDetailResponse inquiry,
        List<EvidenceChunkView> evidenceChunks,
        List<MessageView> messages,
        List<AgentStepView> agentSteps,
        List<StagedChangeResponse> stagedChanges
) {
    public static InquiryDetailViewModel from(
            InquiryDetailResponse inquiry,
            List<EvidenceChunkView> evidenceChunks,
            List<InquiryMessage> messages,
            List<AgentStepView> agentSteps,
            List<StagedChangeResponse> stagedChanges
    ) {
        List<MessageView> messageViews = messages.stream()
                .map(m -> new MessageView(m.getRole(), m.getContent(), m.getCreatedAt()))
                .toList();
        return new InquiryDetailViewModel(inquiry, List.copyOf(evidenceChunks), messageViews,
                List.copyOf(agentSteps), List.copyOf(stagedChanges));
    }
```

import 추가: `com.aicsassistant.staging.dto.StagedChangeResponse`.

- [ ] **Step 5: 컨트롤러 조립 지점을 고친다**

`CounselorViewController.inquiryDetail` — 서비스를 주입하고 호출을 늘린다:

```java
    private final StagedChangeApprovalService stagedChangeApprovalService;
```

```java
        List<AgentStepView> agentSteps = inquiryDetailAssembler.loadAgentSteps(id);
        List<StagedChangeResponse> stagedChanges = stagedChangeApprovalService.findByInquiry(id);
        model.addAttribute("detail",
                InquiryDetailViewModel.from(inquiry, evidenceChunks, messages, agentSteps, stagedChanges));
```

- [ ] **Step 6: 화면에 카드와 이력을 넣는다**

`detail.html` — AI_PROCESSED 검토 블록(`th:if` 로 시작하는 블록, 99행 부근) **바로 위**에 삽입한다. SpEL 람다를 쓰지 않고 `th:each` + `th:if`만 사용한다:

```html
<!-- 승인 대기 중인 제안 -->
<div th:each="change : ${detail.stagedChanges}" th:if="${change.status == 'PENDING'}"
     class="card" style="border-left:4px solid #d97706;">
    <h3 style="margin:0 0 8px;">환불 제안 — 승인 대기</h3>
    <p style="margin:4px 0;">
        주문 <strong th:text="${change.orderId}">ORD-...</strong> ·
        <strong th:text="${#numbers.formatInteger(change.amount, 3, 'COMMA')} + '원'">0원</strong>
    </p>
    <p style="margin:4px 0;" class="muted">근거: <span th:text="${change.reason}"></span></p>
    <p style="margin:4px 0;" class="muted" th:if="${change.policyBasis != null}">
        정책: <span th:text="${change.policyBasis}"></span>
    </p>
    <p style="margin:8px 0 4px;" class="muted">
        아직 실행되지 않았습니다. 승인하면 주문이 환불 처리되고 고객에게 알림이 전송됩니다.
    </p>
    <div style="margin-top:8px;">
        <button class="button" type="button"
                th:attr="data-change-id=${change.id}"
                onclick="decideStagedChange(this.getAttribute('data-change-id'), 'approve')">승인</button>
        <button class="button secondary" type="button"
                th:attr="data-change-id=${change.id}"
                onclick="decideStagedChange(this.getAttribute('data-change-id'), 'reject')">거부</button>
    </div>
</div>

<!-- 결정된 제안 이력 -->
<div th:each="change : ${detail.stagedChanges}" th:if="${change.status != 'PENDING'}"
     class="card muted" style="font-size:0.9em;">
    환불 제안
    <span th:text="${change.orderId}"></span> ·
    <span th:text="${#numbers.formatInteger(change.amount, 3, 'COMMA')} + '원'"></span> —
    <strong th:text="${change.status == 'APPROVED' ? '승인' : '거부'}"></strong>
    (<span th:text="${change.decidedBy}"></span>,
    <span th:text="${#temporals.format(change.decidedAt, 'yyyy-MM-dd HH:mm')}"></span>)
    <span th:if="${change.decisionNote != null}"> · <span th:text="${change.decisionNote}"></span></span>
</div>
```

기존 `<script>` 블록 안(`confirmReview` 함수 근처)에 추가:

```javascript
        async function decideStagedChange(changeId, decision) {
            let note = null;
            if (decision === 'reject') {
                note = prompt('거부 사유를 입력하세요 (필수)');
                if (!note || !note.trim()) return;
            }
            if (decision === 'approve' && !confirm('환불을 승인하면 즉시 처리되고 고객에게 알림이 전송됩니다. 계속할까요?')) {
                return;
            }
            try {
                const res = await fetch(`/api/inquiries/${inquiryId}/staged-changes/${changeId}/${decision}`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ decidedBy: 'counselor-demo', decisionNote: note })
                });
                if (!res.ok) {
                    const err = await res.json().catch(() => ({}));
                    throw new Error(err.message || `처리 실패 (${res.status})`);
                }
                location.reload();
            } catch (e) {
                alert(e.message);
            }
        }
```

> `inquiryId`는 기존 스크립트 블록 273행의 `const inquiryId = [[${detail.inquiry.id}]];`를 그대로 재사용한다. 새로 선언하지 말 것 — 같은 블록 안이라 중복 선언은 스크립트를 깨뜨린다.

- [ ] **Step 7: 화면을 실제로 띄워 확인한다**

Run: `./gradlew bootRun --args='--spring.profiles.active=local'`
그다음 `/ui/inquiries/{id}`를 열어 대기 카드 → 승인 → 이력 전환과 고객 포털(`/app`)의 알림 메시지를 눈으로 확인한다.
Expected: 승인 후 주문 조회에 `환불완료`가 보이고, 고객 대화에 알림 메시지가 나타난다

- [ ] **Step 8: 전체 테스트를 돌린다**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/aicsassistant/ui src/main/resources/templates/inquiries/detail.html \
        src/test/java/com/aicsassistant/ui
git commit -m "상담사 화면에 환불 제안 카드와 결정 이력 추가

대기 중 제안은 승인/거부 버튼과 함께, 결정된 제안은 결정자·시각·사유와 함께 보인다.
승인 전에 '아직 실행되지 않았습니다' 를 명시하고 승인 시 확인창을 띄운다.
에이전트 스텝 라벨에 stage_refund → '환불 제안' 을 추가했다."
```

---

### Task 8: 문서 갱신

**Files:**
- Modify: `CLAUDE.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: 없음
- Produces: 없음

- [ ] **Step 1: `CLAUDE.md`의 툴 설계 원칙을 고친다**

현재:

```markdown
### 툴 설계 원칙
- 조회 툴은 에이전트가 자유롭게 호출 가능
- 상태 변경 툴(환불 실행 등)은 에이전트 툴셋에 포함하지 않고 상담사 승인 후 실행
```

바꾼 뒤:

```markdown
### 툴 설계 원칙
- 조회 툴은 에이전트가 자유롭게 호출 가능
- 쓰기 툴은 **staging만** 한다 — 에이전트는 제안을 올릴 수 있고, 실행은 상담사 승인 표면(`StagedChangeApprovalService`)에서만 일어난다
- 쓰기 툴의 가드레일은 프롬프트가 아니라 `ToolCallInterceptor`에서 코드로 검사한다 (provenance·금액·상태·중복)
- 툴 결과 문구에 "아직 실행되지 않았음"을 명시해 에이전트가 staging을 실행 완료로 오인하지 않게 한다
```

`## 주요 도메인 상태` 아래에 표를 하나 추가한다:

```markdown
### StagedChangeStatus
| 상태 | 설명 |
|------|------|
| `PENDING` | 에이전트가 제안, 상담사 승인 대기 |
| `APPROVED` | 상담사 승인 → 실행 완료 |
| `REJECTED` | 상담사 거부 (사유 필수) |
```

- [ ] **Step 2: `README.md` 아키텍처 mermaid에 staging 경로를 넣는다**

`subgraph Agent["ReAct Agent"]` 안에 툴을 추가:

```
        RefundTool["StageRefundTool\n환불 제안 (실행 아님)"]
```

`Interceptors` 노드 설명을 갱신:

```
        Interceptors["ToolCallInterceptor 체인\n(예산·고액 주문·provenance·환불 가드레일)"]
```

새 subgraph와 엣지를 추가:

```
    subgraph Staging["승인 게이트"]
        StagedChange[("staged_change\nPENDING → APPROVED/REJECTED")]
        Approval["StagedChangeApprovalService\n재검사 → 실행 → 고객 알림"]
    end

    AgentLoop --> Interceptors --> FaqTool & SearchTool & OrderTool & RefundTool
    RefundTool --> StagedChange
    AdminUI -->|승인/거부| Approval
    Approval --> StagedChange
    Approval --> InMemoryOrder
```

기존 `AgentLoop --> Interceptors --> FaqTool & SearchTool & OrderTool` 줄을 위 버전으로 대체한다.

`## 주요 기능` 표에 한 줄 추가:

```markdown
| **승인 게이트** | AI가 환불을 제안(staging)하고 상담사 승인 후에만 실행 — 가드레일 4종을 코드로 검사 |
```

- [ ] **Step 3: 커밋**

```bash
git add CLAUDE.md README.md
git commit -m "docs: 승인 게이트 도입에 맞춰 툴 설계 원칙과 아키텍처 갱신

'상태 변경 툴은 툴셋에 넣지 않는다' → '쓰기 툴은 staging 만 하고 실행은 승인 표면에서만'.
원칙은 유지되고 표현이 정확해진다."
```

---

## 완료 확인

- [ ] `./gradlew test` 전체 통과
- [ ] `/ui/inquiries/{id}`에서 제안 카드 → 승인 → 이력 전환이 보인다
- [ ] `/app`에서 고객이 승인 알림을 본다
- [ ] 승인 후 `check_order_status`가 `환불완료`를 반환한다
- [ ] 같은 주문에 두 번째 제안이 차단된다 (가드레일 4)
- [ ] 조회하지 않은 주문에 대한 제안이 차단된다 (가드레일 1)
