package com.aicsassistant.analysis.application;

import com.aicsassistant.inquiry.domain.Inquiry;

public interface CounselorNotificationService {

    void notifyHumanReviewRequired(Inquiry inquiry, String reason);

    void notifyEscalationRequired(Inquiry inquiry, String reason);
}
