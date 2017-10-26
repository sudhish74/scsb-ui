package org.recap.service;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.recap.RecapConstants;
import org.recap.model.jpa.BulkRequestItemEntity;
import org.recap.model.jpa.InstitutionEntity;
import org.recap.model.jpa.UsersEntity;
import org.recap.model.request.ItemRequestInformation;
import org.recap.model.request.ItemResponseInformation;
import org.recap.model.search.BulkRequestForm;
import org.recap.model.search.BulkRequestInformation;
import org.recap.model.search.BulkRequestResponse;
import org.recap.model.search.BulkSearchResultRow;
import org.recap.repository.jpa.*;
import org.recap.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Created by akulak on 22/9/17.
 */
@Service
public class BulkRequestService {

    private static final Logger logger = LoggerFactory.getLogger(BulkRequestService.class);

    @Autowired
    private BulkRequestDetailsRepository bulkRequestDetailsRepository;

    @Autowired
    private UserDetailsRepository userDetailsRepository;

    @Autowired
    private BulkSearchRequestService bulkSearchRequestService;

    @Autowired
    private InstitutionDetailsRepository institutionDetailsRepository;

    @Autowired
    private BulkCustomerCodeDetailsRepository bulkCustomerCodeDetailsRepository;

    @Autowired
    private RestHeaderService restHeaderService;

    @Autowired
    private SecurityUtil securityUtil;

    @Value("${scsb.url}")
    private String scsbUrl;


    public void processCreateBulkRequest(BulkRequestForm bulkRequestForm, HttpServletRequest request) {
        try {
            if (processPatronValidation(bulkRequestForm)){
                InstitutionEntity institutionEntity = institutionDetailsRepository.findByInstitutionCode(bulkRequestForm.getRequestingInstitution());
                MultipartFile multipartFile = bulkRequestForm.getFile();
                byte[] bytes = multipartFile.getBytes();
                HttpSession session = request.getSession(false);
                Integer userId = (Integer) session.getAttribute(RecapConstants.USER_ID);
                UsersEntity usersEntity = userDetailsRepository.findByUserId(userId);
                BulkRequestItemEntity bulkRequestItemEntity = new BulkRequestItemEntity();
                bulkRequestItemEntity.setCreatedBy(usersEntity != null ? usersEntity.getLoginId() : "");
                bulkRequestItemEntity.setCreatedDate(new Date());
                bulkRequestItemEntity.setLastUpdatedDate(new Date());
                bulkRequestItemEntity.setEmailId(getEncryptedPatronEmailId(bulkRequestForm.getPatronEmailAddress()));
                bulkRequestItemEntity.setBulkRequestName(bulkRequestForm.getBulkRequestName());
                bulkRequestItemEntity.setBulkRequestFileName(multipartFile.getOriginalFilename());
                bulkRequestItemEntity.setBulkRequestFileData(bytes);
                bulkRequestItemEntity.setPatronId(bulkRequestForm.getPatronBarcodeInRequest());
                bulkRequestItemEntity.setStopCode(bulkRequestForm.getDeliveryLocationInRequest());
                bulkRequestItemEntity.setRequestingInstitutionId(institutionEntity.getInstitutionId());
                bulkRequestItemEntity.setNotes(bulkRequestForm.getRequestNotes());
                bulkRequestItemEntity.setBulkRequestStatus(RecapConstants.IN_PROCESS);
                BulkRequestItemEntity savedBulkRequestItemEntity = bulkRequestDetailsRepository.save(bulkRequestItemEntity);

                String bulkRequestItemUrl = scsbUrl + RecapConstants.BULK_REQUEST_ITEM_URL;
                HttpEntity requestEntity = new HttpEntity<>(restHeaderService.getHttpHeaders());
                UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(bulkRequestItemUrl).queryParam("bulkRequestId", savedBulkRequestItemEntity.getBulkRequestId());
                new RestTemplate().exchange(builder.build().encode().toUri(), HttpMethod.POST, requestEntity, BulkRequestResponse.class);

                bulkRequestForm.setSubmitted(true);
                bulkRequestForm.setFileName(multipartFile.getOriginalFilename());
            }else {
                bulkRequestForm.setShowRequestErrorMsg(true);
                bulkRequestForm.setErrorMessage("Patron Barcode is incorrect");
            }
        } catch (IOException e) {
            logger.error(RecapConstants.LOG_ERROR, e);
        }
    }

    private Boolean processPatronValidation(BulkRequestForm bulkRequestForm) {
        BulkRequestInformation bulkRequestInformation = new BulkRequestInformation();
        bulkRequestInformation.setRequestingInstitution(bulkRequestForm.getRequestingInstitution());
        bulkRequestInformation.setPatronBarcode(bulkRequestForm.getPatronBarcodeInRequest());
        HttpEntity httpEntity = new HttpEntity(bulkRequestInformation,restHeaderService.getHttpHeaders());
        ResponseEntity<Boolean> responseEntity = new RestTemplate().exchange(scsbUrl + "/requestItem/patronValidationBulkRequest", HttpMethod.POST,httpEntity, Boolean.class);
        return responseEntity.getBody();
    }

