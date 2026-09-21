package com.chris64233.ccpayment.payment;

import com.chris64233.ccpayment.payment.dto.ReconciliationBatchResponse;
import com.chris64233.ccpayment.payment.dto.ReconciliationDetailRequest;
import com.chris64233.ccpayment.payment.dto.ReconciliationEntryResponse;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ReconciliationProcessor {

    private final ReconciliationBatchRepository batchRepository;
    private final ReconciliationEntryRepository entryRepository;
    private final PaymentOrderRepository orderRepository;

    public ReconciliationProcessor(ReconciliationBatchRepository batchRepository,
                                   ReconciliationEntryRepository entryRepository,
                                   PaymentOrderRepository orderRepository) {
        this.batchRepository = batchRepository;
        this.entryRepository = entryRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public ReconciliationBatchResponse process(String channel, LocalDate accountingDate,
                                               List<ReconciliationDetailRequest> details,
                                               String fingerprint) {
        return batchRepository.findByChannelAndAccountingDate(channel, accountingDate)
                .map(batch -> replayOrThrow(batch, fingerprint))
                .orElseGet(() -> createBatch(channel, accountingDate, details, fingerprint));
    }

    private ReconciliationBatchResponse replayOrThrow(ReconciliationBatch batch, String fingerprint) {
        if (!batch.getRequestFingerprint().equals(fingerprint)) {
            throw new PaymentException(ErrorCode.RECONCILIATION_BATCH_CONFLICT);
        }
        return toResponse(batch);
    }

    private ReconciliationBatchResponse createBatch(String channel, LocalDate accountingDate,
                                                    List<ReconciliationDetailRequest> details,
                                                    String fingerprint) {
        List<String> paymentNos = details.stream()
                .map(ReconciliationDetailRequest::paymentNo)
                .distinct()
                .toList();
        Map<String, PaymentOrder> orders = orderRepository.findAllByPaymentNoIn(paymentNos).stream()
                .collect(Collectors.toMap(PaymentOrder::getPaymentNo, Function.identity()));

        record Compared(ReconciliationDetailRequest detail,
                        ReconciliationResult result,
                        String discrepancyTypes) {
        }

        List<Compared> compared = new ArrayList<>();
        int matchCount = 0;
        int mismatchCount = 0;
        for (ReconciliationDetailRequest detail : details) {
            Set<ReconciliationDiscrepancyType> discrepancies = compare(detail, orders.get(detail.paymentNo()));
            ReconciliationResult result = discrepancies.isEmpty()
                    ? ReconciliationResult.MATCH
                    : ReconciliationResult.MISMATCH;
            if (result == ReconciliationResult.MATCH) {
                matchCount++;
            } else {
                mismatchCount++;
            }
            compared.add(new Compared(detail, result, joinDiscrepancies(discrepancies)));
        }

        ReconciliationBatch savedBatch = batchRepository.saveAndFlush(new ReconciliationBatch(
                generateBatchNo(), channel, accountingDate, fingerprint,
                details.size(), matchCount, mismatchCount));
        int lineNo = 0;
        for (Compared item : compared) {
            lineNo++;
            entryRepository.save(new ReconciliationEntry(
                    savedBatch, lineNo,
                    item.detail().channelTxnNo(), item.detail().paymentNo(),
                    item.detail().amount(), item.detail().currency(), item.detail().channelResult(),
                    item.result(), item.discrepancyTypes()));
        }
        return toResponse(savedBatch);
    }

    private Set<ReconciliationDiscrepancyType> compare(ReconciliationDetailRequest detail,
                                                       PaymentOrder order) {
        Set<ReconciliationDiscrepancyType> discrepancies = EnumSet
                .noneOf(ReconciliationDiscrepancyType.class);
        if (order == null) {
            discrepancies.add(ReconciliationDiscrepancyType.PAYMENT_ORDER_NOT_FOUND);
            return discrepancies;
        }
        if (detail.amount().compareTo(order.getAmount()) != 0) {
            discrepancies.add(ReconciliationDiscrepancyType.AMOUNT_MISMATCH);
        }
        if (!detail.currency().equals(order.getCurrency())) {
            discrepancies.add(ReconciliationDiscrepancyType.CURRENCY_MISMATCH);
        }
        if (!statusMatches(detail.channelResult(), order.getStatus())) {
            discrepancies.add(ReconciliationDiscrepancyType.STATUS_MISMATCH);
        }
        return discrepancies;
    }

    private boolean statusMatches(String channelResult, PaymentOrderStatus localStatus) {
        if ("SUCCESS".equals(channelResult)) {
            return localStatus == PaymentOrderStatus.SUCCESS
                    || localStatus == PaymentOrderStatus.PARTIALLY_REFUNDED
                    || localStatus == PaymentOrderStatus.REFUNDED;
        }
        return "FAILED".equals(channelResult) && localStatus == PaymentOrderStatus.FAILED;
    }

    private String joinDiscrepancies(Set<ReconciliationDiscrepancyType> discrepancies) {
        return discrepancies.stream()
                .map(Enum::name)
                .collect(Collectors.joining(","));
    }

    private ReconciliationBatchResponse toResponse(ReconciliationBatch batch) {
        List<ReconciliationEntryResponse> details = entryRepository
                .findByBatchIdOrderByLineNoAsc(batch.getId()).stream()
                .map(ReconciliationEntryResponse::from)
                .toList();
        return ReconciliationBatchResponse.from(batch, details);
    }

    private String generateBatchNo() {
        return "RC" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }
}
