# Staged Refund + 상담사 승인 게이트 설계

- 날짜: 2026-09-03
- 상태: 승인됨 (구현 계획 대기)
- 참고: Anthropic commerce-agents 블루프린트의 approval gate 패턴 (`docs/safety.md`)

## 배경

현재 시스템은 상태 변경 액션을 **에이전트 툴셋에 아예 넣지 않는 방식**으로 안전을 확보한다.
안전하지만 기능이 없는 안전이다. 환불·취소 요청이 오면 에이전트는 `needsHumanReview: true`로
상담사에게 넘기고, 상담사는 시스템 밖에서 처리한다.

블루프린트는 한 단계 더 간다 — 모든 쓰기는 staged change 이고, 호스트의 승인 표면에서만
적용되며, 채팅으로 승인한다고 말해도 아무것도 설정되지 않는다. 여기에 가드레일이 겹친다.

이 프로젝트에는 승인 표면이 이미 있다: `POST /api/inquiries/{id}/review` → `Inquiry.confirmReview`.
상담사가 AI 초안을 고쳐 확정하는 흐름이다. 없는 것은 **액션 실행**이다.

## 결정 사항

| # | 결정 | 근거 |
|---|---|---|
| 1 | v1 액션은 **환불 하나** | 제안→가드레일→승인→실행을 한 액션에서 끝까지 완성한다. 액션이 늘어도 같은 틀에 툴만 추가하면 된다 |
| 2 | 승인 시 **mock 주문 상태 변경 + 고객 알림** | 데모에서 상태 변화가 눈에 보여야 승인 게이트가 실감난다. staged change 레코드가 그대로 감사 이력이 되므로 별도 감사 테이블은 두지 않는다 |
| 3 | 에이전트는 **`stage_refund` 툴**로 제안 | 기존 `ToolCallInterceptor` 체인이 가드레일 자리로 정확히 들어맞고, 제안 이유가 `agentSteps`에 남아 Langfuse에서 보인다. 쓰기 툴이지만 실행이 아니라 staging 이다 |
| 4 | 가드레일 **핵심 4종** (provenance / 금액 / 주문상태 / 중복) | 전부 데이터로 판정 가능해 정책 문서와 이중 정의가 생기지 않는다. 반환 기간(7일) 검사는 제외 — 7일이 정책 문서와 코드 두 곳에 정의되면 정책 변경 시 어긋난다 |
| 5b | **금액 최종 결정권은 상담사** — 승인 화면에서 제안 금액을 수정할 수 있다 | AI 금액은 초안이다. 승인/거부만 가능하면 "사람이 금액을 판단"하는 게 아니라 "AI 금액을 거부"하는 것만 가능해진다. 금액이 조금 다를 때 거부 후 시스템 밖에서 처리하게 되면 승인 게이트를 우회하는 경로가 생긴다 |
| 5 | **전용 승인 엔드포인트**, `InquiryStatus`는 불변 | staged change 가 자기 생애주기를 갖고, 문의 상태 머신은 건드리지 않는다. 답변 확정과 환불 승인이 독립이라 상담사가 환불만 승인하고 답변은 더 고칠 수 있다 |

## 도메인 & 스키마

새 패키지 `com.aicsassistant.staging` (`domain` / `application` / `api` / `infra`) — 기존 도메인 폴더 관례를 따른다.

```sql
create table if not exists staged_change (
    id bigserial primary key,
    inquiry_id bigint not null references inquiry(id),
    change_type varchar(20) not null,          -- 현재 REFUND 하나
    order_id varchar(50) not null,
    amount integer not null,                   -- KRW, OrderInfo.amount 와 동일 타입
    reason text not null,                      -- 에이전트가 낸 금액 산정 근거
    policy_basis text,                         -- 근거로 든 정책 조항 (없을 수 있음)
    status varchar(20) not null,               -- PENDING | APPROVED | REJECTED
    decided_by varchar(100),
    decided_at timestamp,
    decision_note text,
    created_at timestamp not null
);

-- 상담사가 제안 금액을 수정해 승인한 경우의 최종 금액. null 이면 제안 금액을 그대로 승인한 것이다.
-- 제안 금액(amount)은 AI 의 판단 이력으로 보존하므로 덮어쓰지 않는다.
alter table staged_change add column if not exists approved_amount integer;
create index if not exists idx_staged_change_order_pending
    on staged_change(order_id) where status = 'PENDING';
```

`schema.sql` 하단에 추가한다 (프로젝트 관용구: `create table if not exists` + `sql.init.mode: always`).

구성 요소:

- 엔티티 `StagedChange`
- enum `ChangeType` (`REFUND`), `StagedChangeStatus` (`PENDING`/`APPROVED`/`REJECTED`)
- `StagedChangeRepository` — 쿼리 메서드 2개: `existsByOrderIdAndStatus`, `findByInquiryIdOrderByCreatedAtDesc`

