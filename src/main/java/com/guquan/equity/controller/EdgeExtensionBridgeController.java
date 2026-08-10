package com.guquan.equity.controller;

import com.guquan.equity.model.EdgeExtensionCaptureRequest;
import com.guquan.equity.model.EdgeExtensionCaptureResult;
import com.guquan.equity.model.EdgeExtensionDeferRequest;
import com.guquan.equity.model.EdgeExtensionTargetView;
import com.guquan.equity.model.EdgeCaptchaSolveRequest;
import com.guquan.equity.model.EdgeCaptchaSolveResult;
import com.guquan.equity.model.EdgeCaptchaStatusView;
import com.guquan.equity.model.EdgeVerificationCandidateSelectionRequest;
import com.guquan.equity.model.EdgeVerificationCandidateView;
import com.guquan.equity.model.EdgeVerificationSessionClaimView;
import com.guquan.equity.model.EdgeVerificationSessionCreateRequest;
import com.guquan.equity.model.EdgeVerificationSessionStatusRequest;
import com.guquan.equity.model.EdgeVerificationSessionView;
import com.guquan.equity.service.EdgeExtensionBridgeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/edge-extension")
@CrossOrigin(originPatterns = {"chrome-extension://*", "edge-extension://*"},
        allowedHeaders = {"Content-Type", "X-Extension-Token"},
        methods = {})
public class EdgeExtensionBridgeController {

    private final EdgeExtensionBridgeService service;

    public EdgeExtensionBridgeController(EdgeExtensionBridgeService service) {
        this.service = service;
    }

    @PostMapping("/verification-sessions")
    public EdgeVerificationSessionView createVerificationSession(
            @RequestBody EdgeVerificationSessionCreateRequest request) {
        return service.createVerificationSession(request == null ? null : request.getJobId());
    }

    @GetMapping("/verification-sessions/{sessionId}")
    public EdgeVerificationSessionView verificationSession(@PathVariable String sessionId) {
        return service.verificationSession(sessionId);
    }

    @GetMapping("/verification-sessions/active")
    public ResponseEntity<EdgeVerificationSessionClaimView> activeVerificationSession() {
        EdgeVerificationSessionClaimView active = service.activeVerificationSession();
        return active == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(active);
    }

    @PostMapping("/verification-sessions/{sessionId}/status")
    public EdgeVerificationSessionView updateVerificationSession(
            @RequestHeader("X-Extension-Token") String accessToken,
            @PathVariable String sessionId,
            @RequestBody EdgeVerificationSessionStatusRequest request) {
        return service.updateVerificationSession(accessToken, sessionId, request);
    }

    @PostMapping("/verification-sessions/{sessionId}/selection")
    public EdgeVerificationSessionView selectCandidate(
            @PathVariable String sessionId,
            @RequestBody EdgeVerificationCandidateSelectionRequest request) {
        return service.selectCandidate(sessionId, request == null ? null : request.getCandidateId());
    }

    /**
     * The user has completed the official-site verification in the browser.
     * The extension will see this state transition and inspect the current
     * page again without submitting the enterprise name a second time.
     */
    @PostMapping("/verification-sessions/{sessionId}/resume")
    public EdgeVerificationSessionView resumeVerificationSession(@PathVariable String sessionId) {
        return service.resumeVerificationSession(sessionId);
    }

    @GetMapping("/verification-sessions/{sessionId}/selection")
    public ResponseEntity<EdgeVerificationCandidateView> selectedCandidate(
            @RequestHeader("X-Extension-Token") String accessToken,
            @PathVariable String sessionId) {
        EdgeVerificationCandidateView selection = service.selectedCandidate(accessToken, sessionId);
        return selection == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(selection);
    }

    @DeleteMapping("/verification-sessions/{sessionId}")
    public EdgeVerificationSessionView stopVerificationSession(@PathVariable String sessionId) {
        return service.stopVerificationSession(sessionId);
    }

    @GetMapping("/jobs/{jobId}/next")
    public EdgeExtensionTargetView next(@RequestHeader("X-Extension-Token") String accessToken,
            @PathVariable String jobId) {
        return service.nextTarget(accessToken, jobId);
    }

    @GetMapping("/captcha/status")
    public EdgeCaptchaStatusView captchaStatus() {
        return service.captchaStatus();
    }

    @PostMapping("/captcha/solve")
    public EdgeCaptchaSolveResult solveCaptcha(
            @RequestHeader("X-Extension-Token") String accessToken,
            @RequestBody EdgeCaptchaSolveRequest request) {
        return service.solveCaptcha(accessToken,
                request == null ? null : request.getJobId(), request);
    }

    @PostMapping("/jobs/{jobId}/companies/{companyId}/capture")
    public EdgeExtensionCaptureResult capture(@RequestHeader("X-Extension-Token") String accessToken,
            @PathVariable String jobId, @PathVariable Long companyId,
            @RequestBody EdgeExtensionCaptureRequest request) {
        return service.capture(accessToken, jobId, companyId, request);
    }

    @PostMapping("/jobs/{jobId}/companies/{companyId}/defer")
    public EdgeExtensionCaptureResult defer(@RequestHeader("X-Extension-Token") String accessToken,
            @PathVariable String jobId, @PathVariable Long companyId,
            @RequestBody(required = false) EdgeExtensionDeferRequest request) {
        return service.defer(accessToken, jobId, companyId, request == null ? null : request.getReason());
    }
}
