# 0001. LLM 프레임워크 없이 ReAct 루프를 직접 구현한다

- 상태: 채택
- 결정 시점: 2026-04 (2026-09-03 소급 기록)

## 맥락

문의를 분류하고 정책을 찾아 답변하려면 모델이 여러 번 판단하며 툴을 호출해야 한다.
LangChain 같은 프레임워크를 쓰면 루프와 툴 추상화를 공짜로 얻는다.

## 결정

`InquiryAgentService` 에 ReAct 루프를 직접 구현한다. 프레임워크를 쓰지 않는다.
모델은 매 스텝에서 `{thought, action, actionInput}` / `{thought, followUpQuestion}` /
`{thought, finalAnswer, ...}` 중 하나를 JSON 으로 반환하고, 루프가 그것을 해석한다.

## 결과

- 스텝 상한(`MAX_STEPS`), 툴 예산, 인터셉터 훅, 관측성 span 을 우리가 원하는 지점에 정확히 둘 수 있다.
  이 통제권이 이후 결정 [0003](0003-tool-policy-in-interceptors.md) · [0006](0006-never-lose-an-inquiry.md) ·
  [0007](0007-writes-stage-only.md) 을 가능하게 했다 — 프레임워크의 루프 안에서는 어려웠을 것이다
- 툴 표면(설명·사용 시점·경계·입출력 형태·실패 시 행동)을 우리가 직접 렌더링하므로 프롬프트에 무엇이
  들어가는지 전부 보인다
- 대가: 프레임워크가 주는 것(스트리밍, 여러 provider 어댑터, 재시도 정책)을 필요할 때 직접 만들어야 한다.
  현재는 OpenAI 한 곳만 쓰므로 비용이 크지 않다
