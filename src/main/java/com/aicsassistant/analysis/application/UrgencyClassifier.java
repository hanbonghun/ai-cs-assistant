package com.aicsassistant.analysis.application;

import com.aicsassistant.analysis.dto.UrgencyResultDto;
import org.springframework.stereotype.Service;

@Service
public class UrgencyClassifier {

    public UrgencyResultDto classify(InquiryClassifier.ClassificationResult classificationResult) {
        return classificationResult.urgency();
    }
}
