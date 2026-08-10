package com.guquan.equity.service;

import com.guquan.equity.model.CompanyBatchAutomationTarget;
import com.guquan.equity.model.CompanyBatchCompanyView;
import com.guquan.equity.model.CompanyBatchStatus;
import com.guquan.equity.model.CompanyAllSectionsView;
import com.guquan.equity.model.EdgeCaptchaSolveRequest;
import com.guquan.equity.model.EdgeCaptchaSolveResult;
import com.guquan.equity.model.EdgeCaptchaStatusView;
import com.guquan.equity.model.EdgeExtensionCaptureRequest;
import com.guquan.equity.model.EdgeExtensionCaptureResult;
import com.guquan.equity.model.EdgeExtensionTargetView;
import com.guquan.equity.model.EdgeVerificationCandidateView;
import com.guquan.equity.model.EdgeVerificationSessionClaimView;
import com.guquan.equity.model.EdgeVerificationSessionStatusRequest;
import com.guquan.equity.model.EdgeVerificationSessionView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.guquan.equity.provider.ChaojiyingCaptchaClient;

@Service
public class EdgeExtensionBridgeService {

    private static final String OFFICIAL_URL = "https://www.gsxt.gov.cn/";
    private static final int MAX_CANDIDATES = 20;
    private static final Set<String> ALLOWED_SESSION_STATUSES = Set.of(
            "CREATED", "EXTENSION_CONNECTED", "RUNNING", "SEARCHING", "WAITING_MANUAL",
            "WAITING_SELECTION", "COLLECTING", "COOLDOWN", "NEXT_COMPANY", "WAITING_REVIEW", "CAPTCHA_AUTO",
            "BLOCKED", "COMPLETED", "ERROR", "STOPPED");

    private final CompanyBatchService batchService;
    private final GsxtAccessControlService accessControl;
    private final ChaojiyingCaptchaClient captchaClient;
    private final Map<String, VerificationSession> verificationSessions = new ConcurrentHashMap<>();
    private volatile String activeVerificationSessionId;

    public EdgeExtensionBridgeService(CompanyBatchService batchService,
            GsxtAccessControlService accessControl, ChaojiyingCaptchaClient captchaClient) {
        this.batchService = batchService;
        this.accessControl = accessControl;
        this.captchaClient = captchaClient;
    }

    public EdgeCaptchaStatusView captchaStatus() {
        return captchaClient.status();
    }

    public EdgeCaptchaSolveResult solveCaptcha(String accessToken, String jobId,
            EdgeCaptchaSolveRequest request) {
        authorize(accessToken, jobId);
        if (request == null || !StringUtils.hasText(request.getImageBase64())) {
            throw new IllegalArgumentException("缺少验证码图片");
        }
        return captchaClient.solve(request.getImageBase64(), request.getPreviousPicId());
    }

    public synchronized EdgeVerificationSessionView createVerificationSession(String jobId) {
        if (!StringUtils.hasText(jobId)) throw new IllegalArgumentException("缺少企业任务编号");
        batchService.get(jobId);
        LocalDateTime now = LocalDateTime.now();
        verificationSessions.entrySet().removeIf(entry ->
                entry.getValue().expiresAt.isBefore(now.minusHours(1)));
        VerificationSession previous = currentActiveSession();
        if (previous != null) {
            previous.status = "STOPPED";
            previous.message = "已被新的验证任务替换";
            previous.updatedAt = now;
        }
        VerificationSession session = new VerificationSession();
        session.sessionId = UUID.randomUUID().toString();
        session.jobId = jobId;
        session.accessToken = UUID.randomUUID().toString().replace("-", "");
        session.createdAt = now;
        session.updatedAt = now;
        session.expiresAt = now.plusMinutes(30);
        boolean hasPendingCollection = batchService.nextAutomationTarget(jobId).isPresent();
        if (hasPendingCollection) accessControl.requireSessionMayStart();
        session.status = hasPendingCollection ? "CREATED" : "COMPLETED";
        session.message = "COMPLETED".equals(session.status)
                ? "当前任务没有需要官网采集的企业"
                : "等待验证助手连接";
        verificationSessions.put(session.sessionId, session);
        activeVerificationSessionId = session.sessionId;
        return view(session);
    }

