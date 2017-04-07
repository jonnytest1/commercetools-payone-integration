package com.commercetools.pspadapter.payone;

import com.commercetools.pspadapter.payone.domain.ctp.CommercetoolsQueryExecutor;
import com.commercetools.pspadapter.payone.domain.ctp.PaymentWithCartLike;
import com.commercetools.pspadapter.payone.domain.ctp.exceptions.NoCartLikeFoundException;
import io.sphere.sdk.payments.Payment;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ConcurrentModificationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * @author fhaertig
 * @since 07.12.15
 */
public abstract class ScheduledJob implements Job {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduledJob.class);

    public static final String INTEGRATION_SERVICE = "INTEGRATION_SERVICE";

    @Override
    public void execute(final JobExecutionContext context) throws JobExecutionException {
        JobDataMap dataMap = context.getMergedJobDataMap();

        final IntegrationService integrationService = (IntegrationService) dataMap.get(INTEGRATION_SERVICE);

        final ZonedDateTime sinceDate = getSinceDateTime();

        integrationService.getTenantFactories().forEach(tenantFactory -> {
            final CommercetoolsQueryExecutor queryExecutor = tenantFactory.getCommercetoolsQueryExecutor();
            final PaymentDispatcher paymentDispatcher = tenantFactory.getPaymentDispatcher();

            final Consumer<Payment> paymentConsumer = payment -> {
                try {
                    final PaymentWithCartLike paymentWithCartLike = queryExecutor.getPaymentWithCartLike(payment.getId(), CompletableFuture.completedFuture(payment));
                    paymentDispatcher.accept(paymentWithCartLike);
                } catch (final NoCartLikeFoundException ex) {
                    LOG.debug("Could not dispatch payment with ID \"{}\": {}", payment.getId(), ex.getMessage());
                } catch (final ConcurrentModificationException ex) {
                    LOG.info("Could not dispatch payment with ID \"{}\": The payment is currently processed by someone else.", payment.getId());
                } catch (final Throwable ex) {
                    LOG.error("Error dispatching payment with ID \"{}\"", payment.getId(), ex);
                }
            };

            queryExecutor.consumePaymentCreatedMessages(sinceDate, paymentConsumer);
            queryExecutor.consumePaymentTransactionAddedMessages(sinceDate, paymentConsumer);
        });
    }

    protected abstract ZonedDateTime getSinceDateTime();
}
