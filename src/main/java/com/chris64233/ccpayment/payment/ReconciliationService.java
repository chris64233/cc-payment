package com.chris64233.ccpayment.payment;

import com.chris64233.ccpayment.payment.dto.ReconciliationBatchResponse;
import com.chris64233.ccpayment.payment.dto.ReconciliationDetailRequest;
import com.chris64233.ccpayment.payment.dto.ReconciliationEntryResponse;
import com.chris64233.ccpayment.payment.dto.SubmitReconciliationRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.time.LocalDate;
import java.util.stream.Collectors;

@Service
public class ReconciliationService {

    private final ReconciliationProcessor processor;
    private final ReconciliationBatchRepository batchRepository;
    private final ReconciliationEntryRepository entryRepository;

    public ReconciliationService(ReconciliationProcessor processor,
                                 ReconciliationBatchRepository batchRepository,
                                 ReconciliationEntryRepository entryRepository) {
        this.processor = processor;
        this.batchRepository = batchRepository;
        this.entryRepository = entryRepository;
    }

    public Submission submit(SubmitReconciliationRequest request) {
        validateBatch(request.details());
        String fingerprint = fingerprint(request.channel(), request.accountingDate(), request.details());
        try {
            ReconciliationBatchResponse existing = batchRepository
                    .findByChannelAndAccountingDate(request.channel(), request.accountingDate())
                    .map(batch -> replayOrThrow(batch, fingerprint))
                    .orElse(null);
            if (existing != null) {
                return new Submission(existing, false);
            }
            return new Submission(processor.process(
                    request.channel(), request.accountingDate(), request.details(), fingerprint), true);
        } catch (DataIntegrityViolationException e) {
            // 并发下 (channel, accounting_date) 唯一约束冲突，依据数据库约束兜底
            return batchRepository.findByChannelAndAccountingDate(
                            request.channel(), request.accountingDate())
                    .map(batch -> new Submission(replayOrThrow(batch, fingerprint), false))
                    .orElseThrow(() -> e);
        }
    }

    @Transactional(readOnly = true)
    public ReconciliationBatchResponse getByBatchNo(String batchNo) {
        ReconciliationBatch batch = batchRepository.findByBatchNo(batchNo)
                .orElseThrow(() -> new PaymentException(ErrorCode.RECONCILIATION_BATCH_NOT_FOUND));
        List<ReconciliationEntryResponse> details = entryRepository
                .findByBatchIdOrderByLineNoAsc(batch.getId()).stream()
                .map(ReconciliationEntryResponse::from)
                .toList();
        return ReconciliationBatchResponse.from(batch, details);
    }

    private ReconciliationBatchResponse replayOrThrow(ReconciliationBatch batch, String fingerprint) {
        if (!batch.getRequestFingerprint().equals(fingerprint)) {
            throw new PaymentException(ErrorCode.RECONCILIATION_BATCH_CONFLICT);
        }
        List<ReconciliationEntryResponse> details = entryRepository
                .findByBatchIdOrderByLineNoAsc(batch.getId()).stream()
                .map(ReconciliationEntryResponse::from)
                .toList();
        return ReconciliationBatchResponse.from(batch, details);
    }

    private void validateBatch(List<ReconciliationDetailRequest> details) {
        Set<String> channelTxnNos = new HashSet<>();
        Set<String> paymentNos = new HashSet<>();
        for (ReconciliationDetailRequest detail : details) {
            if (!channelTxnNos.add(detail.channelTxnNo())) {
                throw new PaymentException(ErrorCode.VALIDATION_ERROR, "渠道交易号在同一批次内不能重复："
                        + detail.channelTxnNo());
            }
            if (!paymentNos.add(detail.paymentNo())) {
                throw new PaymentException(ErrorCode.VALIDATION_ERROR, "支付单号在同一批次内不能重复："
                        + detail.paymentNo());
            }
        }
    }

    private String fingerprint(String channel, LocalDate accountingDate,
                               List<ReconciliationDetailRequest> details) {
        // 对每条明细做规范化并排序，使请求与明细排列顺序无关
        String canonical = details.stream()
                .map(this::canonicalDetail)
                .sorted()
                .collect(Collectors.joining("\n"));
        String raw = channel + "|" + accountingDate + "\n" + canonical;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String canonicalDetail(ReconciliationDetailRequest detail) {
        return detail.channelTxnNo() + "|"
                + detail.paymentNo() + "|"
                + detail.amount().stripTrailingZeros().toPlainString() + "|"
                + detail.currency() + "|"
                + detail.channelResult();
    }

    public record Submission(ReconciliationBatchResponse response, boolean created) {
    }
}