    public EdgeVerificationSessionView verificationSession(String sessionId) {
        return view(requireSession(sessionId));
    }

    public synchronized EdgeVerificationSessionClaimView activeVerificationSession() {
        VerificationSession session = currentActiveSession();
        if (session == null || isTerminal(session.status)) return null;
        if ("CREATED".equals(session.status)) {
            session.status = "EXTENSION_CONNECTED";
            session.message = "验证助手已连接";
            session.updatedAt = LocalDateTime.now();
        }
        return EdgeVerificationSessionClaimView.builder()
                .sessionId(session.sessionId)
                .jobId(session.jobId)
                .accessToken(session.accessToken)
                .officialUrl(OFFICIAL_URL)
                .build();
    }

    public synchronized EdgeVerificationSessionView updateVerificationSession(String accessToken,
            String sessionId, EdgeVerificationSessionStatusRequest request) {
        VerificationSession session = requireSession(sessionId);
        authorizeSession(accessToken, session);
        if (request == null || !ALLOWED_SESSION_STATUSES.contains(request.getStatus())) {
            throw new IllegalArgumentException("验证会话状态无效");
        }
        if ("BLOCKED".equals(request.getStatus())) {
            accessControl.block(request.getMessage());
        }
        session.status = request.getStatus();
        session.message = limit(request.getMessage(), 1000);
        session.currentCompanyName = limit(request.getCurrentCompanyName(), 255);
        if ("WAITING_SELECTION".equals(session.status)) {
            List<EdgeVerificationCandidateView> updatedCandidates =
                    sanitizeCandidates(request.getCandidates());
            if (!updatedCandidates.isEmpty()) session.candidates = updatedCandidates;
            boolean selectionStillAvailable = session.candidates.stream()
                    .anyMatch(candidate -> candidate.getCandidateId().equals(session.selectedCandidateId));
            if (!selectionStillAvailable) session.selectedCandidateId = null;
        } else {
            session.candidates = List.of();
            session.selectedCandidateId = null;
        }
        session.updatedAt = LocalDateTime.now();
        return view(session);
    }

    public synchronized EdgeVerificationSessionView selectCandidate(String sessionId, String candidateId) {
        VerificationSession session = requireSession(sessionId);
        if (!"WAITING_SELECTION".equals(session.status)) {
            throw new IllegalArgumentException("当前没有需要选择的企业候选项");
        }
        String selectedId = limit(candidateId, 128);
        boolean found = session.candidates.stream()
                .anyMatch(candidate -> candidate.getCandidateId().equals(selectedId));
        if (!found) throw new IllegalArgumentException("所选企业候选项已失效，请等待系统重新识别");
        session.selectedCandidateId = selectedId;
        session.message = "已收到企业选择，后台正在打开企业详情";
        session.updatedAt = LocalDateTime.now();
        return view(session);
    }

    /**
     * Resumes inspection after the user has handled a CAPTCHA or another
     * official-site interstitial.  This deliberately does not open a new tab,
     * submit another search, or relax the access-control cooldown.
     */
    public synchronized EdgeVerificationSessionView resumeVerificationSession(String sessionId) {
        VerificationSession session = requireSession(sessionId);
        if (!"WAITING_MANUAL".equals(session.status)) {
            throw new IllegalArgumentException("当前会话不在等待人工验证状态，无需继续采集");
        }
        session.status = "RUNNING";
        session.message = "用户已完成官网验证，等待验证助手重新识别当前页面";
        session.updatedAt = LocalDateTime.now();
        return view(session);
    }

    public synchronized EdgeVerificationCandidateView selectedCandidate(String accessToken, String sessionId) {
        VerificationSession session = requireSession(sessionId);
        authorizeSession(accessToken, session);
        if (!StringUtils.hasText(session.selectedCandidateId)) return null;
        return session.candidates.stream()
                .filter(candidate -> session.selectedCandidateId.equals(candidate.getCandidateId()))
                .findFirst().orElse(null);
    }

    public synchronized EdgeVerificationSessionView stopVerificationSession(String sessionId) {
        VerificationSession session = requireSession(sessionId);
        session.status = "STOPPED";
        session.message = "验证已由使用者停止";
        session.updatedAt = LocalDateTime.now();
        return view(session);
    }