    public void processSearchRequest(BulkRequestForm bulkRequestForm) {
        try{
            bulkRequestForm.setPageNumber(0);
            bulkRequestForm.setPageSize(10);
            if (CollectionUtils.isNotEmpty(bulkRequestForm.getBulkSearchResultRows())){
                bulkRequestForm.getBulkSearchResultRows().clear();
            }
            getPaginatedSearchResults(bulkRequestForm);
        }catch (Exception e){
            logger.error(RecapConstants.LOG_ERROR,e);
        }
    }


    public void processOnPageSizeChange(BulkRequestForm bulkRequestForm) {
        try {
            bulkRequestForm.setPageNumber(getPageNumberOnPageSizeChange(bulkRequestForm));
            getPaginatedSearchResults(bulkRequestForm);
        } catch (ParseException e) {
            logger.error(RecapConstants.LOG_ERROR,e);
        }
    }


    public void getPaginatedSearchResults(BulkRequestForm bulkRequestForm){
        Page<BulkRequestItemEntity> bulkRequestItemEntities = bulkSearchRequestService.processSearchRequest(bulkRequestForm);
        buildBulkSearchResultRows(bulkRequestForm,bulkRequestItemEntities);
    }


    private void buildBulkSearchResultRows(BulkRequestForm bulkRequestForm, Page<BulkRequestItemEntity> bulkRequestItemEntities) {
        if (bulkRequestItemEntities.getTotalElements() > 0) {
            List<BulkRequestItemEntity> bulkRequestItemEntityList = bulkRequestItemEntities.getContent();
            List<BulkSearchResultRow> bulkSearchResultRows = new ArrayList<>();
            Map<Integer, String> institutionMap = institutionDetailsRepository.getInstitutionCodeForSuperAdmin().stream().collect(Collectors.toMap(InstitutionEntity::getInstitutionId, InstitutionEntity::getInstitutionCode));
            for (BulkRequestItemEntity bulkRequestItemEntity : bulkRequestItemEntityList) {
                try {
                    BulkSearchResultRow bulkSearchResultRow = new BulkSearchResultRow();
                    bulkSearchResultRow.setBulkRequestId(bulkRequestItemEntity.getBulkRequestId());
                    bulkSearchResultRow.setBulkRequestName(bulkRequestItemEntity.getBulkRequestName());
                    bulkSearchResultRow.setFileName(bulkRequestItemEntity.getBulkRequestFileName());
                    bulkSearchResultRow.setPatronBarcode(bulkRequestItemEntity.getPatronId());
                    bulkSearchResultRow.setRequestingInstitution(institutionMap.get(bulkRequestItemEntity.getRequestingInstitutionId()));
                    bulkSearchResultRow.setDeliveryLocation(bulkRequestItemEntity.getStopCode());
                    bulkSearchResultRow.setCreatedBy(bulkRequestItemEntity.getCreatedBy());
                    bulkSearchResultRow.setEmailAddress(getDecryptedPatronEmailId(bulkRequestItemEntity.getEmailId()));
                    bulkSearchResultRow.setCreatedDate(bulkRequestItemEntity.getCreatedDate());
                    bulkSearchResultRow.setStatus(bulkRequestItemEntity.getBulkRequestStatus());
                    bulkSearchResultRow.setBulkRequestNotes(bulkRequestItemEntity.getNotes());
                    bulkSearchResultRows.add(bulkSearchResultRow);
                } catch (Exception ex) {
                    logger.error(RecapConstants.LOG_ERROR, ex);
                }
            }
            bulkRequestForm.setTotalRecordsCount(String.valueOf(bulkRequestItemEntities.getTotalElements()));
            bulkRequestForm.setTotalPageCount(bulkRequestItemEntities.getTotalPages());
            bulkRequestForm.setBulkSearchResultRows(bulkSearchResultRows);
        } else {
            bulkRequestForm.setMessage("No Search Results Found");
        }
        bulkRequestForm.setShowResults(true);
    }


    private Integer getPageNumberOnPageSizeChange(BulkRequestForm bulkRequestForm) throws ParseException {
        int totalRecordsCount;
        Integer pageNumber = bulkRequestForm.getPageNumber();
        totalRecordsCount = NumberFormat.getNumberInstance().parse(bulkRequestForm.getTotalRecordsCount()).intValue();
        int totalPagesCount = (int) Math.ceil((double) totalRecordsCount / (double) bulkRequestForm.getPageSize());
        if (totalPagesCount > 0 && pageNumber >= totalPagesCount) {
            pageNumber = totalPagesCount - 1;
        }
        return pageNumber;
    }

    public void processDeliveryLocations(BulkRequestForm bulkRequestForm) {
        InstitutionEntity institutionEntity = institutionDetailsRepository.findByInstitutionCode(bulkRequestForm.getRequestingInstitution());
        bulkRequestForm.setDeliveryLocations(bulkCustomerCodeDetailsRepository.findByOwningInstitutionId(institutionEntity.getInstitutionId()));
    }

    private String getEncryptedPatronEmailId(String patronEmailAddress) {
        return StringUtils.isNotBlank(patronEmailAddress) ? securityUtil.getEncryptedValue(patronEmailAddress) : patronEmailAddress;
    }

    private String getDecryptedPatronEmailId(String patronEmailAddress) {
        return StringUtils.isNotBlank(patronEmailAddress) ? securityUtil.getDecryptedValue(patronEmailAddress) : patronEmailAddress;
    }
}