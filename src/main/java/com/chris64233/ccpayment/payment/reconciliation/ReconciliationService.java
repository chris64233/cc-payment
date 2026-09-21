package com.chris64233.ccpayment.payment.reconciliation;

import com.chris64233.ccpayment.payment.ErrorCode;
import com.chris64233.ccpayment.payment.PaymentException;
import com.chris64233.ccpayment.payment.reconciliation.dto.ReconciliationBatchDetailResponse;
import com.chris64233.ccpayment.payment.reconciliation.dto.ReconciliationBatchResponse;
import com.chris64233.ccpayment.payment.reconciliation.dto.ReconciliationDetailRequest;
import com.chris64233.ccpayment.payment.reconciliation.dto.ReconciliationSubmissionRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

@Service
public class ReconciliationService {

    private final ReconciliationBatchRepository batchRepository;
    private final ReconciliationProcessor processor;
    private final ObjectMapper objectMapper;

    public ReconciliationService(ReconciliationBatchRepository batchRepository,
                                 ReconciliationProcessor processor,
                                 ObjectMapper objectMapper) {
        this.batchRepository = batchRepository;
        this.processor = processor;
        this.objectMapper = objectMapper;
    }

    public ReconciliationBatchDetailResponse submit(String channel, LocalDate accountingDate,
                                                    ReconciliationSubmissionRequest request) {
        List<ReconciliationDetailRequest> details = request.details();
        validateBatch(details);
        String fingerprint = fingerprint(details);

        return batchRepository.findByChannelAndAccountingDate(channel, accountingDate)
                .map(existing -> processor.replay(channel, accountingDate, fingerprint))
                .orElseGet(() -> createNew(channel, accountingDate, details, fingerprint));
    }

    private ReconciliationBatchDetailResponse createNew(String channel, LocalDate accountingDate,
                                                        List<ReconciliationDetailRequest> details,
                                                        String fingerprint) {
        try {
            return processor.create(channel, accountingDate, details, fingerprint);
        } catch (DataIntegrityViolationException e) {
            // 并发下同一渠道同一账务日期唯一约束冲突，依据数据库约束兜底
            return processor.replay(channel, accountingDate, fingerprint);
        }
    }

    @Transactional(readOnly = true)
    public ReconciliationBatchResponse getSummary(String batchNo) {
        return batchRepository.findByBatchNo(batchNo)
                .map(ReconciliationBatchResponse::from)
                .orElseThrow(() -> new PaymentException(ErrorCode.RECONCILIATION_BATCH_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public ReconciliationBatchDetailResponse getDetails(String batchNo) {
        return batchRepository.findByBatchNo(batchNo)
                .map(ReconciliationBatchDetailResponse::from)
                .orElseThrow(() -> new PaymentException(ErrorCode.RECONCILIATION_BATCH_NOT_FOUND));
    }

    private void validateBatch(List<ReconciliationDetailRequest> details) {
        Set<String> channelTxnNos = new HashSet<>();
        Set<String> paymentNos = new HashSet<>();
        for (ReconciliationDetailRequest detail : details) {
            if (!channelTxnNos.add(detail.channelTxnNo())) {
                throw new PaymentException(ErrorCode.VALIDATION_ERROR, "渠道交易号在同一批次内不能重复：" + detail.channelTxnNo());
            }
            if (!paymentNos.add(detail.paymentNo())) {
                throw new PaymentException(ErrorCode.VALIDATION_ERROR, "支付单号在同一批次内不能重复：" + detail.paymentNo());
            }
        }
    }

    private String fingerprint(List<ReconciliationDetailRequest> details) {
        List<List<String>> canonical = details.stream()
                .map(detail -> List.of(
                        detail.channelTxnNo(),
                        detail.paymentNo(),
                        detail.amount().stripTrailingZeros().toPlainString(),
                        detail.currency(),
                        detail.channelResult().name()
                ))
                .sorted(Comparator.comparing(line -> line.get(0) + "|" + line.get(1)))
                .toList();
        try {
            String raw = objectMapper.writeValueAsString(canonical);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (JacksonException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("对账内容摘要计算失败", e);
        }
    }
}