    public EdgeExtensionTargetView nextTarget(String accessToken, String jobId) {
        authorize(accessToken, jobId);
        return batchService.nextAutomationTarget(jobId)
                .map(target -> {
                    accessControl.beginCompanyVisit();
                    return targetView(jobId, target);
                })
                .orElse(null);
    }

    public EdgeExtensionCaptureResult capture(String accessToken, String jobId, Long companyId,
            EdgeExtensionCaptureRequest request) {
        authorize(accessToken, jobId);
        if (request == null || !StringUtils.hasText(request.getPageText())) {
            throw new IllegalArgumentException("没有读取到当前企业页面文字");
        }
        if (request.getPageText().length() < 80) {
            throw new IllegalArgumentException("当前页面文字过少，请确认已经打开企业详情页");
        }
        if (!isGsxtUrl(request.getCurrentUrl())) {
            throw new IllegalArgumentException("当前页面不是国家企业信用信息公示系统企业详情页");
        }

        CompanyAllSectionsView parsed = batchService.confirmAllSections(
                jobId, companyId, request.getPageText(), request.getCurrentUrl());
        CompanyBatchCompanyView current = batchService.company(jobId, companyId);
        boolean profileConfirmed = current.getProfile() != null
                && StringUtils.hasText(current.getProfile().getCompanyName())
                && StringUtils.hasText(current.getProfile().getCreditCode());
        if (current.getStatus() == CompanyBatchStatus.CONFLICT || !profileConfirmed) {
            return EdgeExtensionCaptureResult.builder()
                    .completed(false)
                    .message(StringUtils.hasText(current.getCollectionMessage())
                            ? current.getCollectionMessage()
                            : "页面已经解析，但未能确认企业名称和统一社会信用代码，请停留当前企业人工复核")
                    .parsed(parsed)
                    .build();
        }

        EdgeExtensionTargetView next = batchService.nextAutomationTarget(jobId)
                .map(target -> targetView(jobId, target))
                .orElse(null);
        long cooldownMillis = accessControl.completeCompanyVisit();
        return EdgeExtensionCaptureResult.builder()
                .completed(true)
                .message(next == null ? "当前任务中的企业已经全部采集完成" : "当前企业已保存，准备采集下一家")
                .parsed(parsed)
                .nextTarget(next)
                .cooldownMillis(cooldownMillis)
                .build();
    }

    /**
     * Marks the current company for later manual handling and advances with the
     * same conservative visit cooldown used after a successful collection.
     */
    public EdgeExtensionCaptureResult defer(String accessToken, String jobId, Long companyId,
            String reason) {
        authorize(accessToken, jobId);
        batchService.markAutomationIssue(jobId, companyId, limit(reason, 1000));
        EdgeExtensionTargetView next = batchService.nextAutomationTarget(jobId)
                .map(target -> targetView(jobId, target))
                .orElse(null);
        long cooldownMillis = accessControl.completeCompanyVisit();
        return EdgeExtensionCaptureResult.builder()
                .completed(true)
                .message(next == null
                        ? "自动处理已结束；无法继续的企业已标记为待人工处理"
                        : "当前企业已标记为待人工处理，准备继续下一家")
                .nextTarget(next)
                .cooldownMillis(cooldownMillis)
                .build();
    }

