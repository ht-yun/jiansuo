package com.guquan.equity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guquan.equity.api.CompanyProfileCacheService;
import com.guquan.equity.model.CompanyBatchStatus;
import com.guquan.equity.model.CompanyInfoSection;
import com.guquan.equity.repository.CompanyBatchCompanyEntity;
import com.guquan.equity.repository.CompanyBatchCompanyRepository;
import com.guquan.equity.repository.CompanyBatchJobEntity;
import com.guquan.equity.repository.CompanyBatchJobRepository;
import com.guquan.equity.repository.CompanyBatchSectionRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class CompanyBatchServiceTest {

    private CompanyBatchJobRepository jobs;
    private CompanyBatchCompanyRepository companies;
    private CompanyProfileCacheService cache;
    private GsxtPageParser pageParser;
    private CompanyBatchSectionRepository sections;
    private CompanyAllSectionsParser allSectionsParser;
    private CompanyBatchService service;

    @BeforeEach
    void setUp() {
        jobs = mock(CompanyBatchJobRepository.class);
        companies = mock(CompanyBatchCompanyRepository.class);
        cache = mock(CompanyProfileCacheService.class);
        pageParser = mock(GsxtPageParser.class);
        sections = mock(CompanyBatchSectionRepository.class);
        allSectionsParser = mock(CompanyAllSectionsParser.class);
        service = new CompanyBatchService(jobs, companies, cache, pageParser, sections,
                mock(CompanySectionParser.class), allSectionsParser,
                mock(CompanyOtherInformationFlattener.class), new ObjectMapper());
    }

    @Test
    void automaticQueueSkipsCachedCompaniesWaitingForReview() {
        CompanyBatchJobEntity job = new CompanyBatchJobEntity();
        job.setId("job-1");
        when(jobs.findById("job-1")).thenReturn(Optional.of(job));

        CompanyBatchCompanyEntity review = company(1L, "缓存企业", CompanyBatchStatus.NEEDS_REVIEW);
        CompanyBatchCompanyEntity collection = company(2L, "待采集企业", CompanyBatchStatus.NEEDS_COLLECTION);
        when(companies.findByJobIdOrderById("job-1")).thenReturn(List.of(review, collection));

        assertThat(service.nextAutomationTarget("job-1")).get()
                .extracting(target -> target.getCompanyId())
                .isEqualTo(2L);
    }

    @Test
    void automationTimeoutIsQueuedForManualReviewRatherThanMarkedAsAConflict() {
        CompanyBatchCompanyEntity task = company(1L, "查询超时企业",
                CompanyBatchStatus.NEEDS_COLLECTION);
        when(companies.findById(1L)).thenReturn(Optional.of(task));

        service.markAutomationIssue("job-1", 1L, "官网查询超时");

        assertThat(task.getStatus()).isEqualTo(CompanyBatchStatus.NEEDS_REVIEW);
        assertThat(task.getCollectionMessage()).isEqualTo("官网查询超时");
        verify(companies).save(task);
    }

    @Test
    void reclassifiesLegacyCollectionTimeoutWithoutChangingRealConflicts() {
        CompanyBatchCompanyEntity timeout = company(1L, "超时企业", CompanyBatchStatus.CONFLICT);
        timeout.setCollectionMessage("查询已提交，但官网未返回可确认的结果。");
        CompanyBatchCompanyEntity mismatch = company(2L, "真实冲突企业", CompanyBatchStatus.CONFLICT);
        mismatch.setCollectionMessage("官网企业名称与任务名称不一致");
        when(companies.findByStatus(CompanyBatchStatus.CONFLICT)).thenReturn(List.of(timeout, mismatch));

        int changed = service.reclassifyLegacyAutomationIssues();

        assertThat(changed).isEqualTo(1);
        assertThat(timeout.getStatus()).isEqualTo(CompanyBatchStatus.NEEDS_REVIEW);
        assertThat(mismatch.getStatus()).isEqualTo(CompanyBatchStatus.CONFLICT);
        verify(companies).save(timeout);
        verify(companies, never()).save(mismatch);
    }

    @Test
    void retryAutomationReturnsManualReviewItemToCollectionQueue() {
        CompanyBatchCompanyEntity task = company(1L, "待复测企业", CompanyBatchStatus.NEEDS_REVIEW);
        task.setCollectionMessage("官网查询结果长时间未稳定，待人工补录");
        when(companies.findById(1L)).thenReturn(Optional.of(task));

        var retried = service.retryAutomation("job-1", 1L);

        assertThat(retried.getStatus()).isEqualTo(CompanyBatchStatus.NEEDS_COLLECTION);
        assertThat(task.getCollectionMessage()).isNull();
        verify(companies).save(task);
    }

    @Test
    void mismatchedCompanyPageIsNotSavedToCache() {
        CompanyBatchCompanyEntity task = company(1L, "北京目标科技有限公司",
                CompanyBatchStatus.NEEDS_COLLECTION);
        when(companies.findById(1L)).thenReturn(Optional.of(task));
        when(allSectionsParser.parse(anyString())).thenReturn(parsedPage(
                "上海其他科技有限公司", "91310000123456789X"));
        when(pageParser.parse(anyString(), any(), anyString())).thenReturn(List.of());
        when(sections.findByJobIdAndCompanyIdAndSection(anyString(), any(), any()))
                .thenReturn(Optional.empty());

        service.confirmAllSections("job-1", 1L, "官网页面文字".repeat(20));

        assertThat(task.getStatus()).isEqualTo(CompanyBatchStatus.CONFLICT);
        assertThat(task.getCollectionMessage()).contains("不一致");
        verify(cache, never()).save(any());
    }

    @Test
    void deleteRemovesSectionRowsBeforeCompanyAndJobRows() {
        CompanyBatchJobEntity job = new CompanyBatchJobEntity();
        job.setId("job-1");
        when(jobs.findById("job-1")).thenReturn(Optional.of(job));

        service.delete("job-1");

        InOrder order = inOrder(sections, companies, jobs);
        order.verify(sections).deleteByJobId("job-1");
        order.verify(companies).deleteByJobId("job-1");
        order.verify(jobs).deleteById("job-1");
    }

    @Test
    void csvImportFindsCompanyNameColumnAndHandlesQuotedCommas() {
        when(cache.findExact(anyString())).thenReturn(Optional.empty());
        when(jobs.findById(anyString())).thenAnswer(invocation -> {
            CompanyBatchJobEntity job = new CompanyBatchJobEntity();
            job.setId(invocation.getArgument(0));
            job.setOriginalFilename("企业名单.csv");
            job.setCreatedAt(LocalDateTime.now());
            return Optional.of(job);
        });
        when(companies.findByJobIdOrderById(anyString())).thenReturn(List.of());
        MockMultipartFile file = new MockMultipartFile("file", "企业名单.csv", "text/csv",
                ("业务编号,企业名称\n1,\"北京,示例科技有限公司\"\n2,上海示例贸易有限公司\n")
                        .getBytes(StandardCharsets.UTF_8));

        service.create(file);

        ArgumentCaptor<CompanyBatchCompanyEntity> captor =
                ArgumentCaptor.forClass(CompanyBatchCompanyEntity.class);
        verify(companies, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(CompanyBatchCompanyEntity::getInputCompanyName)
                .containsExactly("北京,示例科技有限公司", "上海示例贸易有限公司");
    }

    @Test
    void csvExportHasUtf8BomAndNeutralizesSpreadsheetFormulas() {
        CompanyBatchJobEntity job = new CompanyBatchJobEntity();
        job.setId("job-1");
        when(jobs.findById("job-1")).thenReturn(Optional.of(job));
        CompanyBatchCompanyEntity company = company(1L, "=HYPERLINK(\"x\")",
                CompanyBatchStatus.NEEDS_REVIEW);
        when(companies.findByJobIdOrderById("job-1")).thenReturn(List.of(company));

        byte[] exported = service.export("job-1");

        assertThat(exported).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(new String(exported, StandardCharsets.UTF_8)).contains("\"'=HYPERLINK");
    }

    private CompanyBatchCompanyEntity company(Long id, String name, CompanyBatchStatus status) {
        CompanyBatchCompanyEntity company = new CompanyBatchCompanyEntity();
        company.setId(id);
        company.setJobId("job-1");
        company.setInputCompanyName(name);
        company.setNormalizedCompanyName(name);
        company.setStatus(status);
        return company;
    }

    private Map<CompanyInfoSection, CompanySectionParser.ParsedSection> parsedPage(
            String companyName, String creditCode) {
        EnumMap<CompanyInfoSection, CompanySectionParser.ParsedSection> result =
                new EnumMap<>(CompanyInfoSection.class);
        result.put(CompanyInfoSection.BASIC, new CompanySectionParser.ParsedSection(
                "PARSED", List.of(Map.of("companyName", companyName, "creditCode", creditCode))));
        for (CompanyInfoSection section : CompanyInfoSection.values()) {
            result.putIfAbsent(section, new CompanySectionParser.ParsedSection("NO_RECORD", List.of()));
        }
        return result;
    }
}
