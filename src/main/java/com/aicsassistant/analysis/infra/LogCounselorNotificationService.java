package com.aicsassistant.analysis.infra;

import com.aicsassistant.analysis.application.CounselorNotificationService;
import com.aicsassistant.inquiry.domain.Inquiry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LogCounselorNotificationService implements CounselorNotificationService {

    private static final Logger log = LoggerFactory.getLogger(LogCounselorNotificationService.class);

    @Override
    public void notifyHumanReviewRequired(Inquiry inquiry, String reason) {
        log.warn("[검토 필요] inquiryId={} title='{}' category={} urgency={} reason={}",
                inquiry.getId(), inquiry.getTitle(),
                inquiry.getCategory(), inquiry.getUrgency(), reason);
    }

    @Override
    public void notifyEscalationRequired(Inquiry inquiry, String reason) {
        log.error("[에스컬레이션] inquiryId={} title='{}' category={} urgency={} reason={}",
                inquiry.getId(), inquiry.getTitle(),
                inquiry.getCategory(), inquiry.getUrgency(), reason);
    }
}