    private boolean isGsxtUrl(String value) {
        if (!StringUtils.hasText(value)) return false;
        try {
            URI uri = URI.create(value.trim());
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && host != null
                    && ("gsxt.gov.cn".equalsIgnoreCase(host)
                            || host.toLowerCase().endsWith(".gsxt.gov.cn"));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private EdgeExtensionTargetView targetView(String jobId, CompanyBatchAutomationTarget target) {
        return EdgeExtensionTargetView.builder()
                .jobId(jobId)
                .companyId(target.getCompanyId())
                .companyName(target.getCompanyName())
                .creditCode(target.getCreditCode())
                .build();
    }

    private void authorize(String accessToken, String jobId) {
        VerificationSession session = currentActiveSession();
        if (session == null || !session.jobId.equals(jobId)) {
            throw new IllegalArgumentException("验证会话无效或已经过期，请从项目页面重新开始验证");
        }
        authorizeSession(accessToken, session);
    }

    private void authorizeSession(String suppliedCode, VerificationSession session) {
        byte[] expected = session.accessToken.getBytes(StandardCharsets.UTF_8);
        byte[] supplied = (suppliedCode == null ? "" : suppliedCode).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, supplied) || isTerminal(session.status)) {
            throw new IllegalArgumentException("验证会话无效或已经过期，请从项目页面重新开始验证");
        }
        if (session.expiresAt.isBefore(LocalDateTime.now())) {
            session.status = "STOPPED";
            session.message = "验证会话已过期，请重新开始";
            session.updatedAt = LocalDateTime.now();
            throw new IllegalArgumentException("验证会话无效或已经过期，请从项目页面重新开始验证");
        }
        session.expiresAt = LocalDateTime.now().plusMinutes(30);
        session.updatedAt = LocalDateTime.now();
    }

    private VerificationSession requireSession(String sessionId) {
        VerificationSession session = verificationSessions.get(sessionId);
        if (session == null) throw new IllegalArgumentException("验证会话不存在");
        if (session.expiresAt.isBefore(LocalDateTime.now()) && !isTerminal(session.status)) {
            session.status = "STOPPED";
            session.message = "验证会话已过期，请重新开始";
            session.updatedAt = LocalDateTime.now();
        }
        if (!isTerminal(session.status)) {
            session.expiresAt = LocalDateTime.now().plusMinutes(30);
            session.updatedAt = LocalDateTime.now();
        }
        return session;
    }

    private VerificationSession currentActiveSession() {
        String sessionId = activeVerificationSessionId;
        if (sessionId == null) return null;
        VerificationSession session = verificationSessions.get(sessionId);
        if (session == null) return null;
        if (session.expiresAt.isBefore(LocalDateTime.now()) && !isTerminal(session.status)) {
            session.status = "STOPPED";
            session.message = "验证会话已过期，请重新开始";
            session.updatedAt = LocalDateTime.now();
            return null;
        }
        return session;
    }

    private boolean isTerminal(String status) {
        return "BLOCKED".equals(status) || "COMPLETED".equals(status)
                || "ERROR".equals(status) || "STOPPED".equals(status);
    }

    private String limit(String value, int maxLength) {
        if (!StringUtils.hasText(value)) return null;
        String result = value.trim();
        return result.length() <= maxLength ? result : result.substring(0, maxLength);
    }

    private EdgeVerificationSessionView view(VerificationSession session) {
        return EdgeVerificationSessionView.builder()
                .sessionId(session.sessionId)
                .jobId(session.jobId)
                .status(session.status)
                .message(session.message)
                .currentCompanyName(session.currentCompanyName)
                .candidates(session.candidates)
                .officialUrl(OFFICIAL_URL)
                .createdAt(session.createdAt)
                .expiresAt(session.expiresAt)
                .build();
    }

    private static final class VerificationSession {
        private String sessionId;
        private String jobId;
        private String accessToken;
        private String status;
        private String message;
        private String currentCompanyName;
        private List<EdgeVerificationCandidateView> candidates = List.of();
        private String selectedCandidateId;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private LocalDateTime expiresAt;
    }

    private List<EdgeVerificationCandidateView> sanitizeCandidates(
            List<EdgeVerificationCandidateView> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        return candidates.stream()
                .filter(candidate -> candidate != null && StringUtils.hasText(candidate.getCandidateId())
                        && StringUtils.hasText(candidate.getCompanyName()))
                .limit(MAX_CANDIDATES)
                .map(candidate -> EdgeVerificationCandidateView.builder()
                        .candidateId(limit(candidate.getCandidateId(), 128))
                        .companyName(limit(candidate.getCompanyName(), 255))
                        .creditCode(limit(candidate.getCreditCode(), 64))
                        .legalPerson(limit(candidate.getLegalPerson(), 255))
                        .registrationStatus(limit(candidate.getRegistrationStatus(), 255))
                        .registeredAddress(limit(candidate.getRegisteredAddress(), 500))
                        .summary(limit(candidate.getSummary(), 1000))
                        .build())
                .toList();
    }
}
