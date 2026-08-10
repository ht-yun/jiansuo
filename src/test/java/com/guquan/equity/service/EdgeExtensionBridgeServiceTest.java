package com.guquan.equity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.guquan.equity.model.CompanyAllSectionsView;
import com.guquan.equity.model.CompanyBatchAutomationTarget;
import com.guquan.equity.model.CompanyBatchCompanyView;
import com.guquan.equity.model.CompanyBatchStatus;
import com.guquan.equity.model.CompanyProfile;
import com.guquan.equity.model.EdgeCaptchaSolveRequest;
import com.guquan.equity.model.EdgeCaptchaSolveResult;
import com.guquan.equity.model.EdgeExtensionCaptureRequest;
import com.guquan.equity.model.EdgeVerificationCandidateView;
import com.guquan.equity.model.EdgeVerificationSessionStatusRequest;
import com.guquan.equity.provider.ChaojiyingCaptchaClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EdgeExtensionBridgeServiceTest {

    private final CompanyBatchService batchService = mock(CompanyBatchService.class);
    private final GsxtAccessControlService accessControl = mock(GsxtAccessControlService.class);
    private final ChaojiyingCaptchaClient captchaClient = mock(ChaojiyingCaptchaClient.class);
    private final EdgeExtensionBridgeService service =
            new EdgeExtensionBridgeService(batchService, accessControl, captchaClient);

    @Test
    void solvesCaptchaWithActiveVerificationSession() {
        when(batchService.nextAutomationTarget("job-1")).thenReturn(Optional.of(
                CompanyBatchAutomationTarget.builder()
                        .companyId(12L)
                        .companyName("示例公司")
                        .build()));
        when(captchaClient.solve("aGVsbG8=", null))
                .thenReturn(EdgeCaptchaSolveResult.success("pic-1", "ABCD", List.of(), null));
        service.createVerificationSession("job-1");
        var claim = service.activeVerificationSession();
        EdgeCaptchaSolveRequest request = new EdgeCaptchaSolveRequest();
        request.setJobId("job-1");
        request.setImageBase64("aGVsbG8=");

        var result = service.solveCaptcha(claim.getAccessToken(), "job-1", request);

        assertThat(result.isSolved()).isTrue();
        assertThat(result.getText()).isEqualTo("ABCD");
    }

    @Test
    void extendsSessionExpiryWhileItIsActive() {
        when(batchService.nextAutomationTarget("job-1")).thenReturn(
                Optional.of(CompanyBatchAutomationTarget.builder()
                        .companyId(12L)
                        .companyName("示例公司")
                        .build()),
                Optional.of(CompanyBatchAutomationTarget.builder()
                        .companyId(12L)
                        .companyName("示例公司")
                        .build()));
        var created = service.createVerificationSession("job-1");
        var claim = service.activeVerificationSession();
        var before = service.verificationSession(created.getSessionId()).getExpiresAt();

        service.nextTarget(claim.getAccessToken(), "job-1");

        var after = service.verificationSession(created.getSessionId()).getExpiresAt();
        assertThat(after).isAfter(before);
    }

    @Test
    void rejectsCaptchaSolveWithoutVerificationSession() {
        EdgeCaptchaSolveRequest request = new EdgeCaptchaSolveRequest();
        request.setJobId("job-1");
        request.setImageBase64("aGVsbG8=");

        assertThatThrownBy(() -> service.solveCaptcha("bad-token", "job-1", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("验证会话无效");
    }

    @Test
    void acceptsAutomaticCaptchaStatusUpdate() {
        when(batchService.nextAutomationTarget("job-1")).thenReturn(Optional.of(
                CompanyBatchAutomationTarget.builder()
                        .companyId(12L)
                        .companyName("示例公司")
                        .build()));
        var created = service.createVerificationSession("job-1");
        var claim = service.activeVerificationSession();
        EdgeVerificationSessionStatusRequest request = new EdgeVerificationSessionStatusRequest();
        request.setStatus("CAPTCHA_AUTO");
        request.setMessage("正在通过超级鹰自动识别官网验证码");

        service.updateVerificationSession(claim.getAccessToken(), created.getSessionId(), request);

        assertThat(service.verificationSession(created.getSessionId()).getStatus())
                .isEqualTo("CAPTCHA_AUTO");
    }

    @Test
    void returnsNextTargetWithActiveVerificationSession() {
        when(batchService.nextAutomationTarget("job-1")).thenReturn(Optional.of(
                CompanyBatchAutomationTarget.builder()
                        .companyId(12L)
                        .companyName("北京示例科技有限公司")
                        .creditCode("91110000123456789X")
                        .build()));

        var created = service.createVerificationSession("job-1");
        var claim = service.activeVerificationSession();
        var target = service.nextTarget(claim.getAccessToken(), "job-1");

        assertThat(created.getStatus()).isEqualTo("CREATED");
        assertThat(target.getJobId()).isEqualTo("job-1");
        assertThat(target.getCompanyId()).isEqualTo(12L);
        assertThat(target.getCompanyName()).isEqualTo("北京示例科技有限公司");
        verify(accessControl).beginCompanyVisit();
    }

    @Test
    void rejectsAccessWithoutVerificationSession() {
        assertThatThrownBy(() -> service.nextTarget("wrong-code", "job-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("验证会话无效");
    }

    @Test
    void createsAndClaimsOneClickVerificationSession() {
        when(batchService.nextAutomationTarget("job-1")).thenReturn(Optional.of(
                CompanyBatchAutomationTarget.builder()
                        .companyId(12L)
                        .companyName("北京示例科技有限公司")
                        .build()));

        var created = service.createVerificationSession("job-1");
        var claim = service.activeVerificationSession();
        var target = service.nextTarget(claim.getAccessToken(), "job-1");

        assertThat(created.getStatus()).isEqualTo("CREATED");
        assertThat(claim.getSessionId()).isEqualTo(created.getSessionId());
        assertThat(claim.getJobId()).isEqualTo("job-1");
        assertThat(claim.getAccessToken()).isNotBlank();
        assertThat(target.getCompanyId()).isEqualTo(12L);
    }

    @Test
    void keepsUserActionStatusWhenExtensionReconnects() {
        when(batchService.nextAutomationTarget("job-1")).thenReturn(Optional.of(
                CompanyBatchAutomationTarget.builder()
                        .companyId(12L)
                        .companyName("北京示例科技有限公司")
                        .build()));
        var created = service.createVerificationSession("job-1");
        var claim = service.activeVerificationSession();
        EdgeVerificationSessionStatusRequest request = new EdgeVerificationSessionStatusRequest();
        request.setStatus("WAITING_SELECTION");
        request.setMessage("请选择正确企业");
        request.setCurrentCompanyName("北京示例科技有限公司");

        service.updateVerificationSession(claim.getAccessToken(), created.getSessionId(), request);
        service.activeVerificationSession();

        var current = service.verificationSession(created.getSessionId());
        assertThat(current.getStatus()).isEqualTo("WAITING_SELECTION");
        assertThat(current.getCurrentCompanyName()).isEqualTo("北京示例科技有限公司");
    }

    @Test
    void stoppedVerificationSessionCannotUseItsToken() {
        when(batchService.nextAutomationTarget("job-1")).thenReturn(Optional.of(
                CompanyBatchAutomationTarget.builder().companyId(12L).companyName("示例公司").build()));
        var created = service.createVerificationSession("job-1");
        var claim = service.activeVerificationSession();

        service.stopVerificationSession(created.getSessionId());

        assertThatThrownBy(() -> service.nextTarget(claim.getAccessToken(), "job-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("验证会话无效");
    }

    @Test
    void rejectsCaptureFromNonGsxtPage() {
        when(batchService.nextAutomationTarget("job-1")).thenReturn(Optional.of(
                CompanyBatchAutomationTarget.builder().companyId(12L).companyName("北京示例科技有限公司").build()));
        service.createVerificationSession("job-1");
        var claim = service.activeVerificationSession();
        EdgeExtensionCaptureRequest request = new EdgeExtensionCaptureRequest();
        request.setPageText("企业名称：北京示例科技有限公司\n统一社会信用代码：91110000123456789X\n".repeat(3));
        request.setCurrentUrl("https://example.com/company");

        assertThatThrownBy(() -> service.capture(
                claim.getAccessToken(), "job-1", 12L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是国家企业信用信息公示系统");
    }

    @Test
    void savesCaptureAndReturnsNextCompany() {
        String pageText = """
                企业名称：北京示例科技有限公司
                统一社会信用代码：91110000123456789X
                法定代表人：张三
                登记状态：存续
                登记机关：北京市市场监督管理局
                经营范围：技术开发、技术服务和技术咨询。
                """;
        EdgeExtensionCaptureRequest request = new EdgeExtensionCaptureRequest();
        request.setPageText(pageText);
        request.setCurrentUrl("https://www.gsxt.gov.cn/example");
        when(batchService.confirmAllSections(eq("job-1"), eq(12L), anyString(), anyString()))
                .thenReturn(CompanyAllSectionsView.builder().build());
        when(batchService.company("job-1", 12L)).thenReturn(
                CompanyBatchCompanyView.builder().companyId(12L).status(CompanyBatchStatus.RESOLVED)
                        .profile(CompanyProfile.builder().companyName("北京示例科技有限公司")
                                .creditCode("91110000123456789X").build())
                        .build());
        when(batchService.nextAutomationTarget("job-1")).thenReturn(Optional.of(
                CompanyBatchAutomationTarget.builder().companyId(13L).companyName("上海示例公司").build()));

        service.createVerificationSession("job-1");
        var claim = service.activeVerificationSession();
        var result = service.capture(claim.getAccessToken(), "job-1", 12L, request);

        assertThat(result.isCompleted()).isTrue();
        assertThat(result.getNextTarget().getCompanyId()).isEqualTo(13L);
        assertThat(result.getMessage()).contains("下一家");
        verify(accessControl).completeCompanyVisit();
    }

    @Test
    void continuesToNextCompanyWhenBasicProfileWasSavedButSectionsNeedReview() {
        EdgeExtensionCaptureRequest request = new EdgeExtensionCaptureRequest();
        request.setPageText(("企业名称：北京示例科技有限公司\n"
                + "统一社会信用代码：91110000123456789X\n法定代表人：张三\n登记状态：存续\n").repeat(2));
        request.setCurrentUrl("https://www.gsxt.gov.cn/example");
        when(batchService.confirmAllSections(eq("job-1"), eq(12L), anyString(), anyString()))
                .thenReturn(CompanyAllSectionsView.builder().build());
        when(batchService.company("job-1", 12L)).thenReturn(
                CompanyBatchCompanyView.builder().companyId(12L).status(CompanyBatchStatus.NEEDS_REVIEW)
                        .profile(CompanyProfile.builder().companyName("北京示例科技有限公司")
                                .creditCode("91110000123456789X").build())
                        .collectionMessage("基本信息已保存，股东及出资需要后续补充")
                        .build());
        when(batchService.nextAutomationTarget("job-1")).thenReturn(Optional.of(
                CompanyBatchAutomationTarget.builder().companyId(13L).companyName("上海示例公司").build()));

        service.createVerificationSession("job-1");
        var claim = service.activeVerificationSession();
        var result = service.capture(claim.getAccessToken(), "job-1", 12L, request);

        assertThat(result.isCompleted()).isTrue();
        assertThat(result.getNextTarget().getCompanyId()).isEqualTo(13L);
    }

    @Test
    void locksAccessControlWhenExtensionReportsOfficialBlock() {
        when(batchService.nextAutomationTarget("job-1")).thenReturn(Optional.of(
                CompanyBatchAutomationTarget.builder().companyId(12L).companyName("示例公司").build()));
        var created = service.createVerificationSession("job-1");
        var claim = service.activeVerificationSession();
        EdgeVerificationSessionStatusRequest request = new EdgeVerificationSessionStatusRequest();
        request.setStatus("BLOCKED");
        request.setMessage("IP 请求异常");

        service.updateVerificationSession(claim.getAccessToken(), created.getSessionId(), request);

        verify(accessControl).block("IP 请求异常");
        assertThat(service.verificationSession(created.getSessionId()).getStatus()).isEqualTo("BLOCKED");
    }

    @Test
    void returnsTheCandidateSelectedOnTheProjectPageToTheExtension() {
        when(batchService.nextAutomationTarget("job-1")).thenReturn(Optional.of(
                CompanyBatchAutomationTarget.builder().companyId(12L).companyName("示例公司").build()));
        var created = service.createVerificationSession("job-1");
        var claim = service.activeVerificationSession();
        EdgeVerificationSessionStatusRequest status = new EdgeVerificationSessionStatusRequest();
        status.setStatus("WAITING_SELECTION");
        status.setCurrentCompanyName("示例公司");
        status.setCandidates(List.of(
                EdgeVerificationCandidateView.builder().candidateId("candidate-a")
                        .companyName("示例公司一").creditCode("91110000123456789X").build(),
                EdgeVerificationCandidateView.builder().candidateId("candidate-b")
                        .companyName("示例公司二").creditCode("91110000123456789Y").build()));

        service.updateVerificationSession(claim.getAccessToken(), created.getSessionId(), status);
        service.selectCandidate(created.getSessionId(), "candidate-b");

        var selected = service.selectedCandidate(claim.getAccessToken(), created.getSessionId());
        assertThat(selected.getCandidateId()).isEqualTo("candidate-b");
        assertThat(service.verificationSession(created.getSessionId()).getCandidates()).hasSize(2);
    }

    @Test
    void resumesOnlyAfterTheExtensionHasExplicitlyRequestedManualVerification() {
        when(batchService.nextAutomationTarget("job-1")).thenReturn(Optional.of(
                CompanyBatchAutomationTarget.builder().companyId(12L).companyName("示例公司").build()));
        var created = service.createVerificationSession("job-1");
        var claim = service.activeVerificationSession();
        EdgeVerificationSessionStatusRequest status = new EdgeVerificationSessionStatusRequest();
        status.setStatus("WAITING_MANUAL");
        status.setMessage("官网需要人工完成验证");
        service.updateVerificationSession(claim.getAccessToken(), created.getSessionId(), status);

        var resumed = service.resumeVerificationSession(created.getSessionId());

        assertThat(resumed.getStatus()).isEqualTo("RUNNING");
        assertThat(resumed.getMessage()).contains("已完成官网验证");
    }

    @Test
    void rejectsResumeWhenTheExtensionDidNotRequestManualVerification() {
        when(batchService.nextAutomationTarget("job-1")).thenReturn(Optional.of(
                CompanyBatchAutomationTarget.builder().companyId(12L).companyName("示例公司").build()));
        var created = service.createVerificationSession("job-1");

        assertThatThrownBy(() -> service.resumeVerificationSession(created.getSessionId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不在等待人工验证状态");
    }

    @Test
    void defersAnUnattendedCompanyAndReturnsTheNextSafeTarget() {
        when(batchService.nextAutomationTarget("job-1")).thenReturn(
                Optional.of(CompanyBatchAutomationTarget.builder().companyId(12L).companyName("第一家公司").build()),
                Optional.of(CompanyBatchAutomationTarget.builder().companyId(13L).companyName("下一家公司").build()));
        when(accessControl.completeCompanyVisit()).thenReturn(60_000L);
        service.createVerificationSession("job-1");
        var claim = service.activeVerificationSession();

        var result = service.defer(claim.getAccessToken(), "job-1", 12L, "官网要求验证码");

        assertThat(result.isCompleted()).isTrue();
        assertThat(result.getNextTarget().getCompanyId()).isEqualTo(13L);
        assertThat(result.getCooldownMillis()).isEqualTo(60_000L);
        verify(batchService).markAutomationIssue("job-1", 12L, "官网要求验证码");
        verify(accessControl).completeCompanyVisit();
    }
}
