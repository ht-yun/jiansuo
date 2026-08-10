package com.guquan.equity.service;

import com.guquan.equity.api.CompanyProfileCacheService;
import com.guquan.equity.model.CompanyBatchCompanyView;
import com.guquan.equity.model.CompanyBatchAutomationTarget;
import com.guquan.equity.model.CompanyOtherInformationItem;
import com.guquan.equity.model.CompanyBatchConfirmRequest;
import com.guquan.equity.model.CompanyBatchImportRequest;
import com.guquan.equity.model.CompanyBatchJobView;
import com.guquan.equity.model.CompanyBatchStatus;
import com.guquan.equity.model.CompanyAllSectionsView;
import com.guquan.equity.model.CompanyBrowserTaskRequest;
import com.guquan.equity.model.CompanyInfoSection;
import com.guquan.equity.model.CompanySectionImportRequest;
import com.guquan.equity.model.CompanySectionView;
import com.guquan.equity.model.CompanyProfile;
import com.guquan.equity.repository.CompanyBatchSectionEntity;
import com.guquan.equity.repository.CompanyBatchSectionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guquan.equity.repository.CompanyBatchCompanyEntity;
import com.guquan.equity.repository.CompanyBatchCompanyRepository;
import com.guquan.equity.repository.CompanyBatchJobEntity;
import com.guquan.equity.repository.CompanyBatchJobRepository;
import com.guquan.equity.util.CompanyNameNormalizer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CompanyBatchService {
    private static final int MAX_PAGE_TEXT_LENGTH = 2_000_000;

    private final CompanyBatchJobRepository jobs;
    private final CompanyBatchCompanyRepository companies;
    private final CompanyProfileCacheService cache;
    private final GsxtPageParser parser;
    private final CompanyBatchSectionRepository sections;
    private final CompanySectionParser sectionParser;
    private final CompanyAllSectionsParser allSectionsParser;
    private final CompanyOtherInformationFlattener otherInformationFlattener;
    private final ObjectMapper objectMapper;

    public CompanyBatchService(CompanyBatchJobRepository jobs, CompanyBatchCompanyRepository companies,
            CompanyProfileCacheService cache, GsxtPageParser parser, CompanyBatchSectionRepository sections,
            CompanySectionParser sectionParser, CompanyAllSectionsParser allSectionsParser,
            CompanyOtherInformationFlattener otherInformationFlattener, ObjectMapper objectMapper) {
        this.jobs = jobs;
        this.companies = companies;
        this.cache = cache;
        this.parser = parser;
        this.sections = sections;
        this.sectionParser = sectionParser;
        this.allSectionsParser = allSectionsParser;
        this.otherInformationFlattener = otherInformationFlattener;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CompanyBatchJobView create(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择企业名单文件");
        List<String> names = readNames(file);
        if (names.isEmpty()) throw new IllegalArgumentException("名单中没有可用的企业名称");
        String jobId = UUID.randomUUID().toString();
        CompanyBatchJobEntity job = new CompanyBatchJobEntity();
        job.setId(jobId); job.setOriginalFilename(file.getOriginalFilename() == null ? "企业名单" : file.getOriginalFilename());
        job.setCreatedAt(LocalDateTime.now()); jobs.save(job);
        Map<String, String> unique = new LinkedHashMap<>();
        for (String name : names) {
            validateCompanyName(name);
            unique.putIfAbsent(CompanyNameNormalizer.normalize(name), name);
        }
        for (Map.Entry<String, String> item : unique.entrySet()) {
            CompanyBatchCompanyEntity task = new CompanyBatchCompanyEntity();
            task.setJobId(jobId); task.setInputCompanyName(item.getValue()); task.setNormalizedCompanyName(item.getKey());
            task.setStatus(CompanyBatchStatus.NEEDS_COLLECTION); task.setUpdatedAt(LocalDateTime.now());
            cache.findExact(item.getValue()).ifPresent(profile -> { task.setCacheHit(true); apply(task, profile); task.setStatus(CompanyBatchStatus.NEEDS_REVIEW); });
            companies.save(task);
        }
        return get(jobId);
    }

    @Transactional
    public CompanyBatchJobView createManual(String companyName) {
        if (!StringUtils.hasText(companyName)) throw new IllegalArgumentException("请输入公司/单位名称");
        validateCompanyName(companyName);
        String jobId = UUID.randomUUID().toString();
        CompanyBatchJobEntity job = new CompanyBatchJobEntity();
        job.setId(jobId); job.setOriginalFilename("单企业查询"); job.setCreatedAt(LocalDateTime.now()); jobs.save(job);
        CompanyBatchCompanyEntity task = new CompanyBatchCompanyEntity();
        task.setJobId(jobId); task.setInputCompanyName(companyName.trim());
        task.setNormalizedCompanyName(CompanyNameNormalizer.normalize(companyName));
        task.setStatus(CompanyBatchStatus.NEEDS_COLLECTION); task.setUpdatedAt(LocalDateTime.now());
        cache.findExact(companyName.trim()).ifPresent(profile -> { task.setCacheHit(true); apply(task, profile); task.setStatus(CompanyBatchStatus.NEEDS_REVIEW); });
        companies.save(task);
        return get(jobId);
    }

    public List<CompanyBatchJobView> listJobs() {
        return jobs.findTop100ByOrderByCreatedAtDesc().stream().map(job -> get(job.getId())).toList();
    }

    public CompanyBatchJobView get(String jobId) {
        requireJob(jobId);
        List<CompanyBatchCompanyEntity> list = companies.findByJobIdOrderById(jobId);
        return CompanyBatchJobView.builder().jobId(jobId).originalFilename(jobs.findById(jobId).orElseThrow().getOriginalFilename())
                .totalCompanies(list.size()).cacheHits((int) list.stream().filter(CompanyBatchCompanyEntity::isCacheHit).count())
                .pendingCollection(count(list, CompanyBatchStatus.NEEDS_COLLECTION)).pendingReview(count(list, CompanyBatchStatus.NEEDS_REVIEW))
                .conflicts(count(list, CompanyBatchStatus.CONFLICT)).resolved(count(list, CompanyBatchStatus.RESOLVED))
                .createdAt(jobs.findById(jobId).orElseThrow().getCreatedAt()).build();
    }

    public List<CompanyBatchCompanyView> companies(String jobId) {
        requireJob(jobId); return this.companies.findByJobIdOrderById(jobId).stream().map(this::view).toList();
    }

    public CompanyBatchCompanyView company(String jobId, Long companyId) {
        return view(requireCompany(jobId, companyId));
    }

    /** Returns the next company that still needs automatic collection. */
    public Optional<CompanyBatchAutomationTarget> nextAutomationTarget(String jobId) {
        requireJob(jobId);
        return companies.findByJobIdOrderById(jobId).stream()
                .filter(company -> company.getStatus() == CompanyBatchStatus.NEEDS_COLLECTION)
                .findFirst()
                .map(company -> CompanyBatchAutomationTarget.builder()
                        .companyId(company.getId())
                        .companyName(company.getInputCompanyName())
                        .creditCode(company.getCreditCode())
                        .build());
    }

    @Transactional
    public CompanySectionView saveAutomatedSection(String jobId, Long companyId,
            CompanyInfoSection section, String rawText) {
        return confirmSection(jobId, companyId, section.name(),
                pageTextRequest(rawText));
    }

    @Transactional
    public void completeAutomatedCompany(String jobId, Long companyId, CompanyProfile selected,
            String basicPageText) {
        CompanyBatchCompanyEntity task = requireCompany(jobId, companyId);
        CompanyBrowserTaskRequest parserRequest = new CompanyBrowserTaskRequest();
        parserRequest.setCompanyName(task.getInputCompanyName());
        parserRequest.setCreditCode(selected == null ? null : selected.getCreditCode());
        CompanyProfile profile = parser.parse(basicPageText, parserRequest, "GSXT_BROWSER_AUTOMATED").stream()
                .filter(candidate -> selected == null || !StringUtils.hasText(selected.getCreditCode())
                        || selected.getCreditCode().equalsIgnoreCase(candidate.getCreditCode()))
                .findFirst().orElse(selected);
        if (profile == null || !StringUtils.hasText(profile.getCompanyName())
                || !StringUtils.hasText(profile.getCreditCode())) {
            throw new IllegalArgumentException("官网详情页未识别到可确认的企业名称和统一社会信用代码");
        }
        CompanyProfile saved = cache.save(profile);
        cache.saveAlias(task.getInputCompanyName(), saved);
        apply(task, saved);
        task.setStatus(CompanyBatchStatus.RESOLVED);
        task.setCollectionMessage(null);
        task.setUpdatedAt(LocalDateTime.now());
        companies.save(task);
    }

    @Transactional
    public void markAutomationIssue(String jobId, Long companyId, String message) {
        CompanyBatchCompanyEntity task = requireCompany(jobId, companyId);
        // A timeout, captcha, or changed page structure is not a data conflict.
        // Keep genuine name/credit-code mismatches as CONFLICT in confirmAllSections.
        task.setStatus(CompanyBatchStatus.NEEDS_REVIEW);
        task.setCollectionMessage(StringUtils.hasText(message) ? message.trim() : "需要人工处理");
        task.setUpdatedAt(LocalDateTime.now());
        companies.save(task);
    }

    @Transactional
    public CompanyBatchCompanyView retryAutomation(String jobId, Long companyId) {
        CompanyBatchCompanyEntity task = requireCompany(jobId, companyId);
        if (task.getStatus() == CompanyBatchStatus.RESOLVED) {
            throw new IllegalArgumentException("已完成的企业无需重新自动采集");
        }
        if (task.getStatus() == CompanyBatchStatus.CONFLICT) {
            throw new IllegalArgumentException("企业身份存在真实冲突，请先人工确认后再采集");
        }
        task.setStatus(CompanyBatchStatus.NEEDS_COLLECTION);
        task.setCollectionMessage(null);
        task.setUpdatedAt(LocalDateTime.now());
        companies.save(task);
        return view(task);
    }

    /**
     * Versions before the background-query timeout fix incorrectly used
     * CONFLICT for technical collection failures. Reclassify only known
     * automation messages; real identity mismatches stay as conflicts.
     */
    @Transactional
    public int reclassifyLegacyAutomationIssues() {
        List<CompanyBatchCompanyEntity> legacy = companies.findByStatus(CompanyBatchStatus.CONFLICT)
                .stream()
                .filter(task -> isLegacyAutomationIssue(task.getCollectionMessage()))
                .toList();
        legacy.forEach(task -> {
            task.setStatus(CompanyBatchStatus.NEEDS_REVIEW);
            task.setUpdatedAt(LocalDateTime.now());
            companies.save(task);
        });
        return legacy.size();
    }

    @Transactional
    public List<CompanyProfile> importPage(String jobId, Long companyId, CompanyBatchImportRequest request) {
        CompanyBatchCompanyEntity task = requireCompany(jobId, companyId);
        if (request == null || !StringUtils.hasText(request.getPageText())) throw new IllegalArgumentException("请粘贴官网页面文字");
        CompanyBrowserTaskRequest parserRequest = new CompanyBrowserTaskRequest(); parserRequest.setCompanyName(task.getInputCompanyName());
        List<CompanyProfile> profiles = parser.parse(request.getPageText(), parserRequest, "GSXT_MANUAL_IMPORT");
        if (profiles.size() == 1) apply(task, profiles.get(0));
        task.setStatus(profiles.isEmpty() ? CompanyBatchStatus.NEEDS_COLLECTION : CompanyBatchStatus.NEEDS_REVIEW);
        task.setUpdatedAt(LocalDateTime.now()); companies.save(task); return profiles;
    }

    @Transactional
    public CompanyBatchCompanyView confirm(String jobId, Long companyId, CompanyBatchConfirmRequest request) {
        CompanyBatchCompanyEntity task = requireCompany(jobId, companyId);
        if (request == null || request.isNoRecord()) { task.setStatus(CompanyBatchStatus.RESOLVED); task.setUpdatedAt(LocalDateTime.now()); companies.save(task); return view(task); }
        if (request.getProfile() == null || !StringUtils.hasText(request.getProfile().getCompanyName())
                || !StringUtils.hasText(request.getProfile().getCreditCode())) throw new IllegalArgumentException("请至少确认企业名称和统一社会信用代码");
        CompanyProfile saved = cache.save(request.getProfile()); apply(task, saved); task.setStatus(CompanyBatchStatus.RESOLVED); task.setUpdatedAt(LocalDateTime.now()); companies.save(task); return view(task);
    }

    @Transactional
    public CompanyAllSectionsView previewAllSections(String jobId, Long companyId, String text) {
        requireCompany(jobId, companyId);
        validatePageText(text);
        return allSectionsView(allSectionsParser.parse(text), text, null, false, jobId, companyId);
    }

    @Transactional
    public CompanyAllSectionsView confirmAllSections(String jobId, Long companyId, String text) {
        return confirmAllSections(jobId, companyId, text, null);
    }

    @Transactional
    public CompanyAllSectionsView confirmAllSections(String jobId, Long companyId, String text, String sourceUrl) {
        CompanyBatchCompanyEntity task = requireCompany(jobId, companyId);
        validatePageText(text);
        Map<CompanyInfoSection, CompanySectionParser.ParsedSection> parsedSections = allSectionsParser.parse(text);
        CompanyProfile confirmedProfile = profileFromBasicSection(parsedSections.get(CompanyInfoSection.BASIC));

        // The complete GSXT page can contain credit codes for related companies.  Only use the
        // legacy full-page parser as a fallback when the dedicated basic-information block
        // did not produce a usable company profile.
        CompanyBrowserTaskRequest parserRequest = new CompanyBrowserTaskRequest();
        parserRequest.setCompanyName(task.getInputCompanyName());
        List<CompanyProfile> profiles = parser.parse(text, parserRequest, "GSXT_MANUAL_IMPORT");
        if (confirmedProfile == null && profiles.size() == 1) {
            confirmedProfile = profiles.get(0);
        }
        if (confirmedProfile != null && !matchesTarget(task, confirmedProfile)) {
            task.setStatus(CompanyBatchStatus.CONFLICT);
            task.setCollectionMessage("解析到的企业“" + confirmedProfile.getCompanyName()
                    + "”与当前任务“" + task.getInputCompanyName() + "”不一致，请人工核对");
            confirmedProfile = null;
        } else if (confirmedProfile != null) {
            CompanyProfile saved = cache.save(confirmedProfile);
            cache.saveAlias(task.getInputCompanyName(), saved);
            apply(task, saved);
            List<String> incomplete = parsedSections.entrySet().stream()
                    .filter(item -> item.getKey() != CompanyInfoSection.BASIC)
                    .filter(item -> "NOT_FOUND".equals(item.getValue().status())
                            || "PARTIAL".equals(item.getValue().status()))
                    .map(item -> sectionLabel(item.getKey()))
                    .toList();
            task.setStatus(incomplete.isEmpty()
                    ? CompanyBatchStatus.RESOLVED : CompanyBatchStatus.NEEDS_REVIEW);
            task.setCollectionMessage(incomplete.isEmpty() ? null
                    : "基本信息已保存，以下栏目需要后续补充：" + String.join("、", incomplete));
        } else {
            task.setStatus(CompanyBatchStatus.NEEDS_REVIEW);
            task.setCollectionMessage("未能确认企业名称和统一社会信用代码，请人工核对");
        }
        task.setUpdatedAt(LocalDateTime.now());
        companies.save(task);
        return allSectionsView(parsedSections, text, sourceUrl, true, jobId, companyId);
    }

    @Transactional
    public CompanySectionView previewSection(String jobId, Long companyId, String sectionName,
            CompanySectionImportRequest request) {
        requireCompany(jobId, companyId);
        validatePageText(request == null ? null : request.getPageText());
        CompanyInfoSection section = CompanyInfoSection.parse(sectionName);
        CompanySectionParser.ParsedSection parsed = sectionParser.parse(section, request == null ? null : request.getPageText());
        return CompanySectionView.builder().section(section).status(parsed.status()).rawText(request == null ? null : request.getPageText())
                .records(parsed.records()).updatedAt(LocalDateTime.now()).build();
    }

    @Transactional
    public CompanySectionView confirmSection(String jobId, Long companyId, String sectionName,
            CompanySectionImportRequest request) {
        requireCompany(jobId, companyId);
        validatePageText(request == null ? null : request.getPageText());
        CompanyInfoSection section = CompanyInfoSection.parse(sectionName);
        CompanySectionParser.ParsedSection parsed = sectionParser.parse(section, request == null ? null : request.getPageText());
        CompanyBatchSectionEntity entity = sections.findByJobIdAndCompanyIdAndSection(jobId, companyId, section)
                .orElseGet(CompanyBatchSectionEntity::new);
        entity.setJobId(jobId); entity.setCompanyId(companyId); entity.setSection(section); entity.setStatus(parsed.status());
        entity.setRawText(request == null ? null : request.getPageText());
        entity.setParsedJson(writeJson(parsed.records())); entity.setUpdatedAt(LocalDateTime.now());
        sections.save(entity);
        return sectionView(entity);
    }

    public List<CompanySectionView> sectionViews(String jobId, Long companyId) {
        requireCompany(jobId, companyId);
        return java.util.Arrays.stream(CompanyInfoSection.values()).map(section -> sections
                .findByJobIdAndCompanyIdAndSection(jobId, companyId, section).map(this::sectionView)
                .orElseGet(() -> CompanySectionView.builder().section(section).status("NOT_COLLECTED").records(List.of()).build())).toList();
    }

    public byte[] export(String jobId) {
        List<CompanyBatchCompanyEntity> list = companies.findByJobIdOrderById(requireJob(jobId).getId());
        StringBuilder out = new StringBuilder("\uFEFF单位名称,工商登记全称,统一社会信用代码,法定代表人,经营状态,行业,企业类型,注册地址,状态\n");
        for (CompanyBatchCompanyEntity c : list) out.append(csv(c.getInputCompanyName())).append(',').append(csv(c.getOfficialCompanyName())).append(',').append(csv(c.getCreditCode())).append(',').append(csv(c.getLegalPerson())).append(',').append(csv(c.getRegistrationStatus())).append(',').append(csv(c.getIndustryName())).append(',').append(csv(c.getEntityType())).append(',').append(csv(c.getRegisteredAddress())).append(',').append(c.getStatus()).append('\n');
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    public List<CompanyOtherInformationItem> otherInformation(String jobId) {
        requireJob(jobId);
        Map<Long, CompanyBatchCompanyEntity> companyById = companies.findByJobIdOrderById(jobId).stream()
                .collect(java.util.stream.Collectors.toMap(CompanyBatchCompanyEntity::getId, company -> company));
        List<CompanyOtherInformationItem> result = new ArrayList<>();
        for (CompanyBatchSectionEntity section : sections.findByJobIdOrderByCompanyIdAscIdAsc(jobId)) {
            CompanyBatchCompanyEntity company = companyById.get(section.getCompanyId());
            if (company != null) result.addAll(otherInformationFlattener.flatten(company, section.getSection(),
                    section.getStatus(), readRecords(section)));
        }
        return result;
    }

    public byte[] exportOtherInformation(String jobId) {
        StringBuilder out = new StringBuilder("\uFEFF原始企业名称,工商登记全称,统一社会信用代码,栏目,栏目状态,记录序号,字段,值\n");
        for (CompanyOtherInformationItem item : otherInformation(jobId)) {
            out.append(csv(item.getInputCompanyName())).append(',').append(csv(item.getOfficialCompanyName())).append(',')
                    .append(csv(item.getCreditCode())).append(',').append(csv(sectionLabel(item.getSection()))).append(',')
                    .append(csv(item.getSectionStatus())).append(',').append(item.getRecordNumber()).append(',')
                    .append(csv(item.getFieldName())).append(',').append(csv(item.getValue())).append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public void delete(String jobId) {
        requireJob(jobId);
        sections.deleteByJobId(jobId);
        companies.deleteByJobId(jobId);
        jobs.deleteById(jobId);
    }

    private List<String> readNames(MultipartFile file) {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        try { return filename.endsWith(".csv") ? readCsv(file) : readXlsx(file); }
        catch (IOException e) { throw new IllegalArgumentException("读取名单失败：" + e.getMessage(), e); }
    }
    private List<String> readCsv(MultipartFile file) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) rows.add(parseCsvLine(line));
        }
        int headerRow = -1;
        int companyColumn = 0;
        for (int rowIndex = 0; rowIndex < Math.min(rows.size(), 10); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            for (int columnIndex = 0; columnIndex < row.size(); columnIndex++) {
                if (isHeader(row.get(columnIndex).replace("\uFEFF", "").trim())) {
                    headerRow = rowIndex;
                    companyColumn = columnIndex;
                    break;
                }
            }
            if (headerRow >= 0) break;
        }
        List<String> result = new ArrayList<>();
        for (int rowIndex = headerRow >= 0 ? headerRow + 1 : 0; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            if (companyColumn >= row.size()) continue;
            String value = row.get(companyColumn).replace("\uFEFF", "").trim();
            if (!isHeader(value) && StringUtils.hasText(value)) {
                validateCompanyName(value);
                result.add(value);
            }
        }
        return result;
    }
    private List<String> readXlsx(MultipartFile file) throws IOException {
        List<String> result = new ArrayList<>(); try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) { if (workbook.getNumberOfSheets() == 0) return result; var sheet = workbook.getSheetAt(0); DataFormatter formatter = new DataFormatter(); int column = 0; Row header = sheet.getRow(0); if (header != null) for (var cell : header) { String v = formatter.formatCellValue(cell).trim(); if (v.contains("单位名称") || v.contains("企业名称") || v.contains("公司名称")) { column = cell.getColumnIndex(); break; } } for (int i = 0; i <= sheet.getLastRowNum(); i++) { Row row = sheet.getRow(i); if (row == null || row.getCell(column) == null) continue; String value = formatter.formatCellValue(row.getCell(column)).trim(); if (!isHeader(value) && StringUtils.hasText(value)) result.add(value); } } return result;
    }
    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (current == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    value.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                values.add(value.toString());
                value.setLength(0);
            } else {
                value.append(current);
            }
        }
        values.add(value.toString());
        return values;
    }
    private boolean isHeader(String value) { return value.equals("单位名称") || value.equals("企业名称") || value.equals("公司名称"); }
    private int count(List<CompanyBatchCompanyEntity> list, CompanyBatchStatus status) { return (int) list.stream().filter(item -> item.getStatus() == status).count(); }

    private boolean isLegacyAutomationIssue(String message) {
        if (!StringUtils.hasText(message)) return false;
        return message.contains("查询已提交，但官网未返回可确认的结果")
                || message.contains("后台自动查询未能继续")
                || message.contains("官网查询结果长时间未稳定")
                || message.contains("官网要求验证码");
    }
    private void apply(CompanyBatchCompanyEntity task, CompanyProfile profile) { task.setOfficialCompanyName(profile.getCompanyName()); task.setCreditCode(profile.getCreditCode()); task.setLegalPerson(profile.getLegalPerson()); task.setRegistrationStatus(profile.getRegistrationStatus()); task.setIndustryName(profile.getIndustryName()); task.setEntityType(profile.getEntityType()); task.setRegisteredAddress(profile.getRegisteredAddress()); task.setRegisteredAddressAreaCode(profile.getRegisteredAddressAreaCode()); task.setSource(profile.getSource()); }
    private CompanyProfile profileFromBasicSection(CompanySectionParser.ParsedSection basic) {
        if (basic == null || basic.records().size() != 1) return null;
        Map<String, String> record = basic.records().get(0);
        String companyName = record.get("companyName");
        String creditCode = record.get("creditCode");
        if (!StringUtils.hasText(companyName) || !StringUtils.hasText(creditCode)) return null;
        return CompanyProfile.builder()
                .companyName(companyName).creditCode(creditCode)
                .legalPerson(record.get("legalPerson")).registrationStatus(record.get("registrationStatus"))
                .industryName(record.get("industry")).entityType(record.get("entityType"))
                .registrationAuthority(record.get("registrationAuthority"))
                .registeredAddress(record.get("registeredAddress"))
                .source("GSXT_MANUAL_IMPORT").sourceUpdatedAt(LocalDate.now()).build();
    }
    private CompanyBatchCompanyView view(CompanyBatchCompanyEntity c) { CompanyProfile p = CompanyProfile.builder().companyName(c.getOfficialCompanyName()).creditCode(c.getCreditCode()).legalPerson(c.getLegalPerson()).registrationStatus(c.getRegistrationStatus()).industryName(c.getIndustryName()).entityType(c.getEntityType()).registeredAddress(c.getRegisteredAddress()).registeredAddressAreaCode(c.getRegisteredAddressAreaCode()).source(c.getSource()).build(); return CompanyBatchCompanyView.builder().companyId(c.getId()).inputCompanyName(c.getInputCompanyName()).normalizedCompanyName(c.getNormalizedCompanyName()).status(c.getStatus()).cacheHit(c.isCacheHit()).profile(p).collectionMessage(c.getCollectionMessage()).updatedAt(c.getUpdatedAt()).build(); }
    private CompanyBatchJobEntity requireJob(String id) { return jobs.findById(id).orElseThrow(() -> new IllegalArgumentException("企业任务不存在")); }
    private CompanyBatchCompanyEntity requireCompany(String jobId, Long id) { CompanyBatchCompanyEntity c = companies.findById(id).orElseThrow(() -> new IllegalArgumentException("企业任务不存在")); if (!c.getJobId().equals(jobId)) throw new IllegalArgumentException("企业任务不属于当前批次"); return c; }
    private String csv(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
    private String writeJson(List<Map<String, String>> records) { try { return objectMapper.writeValueAsString(records); } catch (Exception e) { throw new IllegalStateException("保存栏目解析结果失败", e); } }
    private CompanySectionView sectionView(CompanyBatchSectionEntity entity) { return CompanySectionView.builder().section(entity.getSection()).status(entity.getStatus()).rawText(entity.getRawText()).sourceUrl(entity.getSourceUrl()).records(readRecords(entity)).updatedAt(entity.getUpdatedAt()).build(); }
    private List<Map<String, String>> readRecords(CompanyBatchSectionEntity entity) { if (!StringUtils.hasText(entity.getParsedJson())) return List.of(); try { return objectMapper.readValue(entity.getParsedJson(), new TypeReference<List<Map<String, String>>>() {}); } catch (Exception e) { throw new IllegalStateException("读取栏目解析结果失败", e); } }
    private CompanySectionImportRequest pageTextRequest(String rawText) { CompanySectionImportRequest request = new CompanySectionImportRequest(); request.setPageText(rawText); return request; }
    private String sectionLabel(CompanyInfoSection section) { return switch (section) { case BASIC -> "企业基本信息"; case SHAREHOLDERS -> "股东及出资"; case INVESTMENTS -> "对外投资"; case POSITIONS -> "任职信息"; case ABNORMAL -> "经营异常"; case SERIOUS_VIOLATIONS -> "严重违法失信"; }; }
    private CompanyAllSectionsView allSectionsView(Map<CompanyInfoSection, CompanySectionParser.ParsedSection> parsed,
            String rawText, String sourceUrl, boolean save, String jobId, Long companyId) {
        Map<CompanyInfoSection, CompanySectionView> views = new java.util.EnumMap<>(CompanyInfoSection.class);
        for (Map.Entry<CompanyInfoSection, CompanySectionParser.ParsedSection> item : parsed.entrySet()) {
            CompanyInfoSection section = item.getKey(); CompanySectionParser.ParsedSection value = item.getValue();
            if (save) {
                CompanyBatchSectionEntity entity = sections.findByJobIdAndCompanyIdAndSection(jobId, companyId, section)
                        .orElseGet(CompanyBatchSectionEntity::new);
                entity.setJobId(jobId); entity.setCompanyId(companyId); entity.setSection(section); entity.setStatus(value.status());
                entity.setRawText(section == CompanyInfoSection.BASIC ? rawText : null);
                if (section == CompanyInfoSection.BASIC && StringUtils.hasText(sourceUrl)) {
                    entity.setSourceUrl(sourceUrl.trim());
                }
                entity.setParsedJson(writeJson(value.records())); entity.setUpdatedAt(LocalDateTime.now()); sections.save(entity);
            }
            views.put(section, CompanySectionView.builder().section(section).status(value.status())
                    .rawText(section == CompanyInfoSection.BASIC ? rawText : null)
                    .sourceUrl(section == CompanyInfoSection.BASIC ? sourceUrl : null)
                    .records(value.records()).updatedAt(LocalDateTime.now()).build());
        }
        return CompanyAllSectionsView.builder().sections(views).updatedAt(LocalDateTime.now()).build();
    }

    private boolean matchesTarget(CompanyBatchCompanyEntity task, CompanyProfile profile) {
        if (StringUtils.hasText(task.getCreditCode())) {
            return task.getCreditCode().equalsIgnoreCase(profile.getCreditCode());
        }
        String expected = CompanyNameNormalizer.normalize(task.getInputCompanyName());
        String actual = CompanyNameNormalizer.normalize(profile.getCompanyName());
        if (expected.equals(actual)) return true;
        int shorterLength = Math.min(expected.length(), actual.length());
        return shorterLength >= 6 && (expected.contains(actual) || actual.contains(expected));
    }

    private void validatePageText(String text) {
        if (!StringUtils.hasText(text)) throw new IllegalArgumentException("请粘贴官网企业信息全文");
        if (text.length() > MAX_PAGE_TEXT_LENGTH) {
            throw new IllegalArgumentException("官网页面文字超过 200 万字符，请仅保留当前企业详情内容");
        }
    }

    private void validateCompanyName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.length() > 255) throw new IllegalArgumentException("公司/单位名称不能超过 255 个字符");
        if (CompanyNameNormalizer.normalize(value).isEmpty()) {
            throw new IllegalArgumentException("公司/单位名称不能只包含空格或标点");
        }
    }
}
