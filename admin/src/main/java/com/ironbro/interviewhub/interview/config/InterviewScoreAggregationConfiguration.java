package com.ironbro.interviewhub.interview.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "xunzhi-agent.interview.score-aggregation")
public class InterviewScoreAggregationConfiguration {

    private Boolean enable = true;
    private String defaultChainId = "score_aggregation_chain";
    private Boolean failOpen = true;
    private String ruleVersion = "v1.0.0";
    private Map<String, Integer> anchorWeights = defaultAnchorWeights();
    private Map<String, Double> statusFactors = defaultStatusFactors();
    private String criticalAnchorId = "correctness";
    private Integer criticalErrorCap = 40;
    private Integer excellentThreshold = 85;
    private Integer qualifiedThreshold = 60;

    private static Map<String, Integer> defaultAnchorWeights() {
        Map<String, Integer> weights = new LinkedHashMap<>();
        weights.put("correctness", 30);
        weights.put("completeness", 25);
        weights.put("logic", 20);
        weights.put("depth", 15);
        weights.put("clarity", 10);
        return weights;
    }

    private static Map<String, Double> defaultStatusFactors() {
        Map<String, Double> factors = new LinkedHashMap<>();
        factors.put("met", 1D);
        factors.put("partial", 0.5D);
        factors.put("missing", 0D);
        factors.put("contradicted", 0D);
        return factors;
    }
}
