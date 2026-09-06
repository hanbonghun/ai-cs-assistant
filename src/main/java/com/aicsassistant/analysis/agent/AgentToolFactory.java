package com.aicsassistant.analysis.agent;

import com.aicsassistant.analysis.agent.tool.CheckOrderStatusTool;
import com.aicsassistant.analysis.agent.tool.SearchManualTool;
import com.aicsassistant.analysis.agent.tool.StageRefundTool;
import com.aicsassistant.analysis.application.ManualRetrievalService;
import com.aicsassistant.inquiry.domain.Inquiry;
import com.aicsassistant.order.InMemoryOrderRepository;
import com.aicsassistant.staging.infra.StagedChangeRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 문의 한 건을 위한 도구 묶음을 만든다.
 *
 * <p>도구는 <b>실행마다 새로 만든다</b>. 싱글턴으로 둘 수 없는 이유가 도구마다 있다.
 * <ul>
 *   <li>{@link CheckOrderStatusTool} 은 고객 신원을 생성자에 묶는다 — 모델이 준 주문번호로
 *       남의 주문을 조회하지 못하게 하는 소유자 스코프의 근거다 (ADR 0005)</li>
 *   <li>{@link StageRefundTool} 은 문의 id 를 묶는다 — 제안이 어느 문의에 속하는지가 모델 입력이
 *       아니라 서버가 아는 값이어야 한다</li>
 *   <li>{@link SearchManualTool} 은 이번 실행에서 가져온 문서를 누적한다 — 분석 로그의 근거 목록이
 *       된다</li>
 * </ul>
 *
 * <p>이 클래스가 데이터 소스 셋을 대신 들고 있어서 {@link InquiryAgentService} 는 루프가 무엇을
 * 조회하는지 몰라도 된다. 도구를 하나 더 붙일 때 손대는 곳이 여기와 프롬프트뿐이다.
 */
@Component
@RequiredArgsConstructor
class AgentToolFactory {

    private final ManualRetrievalService manualRetrievalService;
    private final InMemoryOrderRepository orderRepository;
    private final StagedChangeRepository stagedChangeRepository;

    Toolset createFor(Inquiry inquiry) {
        return new Toolset(
                new SearchManualTool(manualRetrievalService),
                new CheckOrderStatusTool(orderRepository, inquiry.getCustomerIdentifier()),
                new StageRefundTool(stagedChangeRepository, inquiry.getId()));
    }

    /**
     * 한 실행이 쓰는 도구들. 루프가 {@code manual}·{@code order} 를 이름이 아니라 타입으로 잡아야
     * 해서 리스트가 아니라 record 로 돌려준다 — 전자는 수집한 문서를, 후자는 주문 선주입을 위해서다.
     */
    record Toolset(
            SearchManualTool manual,
            CheckOrderStatusTool order,
            StageRefundTool refund
    ) {

        /** 모델이 프롬프트에서 보는 순서. 이름 해석도 이 목록에서 한다. */
        List<AgentTool<?>> all() {
            return List.of(manual, order, refund);
        }
    }
}
