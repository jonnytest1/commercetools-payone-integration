package com.commercetools.pspadapter.payone.transaction;

import com.commercetools.pspadapter.payone.domain.ctp.CustomTypeBuilder;
import com.commercetools.pspadapter.payone.domain.ctp.PaymentWithCartLike;
import com.commercetools.pspadapter.payone.domain.payone.PayonePostService;
import com.commercetools.pspadapter.payone.domain.payone.exceptions.PayoneException;
import com.commercetools.pspadapter.payone.domain.payone.model.common.BaseRequest;
import com.commercetools.pspadapter.payone.domain.payone.model.common.PayoneResponseFields;
import com.commercetools.pspadapter.payone.domain.payone.model.common.ResponseStatus;
import com.commercetools.pspadapter.payone.mapping.CustomFieldKeys;
import com.commercetools.pspadapter.payone.mapping.PayoneRequestFactory;
import com.google.common.cache.LoadingCache;
import io.sphere.sdk.client.BlockingSphereClient;
import io.sphere.sdk.payments.Payment;
import io.sphere.sdk.payments.Transaction;
import io.sphere.sdk.payments.TransactionState;
import io.sphere.sdk.payments.commands.PaymentUpdateCommand;
import io.sphere.sdk.payments.commands.updateactions.AddInterfaceInteraction;
import io.sphere.sdk.payments.commands.updateactions.ChangeTransactionInteractionId;
import io.sphere.sdk.payments.commands.updateactions.ChangeTransactionState;
import io.sphere.sdk.types.Type;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static java.lang.String.format;


/**
 * Base class for validating/executing/re-trying preauthorization/authorization (authorization/charge) transactions.
 */
abstract public class BaseDefaultTransactionExecutor extends TransactionBaseExecutor {

    protected PayoneRequestFactory requestFactory;
    protected final PayonePostService payonePostService;

    public BaseDefaultTransactionExecutor(@Nonnull LoadingCache<String, Type> typeCache,
                                          @Nonnull final PayoneRequestFactory requestFactory,
                                          @Nonnull final PayonePostService payonePostService,
                                          @Nonnull BlockingSphereClient client) {
        super(typeCache, client);
        this.requestFactory = requestFactory;
        this.payonePostService = payonePostService;

    }

    /**
     * Create transaction with respective preauthorization/authorization (authorization/charge) type.
     *
     * @param paymentWithCartLike payment/cart which include to the request
     * @return {@link BaseRequest} implementation with respective payment and transaction type
     */
    @Nonnull
    abstract protected BaseRequest createRequest(PaymentWithCartLike paymentWithCartLike);

    /**
     * @return implementation specific logger
     */
    @Nonnull
    abstract protected Logger getClassLogger();

    @Override
    public boolean wasExecuted(PaymentWithCartLike paymentWithCartLike, Transaction transaction) {
        boolean hasTransactionId = getCustomFieldsOfType(paymentWithCartLike,
                CustomTypeBuilder.PAYONE_INTERACTION_RESPONSE,
                CustomTypeBuilder.PAYONE_INTERACTION_REDIRECT)
                .anyMatch(fields -> transaction.getId().equals(fields.getFieldAsString(CustomFieldKeys.TRANSACTION_ID_FIELD)));

        if (!hasTransactionId) {
            return getCustomFieldsOfType(paymentWithCartLike, CustomTypeBuilder.PAYONE_INTERACTION_NOTIFICATION)
                    //sequenceNumber field is mandatory -> can't be null
                    .anyMatch(fields -> fields.getFieldAsString(CustomFieldKeys.SEQUENCE_NUMBER_FIELD).equals(transaction.getInteractionId()));
        }

        return true;
    }

