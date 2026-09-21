package com.chris64233.ccpayment.payment.reconciliation;

import com.chris64233.ccpayment.payment.PaymentOrder;
import com.chris64233.ccpayment.payment.PaymentOrderRepository;
import com.chris64233.ccpayment.payment.PaymentOrderStatus;
import com.chris64233.ccpayment.payment.PaymentResult;
import com.chris64233.ccpayment.payment.ErrorCode;
import com.chris64233.ccpayment.payment.PaymentException;
import com.chris64233.ccpayment.payment.reconciliation.dto.ReconciliationBatchDetailResponse;
import com.chris64233.ccpayment.payment.reconciliation.dto.ReconciliationDetailRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ReconciliationProcessor {

    private final ReconciliationBatchRepository batchRepository;
    private final PaymentOrderRepository orderRepository;
    private final Clock clock;

    public ReconciliationProcessor(ReconciliationBatchRepository batchRepository,
                                   PaymentOrderRepository orderRepository,
                                   Clock clock) {
        this.batchRepository = batchRepository;
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    @Transactional
    public ReconciliationBatchDetailResponse create(String channel, LocalDate accountingDate,
                                                    List<ReconciliationDetailRequest> details,
                                                    String fingerprint) {
        List<ReconciliationLine> lines = compare(details);
        int matchedCount = (int) lines.stream()
                .filter(line -> line.getMatchStatus() == ReconciliationMatchStatus.MATCHED)
                .count();
        int discrepancyCount = lines.size() - matchedCount;

        ReconciliationBatch batch = new ReconciliationBatch(
                generateBatchNo(),
                channel,
                accountingDate,
                fingerprint,
                lines.size(),
                matchedCount,
                discrepancyCount,
                Instant.now(clock)
        );
        for (ReconciliationLine line : lines) {
            batch.addLine(line);
        }
        return ReconciliationBatchDetailResponse.from(batchRepository.saveAndFlush(batch));
    }

    @Transactional(readOnly = true)
    public ReconciliationBatchDetailResponse replay(String channel, LocalDate accountingDate,
                                                    String fingerprint) {
        ReconciliationBatch batch = batchRepository
                .findByChannelAndAccountingDate(channel, accountingDate)
                .orElseThrow(() -> new PaymentException(ErrorCode.RECONCILIATION_BATCH_NOT_FOUND));
        if (!batch.getRequestFingerprint().equals(fingerprint)) {
            throw new PaymentException(ErrorCode.RECONCILIATION_BATCH_CONTENT_CONFLICT);
        }
        return ReconciliationBatchDetailResponse.from(batch);
    }

    private List<ReconciliationLine> compare(List<ReconciliationDetailRequest> details) {
        List<String> paymentNos = details.stream()
                .map(ReconciliationDetailRequest::paymentNo)
                .distinct()
                .toList();
        Map<String, PaymentOrder> orders = orderRepository.findByPaymentNoIn(paymentNos).stream()
                .collect(Collectors.toMap(PaymentOrder::getPaymentNo, Function.identity()));

        List<ReconciliationLine> lines = new ArrayList<>();
        for (int index = 0; index < details.size(); index++) {
            ReconciliationDetailRequest detail = details.get(index);
            lines.add(compareOne(detail, orders.get(detail.paymentNo()), index));
        }
        return lines;
    }

    private ReconciliationLine compareOne(ReconciliationDetailRequest detail,
                                          PaymentOrder order, int lineOrder) {
        List<ReconciliationDiscrepancyType> discrepancies = new ArrayList<>();
        if (order == null) {
            discrepancies.add(ReconciliationDiscrepancyType.LOCAL_PAYMENT_NOT_FOUND);
        } else {
            if (detail.amount().compareTo(order.getAmount()) != 0) {
                discrepancies.add(ReconciliationDiscrepancyType.AMOUNT_MISMATCH);
            }
            if (!detail.currency().equals(order.getCurrency())) {
                discrepancies.add(ReconciliationDiscrepancyType.CURRENCY_MISMATCH);
            }
            if (!statusMatches(detail.channelResult(), order.getStatus())) {
                discrepancies.add(ReconciliationDiscrepancyType.STATUS_MISMATCH);
            }
        }
        ReconciliationMatchStatus matchStatus = discrepancies.isEmpty()
                ? ReconciliationMatchStatus.MATCHED
                : ReconciliationMatchStatus.MISMATCHED;
        return new ReconciliationLine(
                detail.channelTxnNo(),
                detail.paymentNo(),
                detail.amount(),
                detail.currency(),
                detail.channelResult(),
                matchStatus,
                discrepancies,
                lineOrder
        );
    }

    private boolean statusMatches(PaymentResult channelResult, PaymentOrderStatus localStatus) {
        if (channelResult == PaymentResult.SUCCESS) {
            return localStatus == PaymentOrderStatus.SUCCESS
                    || localStatus == PaymentOrderStatus.PARTIALLY_REFUNDED
                    || localStatus == PaymentOrderStatus.REFUNDED;
        }
        return localStatus == PaymentOrderStatus.FAILED;
    }

    private String generateBatchNo() {
        return "RC" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }
}