`change_type` 컬럼을 지금 두는 이유: v1은 환불뿐이라 `staged_refund` 테이블이 더 정직하지만,
두 번째 액션이 오면 라이브 DB에서 테이블 rename 마이그레이션을 해야 한다. 컬럼 하나가 그보다 싸다.
추상화는 만들지 않고 컬럼만 둔다.

부분 인덱스(`where status = 'PENDING'`)는 중복 가드레일 쿼리용이다. 대기 중 제안은 항상 소수라
인덱스가 작게 유지된다.

## provenance 수집 + 툴 & 가드레일

가드레일은 툴이 아니라 인터셉터에 둔다. 툴은 `execute(Input)`만 받아 `ToolCallContext`를 볼 수 없고,
기존 두 인터셉터(`ToolCallBudgetInterceptor`, `HighValueOrderInterceptor`)가 이미 이 자리에 있다.

```
ToolCallContext        + Set<String> observedOrderIds   (provenance)
                       + boolean stagedChange           (staging 발생 여부)

OrderProvenanceInterceptor  afterExecute: 성공한 check_order_status 의 orderId 를 ctx 에 기록
RefundGuardrailInterceptor  beforeExecute: stage_refund 인수 4종 검사 → 위반 시 차단
StageRefundTool             통과한 것만 StagedChange(PENDING) 저장
```

`StageRefundTool.Input(orderId, amount, reason, policyBasis)`. 툴은 실행마다 새로 만들어지며
`inquiryId`를 주입받는다 (`CheckOrderStatusTool`이 `customerIdentifier`를 받는 것과 같은 패턴).

성공 시 observation 데이터는 **제안이 접수되었을 뿐 실행되지 않았다**는 사실을 명시한다:
`"환불 제안 #<id> 접수됨 (주문 <orderId>, <amount>원). 아직 실행되지 않았으며 상담사 승인이 필요합니다."`
에이전트가 이를 실행 완료로 오인해 고객에게 "환불되었습니다"라고 답하는 것을 막는다.

가드레일과 에러 카테고리:

| 검사 | 카테고리 | 에이전트가 할 일 |
|---|---|---|
| provenance — 이번 실행에서 조회한 주문인가 | `PERMISSION` | 먼저 `check_order_status` 호출 |
| 금액 ≤ 결제금액 | `VALIDATION` | 금액 고쳐 재시도 가능 |
| 주문 상태 — 아래 목록이면 거부 | `PERMISSION` | `needsHumanReview: true` |
| 같은 주문 대기 중 제안 | `PERMISSION` | `needsHumanReview: true` |

거부 대상 주문 상태: `취소완료`, `취소처리중`, `반품완료`. 이미 환불이 끝났거나 진행 중인 건에
환불을 또 제안하는 것을 막는다.

`부분환불완료`는 **거부하지 않는다** — 남은 금액에 대한 추가 환불이 정당할 수 있다. 다만 금액
가드레일은 결제금액 전액을 상한으로 쓰므로 이미 환불된 몫을 차감하지 못한다. mock 데이터가
부분환불 금액을 구조적으로 갖고 있지 않기 때문이다(`note` 텍스트에만 있다). 이 경우 상담사가
승인 화면에서 판단한다 — 스펙상 의도된 한계이며 구현 시 `ponytail:` 주석으로 표시한다.

선주입 주문도 provenance 에 포함한다. `buildInitialMessage`는 `orderTool.execute`를 직접 호출해
인터셉터를 지나가지 않지만, 그 주문은 문의의 `relatedOrderId`이고 소유자 검증을 통과한 고객 자기
주문이라 정당하다. `callContext` 생성을 `buildInitialMessage` 호출보다 앞으로 옮기고 성공 시 기록한다.

staging 이 일어나면 `needsHumanReview`를 코드로 강제한다 — `ctx.stagedChange`가 true 면
`buildFinalAnswer`에서 `withHumanReview()`를 씌운다. 프롬프트 지시에 맡기지 않는 것이 이 프로젝트에서
확립한 방식이다.

툴이 4개가 되지만 `MAX_TOOL_CALLS_PER_RUN = 6` 예산 안에서 동작한다.

## 승인·거부 API와 실행

```
POST /api/inquiries/{inquiryId}/staged-changes/{changeId}/approve   {decidedBy, decisionNote?, approvedAmount?}
POST /api/inquiries/{inquiryId}/staged-changes/{changeId}/reject    {decidedBy, decisionNote}
```

`approvedAmount`를 생략하면 제안 금액을 그대로 승인한다. 값을 주면 그 금액이 최종 금액이 되고,
제안 금액은 `amount`에 그대로 남아 AI 판단과 사람 판단의 차이가 이력에 보인다.
실행·알림·재검사는 모두 **최종 금액**(`approvedAmount ?? amount`)을 기준으로 한다.

`StagedChangeApprovalService.approve()`:

1. 조회 후 `inquiryId` 일치 확인 — 경로 위조로 남의 문의 제안을 승인하는 것을 막는다
2. `status == PENDING` 확인 — 아니면 `ALREADY_DECIDED` 400 (멱등성)
3. 가드레일 **재검사** — 주문상태, 그리고 **최종 금액**이 결제금액 이하인지 — 제안 시점과 승인 시점 사이에 주문 상태가 바뀔 수 있다.
   블루프린트도 스테이징 시와 적용 시 두 번 검사한다.
   나머지 2종은 승인 시점에 재검사하지 않는다: provenance 는 에이전트 실행 세션 개념이라 승인
   시점에 대응물이 없고, 중복 검사는 이 제안 자신이 유일한 `PENDING` 이므로 무의미하다
4. 실행 — mock 주문을 `환불완료`로
5. 고객 알림 메시지 저장 (role `AI`)
6. `status = APPROVED`, `decidedBy`, `decidedAt` 기록

`reject()`는 3~5를 건너뛰고 `REJECTED` + `decisionNote`만 남긴다. 고객 알림은 없다 — 상담사가
답변 확정으로 전달한다.

`decisionNote`는 거부에서 **필수**, 승인에서 선택이다. 거부는 왜 거부했는지가 남아야 이력으로
쓸모가 있고, 승인은 제안 내용 자체가 근거다.

알림 메시지의 role 은 `AI`로 둔다. 사람이 결정한 건이라 고객 화면에 "AI 답변"으로 보이는 어색함이
있지만, `COUNSELOR` role 추가는 enum 값 + 템플릿 분기를 늘린다. 화면에서 어색하면 그때 추가한다.

### 데모의 한계 (코드에 `ponytail:` 주석으로 표시)

- `InMemoryOrderRepository.ORDERS`가 불변 맵이라 상태를 못 바꾼다. `ConcurrentHashMap`으로 감싸고
  `markRefunded(orderId)`가 `OrderInfo`를 새 record 로 교체한다. static mutable 이라 데모 전체가
  공유하고 재시작하면 초기화된다
- DB 두 건(staged change, message)은 한 트랜잭션이지만 mock 주문 변경은 그 밖이다. 실행을 마지막에
  두면 실무상 문제는 없으나, 실제 결제 시스템이면 아웃박스가 필요한 자리다

## UI

상담사 상세 화면(`inquiries/detail.html`)의 기존 AI_PROCESSED 검토 블록 위에 제안 카드를 얹는다 —
주문번호 · 금액 · 근거 · 정책 조항 + `[승인]` `[거부]` 버튼. 금액은 제안값이 채워진 입력창으로 두어
상담사가 그대로 승인하거나 고쳐서 승인할 수 있게 한다. 거부는 사유 입력. 결정된 제안은 아래에
이력으로(승인/거부, 결정자, 시각, 사유) 쌓인다. `InquiryDetailAssembler`와
`InquiryDetailViewModel`에 리스트 하나를 추가한다. 집계가 없어 SpEL 람다 금지 규칙에 걸리지 않는다.

유저 포털은 손대지 않는다 — 승인 알림이 `InquiryMessage`로 저장되어 기존 대화 렌더링에 그대로 나온다.

대시보드 지표(승인률 등)는 넣지 않는다. 필요해지면 `staged_change`에 쿼리 하나다.

## 테스트

| 종류 | 대상 | 케이스 |
|---|---|---|
| 단위 | `RefundGuardrailInterceptor` | 가드레일 4종 위반 각각 + 통과, 카테고리 매핑 |
| 단위 | `OrderProvenanceInterceptor` | 성공한 조회만 기록, 실패는 기록 안 함 |
| 단위 | `StageRefundTool` | PENDING 저장, 입력 검증 |
| 통합 | `StagedChangeApprovalService` | 승인 → 주문 상태·메시지·레코드 3자 일치 / 거부 / 재승인 400 / `inquiryId` 불일치 / 재검사 실패 |
| 단위 | `InquiryAgentService` | staging 발생 시 `needsHumanReview` 코드 강제 |

뷰 컨트롤러 테스트는 CLAUDE.md 우선순위대로 생략한다.

## 문서 갱신

`CLAUDE.md`의 툴 설계 원칙을 고친다. 현재 *"상태 변경 툴(환불 실행 등)은 에이전트 툴셋에 포함하지 않고
상담사 승인 후 실행"* 인데 `stage_refund`는 툴셋에 들어간다. *"쓰기 툴은 staging 만 하고 실행은 승인
표면에서만"* 으로 갱신한다 — 원칙은 유지되고 표현이 정확해진다.

README 아키텍처 mermaid 에 staging 경로를 반영한다.

## 범위 밖

- 취소·교환 액션 (v1은 환불만)
- 반환 기간(7일) 가드레일 — 정책 문서와 이중 정의를 피한다
- 별도 감사 테이블 — staged change 레코드가 이력이다
- 승인률 대시보드 지표
- `COUNSELOR` 메시지 role
- 실제 결제 시스템 연동 및 아웃박스