    @Override
    @Nonnull
    protected PaymentWithCartLike execute(final PaymentWithCartLike paymentWithCartLike,
                                          final Transaction transaction) {
        final String transactionId = transaction.getId();
        final String sequenceNumber = String.valueOf(getNextSequenceNumber(paymentWithCartLike));

        final BaseRequest request = createRequest(paymentWithCartLike);

        final Map<String, Object> fieldsMap1 = new HashMap<>();
        fieldsMap1.put(CustomFieldKeys.REQUEST_FIELD, request.toStringMap(true).toString());
        fieldsMap1.put(CustomFieldKeys.TRANSACTION_ID_FIELD, transactionId);
        fieldsMap1.put(CustomFieldKeys.TIMESTAMP_FIELD, ZonedDateTime.now());

        final Payment updatedPayment = client.executeBlocking(
                PaymentUpdateCommand.of(paymentWithCartLike.getPayment(),
                    Arrays.asList(
                        AddInterfaceInteraction.ofTypeKeyAndObjects(CustomTypeBuilder.PAYONE_INTERACTION_REQUEST,
                            fieldsMap1),
                        ChangeTransactionInteractionId.of(sequenceNumber, transactionId)
                    )
                ));

        try {
            final Map<String, String> response = payonePostService.executePost(request);

            final String status = response.get(PayoneResponseFields.STATUS);
            if (ResponseStatus.REDIRECT.getStateCode().equals(status)) {
                final Map<String, Object> fieldsMap2 = new HashMap<>();
                fieldsMap2.put(CustomFieldKeys.RESPONSE_FIELD, responseToJsonString(response));
                fieldsMap2.put(CustomFieldKeys.REDIRECT_URL_FIELD, response.get(PayoneResponseFields.REDIRECT_URL));
                fieldsMap2.put(CustomFieldKeys.TRANSACTION_ID_FIELD, transactionId);
                fieldsMap2.put(CustomFieldKeys.TIMESTAMP_FIELD, ZonedDateTime.now());

                final AddInterfaceInteraction interfaceInteraction = AddInterfaceInteraction.ofTypeKeyAndObjects(CustomTypeBuilder.PAYONE_INTERACTION_REDIRECT,
                        fieldsMap2);

                return update(paymentWithCartLike, updatedPayment, getRedirectUpdateActions(TransactionState.PENDING, updatedPayment, transactionId, response, interfaceInteraction));

            } else {
                final Map<String, Object> fieldsMap3 = new HashMap<>();
                fieldsMap3.put(CustomFieldKeys.RESPONSE_FIELD, responseToJsonString(response));
                fieldsMap3.put(CustomFieldKeys.TRANSACTION_ID_FIELD, transactionId);
                fieldsMap3.put(CustomFieldKeys.TIMESTAMP_FIELD, ZonedDateTime.now());

                final AddInterfaceInteraction interfaceInteraction = AddInterfaceInteraction.ofTypeKeyAndObjects(CustomTypeBuilder.PAYONE_INTERACTION_RESPONSE,
                        fieldsMap3);

                if (ResponseStatus.APPROVED.getStateCode().equals(status)) {

                    return update(paymentWithCartLike, updatedPayment, getDefaultSuccessUpdateActions(TransactionState.SUCCESS, updatedPayment, transactionId, response, interfaceInteraction));

                } else if (ResponseStatus.ERROR.getStateCode().equals(status)) {

                    return update(paymentWithCartLike, updatedPayment, getDefaultUpdateActions(TransactionState.FAILURE, updatedPayment, transactionId, response, interfaceInteraction));

                } else if (ResponseStatus.PENDING.getStateCode().equals(status)) {

                    return update(paymentWithCartLike, updatedPayment, getDefaultSuccessUpdateActions(TransactionState.PENDING, updatedPayment, transactionId, response, interfaceInteraction));

                }
            }

            // TODO: https://github.com/commercetools/commercetools-payone-integration/issues/199
            throw new IllegalStateException("Unknown Payone status: " + status);
        } catch (PayoneException paymentException) {
            getClassLogger().error(
                format("Request to Payone failed for commercetools Payment with id '%s' and Transaction with id '%s'.",
                    paymentWithCartLike.getPayment().getId(), transactionId), paymentException);

            final Map<String, Object> fieldsMap4 = new HashMap<>();
            fieldsMap4.put(CustomFieldKeys.RESPONSE_FIELD, exceptionToResponseJsonString(paymentException));
            fieldsMap4.put(CustomFieldKeys.TRANSACTION_ID_FIELD, transactionId);
            fieldsMap4.put(CustomFieldKeys.TIMESTAMP_FIELD, ZonedDateTime.now());

            final AddInterfaceInteraction interfaceInteraction = AddInterfaceInteraction.ofTypeKeyAndObjects(CustomTypeBuilder.PAYONE_INTERACTION_RESPONSE,
                    fieldsMap4);

            final ChangeTransactionState failureTransaction = ChangeTransactionState.of(TransactionState.FAILURE, transactionId);

            return update(paymentWithCartLike, updatedPayment, Arrays.asList(interfaceInteraction, failureTransaction));
        }
    }
}
