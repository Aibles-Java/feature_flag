package org.aibles.feature_flag.controller.sdk;

import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.dto.response.FlagEvaluationResponse;
import org.aibles.feature_flag.service.EvaluationService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sdk")
@RequiredArgsConstructor
public class EvaluationController {

    private final EvaluationService evaluationService;

    @GetMapping("/flags")
    public List<FlagEvaluationResponse> getAllFlags(Authentication authentication) {
        Environment env = (Environment) authentication.getPrincipal();
        return evaluationService.getAllFlags(env);
    }

    @GetMapping("/flags/{flagKey}")
    public FlagEvaluationResponse getFlag(Authentication authentication,
                                          @PathVariable String flagKey) {
        Environment env = (Environment) authentication.getPrincipal();
        return evaluationService.getFlag(env, flagKey);
    }
}
