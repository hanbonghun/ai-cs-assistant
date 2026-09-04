# Architecture Decision Records

이 프로젝트의 되돌리기 어려운 결정과 그 근거를 한 건씩 기록한다.
코드는 *무엇을* 하는지 말하고, 여기 문서는 *왜 그렇게 정했는지*를 말한다.

| # | 결정 | 상태 |
|---|---|---|
| [0001](0001-react-loop-without-framework.md) | LLM 프레임워크 없이 ReAct 루프를 직접 구현한다 | 채택 |
| [0002](0002-hybrid-rag-retrieval.md) | 정책 검색은 vector 단독이 아니라 Hybrid 로 한다 | 채택 |
| [0003](0003-tool-policy-in-interceptors.md) | 툴 정책은 프롬프트가 아니라 인터셉터에서 코드로 강제한다 | 채택 |
| [0004](0004-fence-untrusted-customer-text.md) | 고객이 쓴 텍스트를 울타리로 분리한다 | 채택 |
| [0005](0005-owner-scoped-order-lookup.md) | 주문 조회는 소유자 스코프로만 하고, 타인 주문은 NOT_FOUND 로 응답한다 | 채택 |
| [0006](0006-never-lose-an-inquiry.md) | 에이전트가 실패해도 문의를 유실하지 않는다 | 채택 |
| [0007](0007-writes-stage-only.md) | 에이전트의 쓰기 툴은 staging 만 하고, 실행은 승인 표면에서만 일어난다 | 채택 |
| [0008](0008-counselor-owns-refund-amount.md) | 환불 금액의 최종 결정권은 상담사에게 있다 | 채택 |
| [0009](0009-no-external-calls-in-transactions.md) | 트랜잭션 안에서 외부 호출을 하지 않는다 | 채택 |

0001~0006 은 결정 당시 문서화하지 않아 2026-09-03 에 소급 기록했다.
