# 0002. 정책 검색은 vector 단독이 아니라 Hybrid 로 한다

- 상태: 채택
- 결정 시점: 2026-04~05 (2026-09-03 소급 기록)

## 맥락

초기 설계는 pgvector 코사인 유사도 단독 검색이었다. 87 케이스 골든셋으로 측정한 결과
hard 케이스(질문과 정책 문서의 표현이 크게 다른 경우) Recall@3 이 0.000 이었다.
한국어 질의가 정책 문서의 용어와 어휘가 어긋날 때 임베딩만으로는 걸리지 않았다.

## 결정

vector 검색과 `pg_trgm` 키워드 검색을 각각 수행하고 RRF(Reciprocal Rank Fusion)로 합친다.
한국어 질의는 `KoreanQueryPreprocessor` 로 어미·공통어를 제거하고,
vector floor gate 로 유사도가 바닥인 결과를 떨어뜨린다.

## 결과

- hard Recall@3 0.000 → 0.143, MRR 0.806 → 0.833. easy/medium Recall@1 은 1.0 유지
- negative 케이스(검색되면 안 되는 질의) NoMatchAccuracy 1.0 유지 — false positive 를 늘리지 않고 회복했다
- 검색 품질이 골든셋 회귀 테스트로 고정되므로 이후 변경의 효과를 숫자로 판정할 수 있다
- 대가: 검색 경로가 두 갈래가 되어 튜닝 표면이 늘었다(RRF 가중치, floor 임계, 전처리 규칙)
