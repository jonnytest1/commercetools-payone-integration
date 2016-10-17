package specs;

import com.commercetools.pspadapter.payone.ServiceFactory;
import com.commercetools.pspadapter.payone.config.PropertyProvider;
import com.commercetools.pspadapter.payone.domain.ctp.CustomTypeBuilder;
import com.commercetools.pspadapter.payone.mapping.CustomFieldKeys;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.*;
import com.neovisionaries.i18n.CountryCode;
import io.sphere.sdk.carts.Cart;
import io.sphere.sdk.carts.CartDraft;
import io.sphere.sdk.carts.CartDraftBuilder;
import io.sphere.sdk.carts.commands.CartCreateCommand;
import io.sphere.sdk.carts.commands.CartUpdateCommand;
import io.sphere.sdk.carts.commands.updateactions.AddLineItem;
import io.sphere.sdk.carts.commands.updateactions.AddPayment;
import io.sphere.sdk.carts.commands.updateactions.SetBillingAddress;
import io.sphere.sdk.carts.commands.updateactions.SetShippingAddress;
import io.sphere.sdk.client.BlockingSphereClient;
import io.sphere.sdk.models.Address;
import io.sphere.sdk.orders.OrderFromCartDraft;
import io.sphere.sdk.orders.PaymentState;
import io.sphere.sdk.orders.commands.OrderFromCartCreateCommand;
import io.sphere.sdk.payments.Payment;
import io.sphere.sdk.payments.Transaction;
import io.sphere.sdk.payments.TransactionState;
import io.sphere.sdk.payments.queries.PaymentByIdGet;
import io.sphere.sdk.products.Product;
import io.sphere.sdk.products.queries.ProductQuery;
import io.sphere.sdk.types.CustomFields;
import io.sphere.sdk.types.Type;
import io.sphere.sdk.utils.MoneyImpl;
import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.money.Monetary;
import javax.money.MonetaryAmount;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * @author fhaertig
 * @since  10.12.15
 */
public abstract class BaseFixture {
    private static final Logger LOG = LoggerFactory.getLogger(BaseFixture.class);

    protected static final String EMPTY_STRING = "";
    protected static final String NULL_STRING = "null";
    protected static final long PAYONE_NOTIFICATION_TIMEOUT = TimeUnit.MINUTES.toMillis(8);

    private static final String TEST_DATA_VISA_CREDIT_CARD_NO_3_DS = "TEST_DATA_VISA_CREDIT_CARD_NO_3DS";
    private static final String TEST_DATA_VISA_CREDIT_CARD_3_DS = "TEST_DATA_VISA_CREDIT_CARD_3DS";
    private static final String TEST_DATA_3_DS_PASSWORD = "TEST_DATA_3_DS_PASSWORD";
    private static final String TEST_DATA_SW_BANK_TRANSFER_IBAN = "TEST_DATA_SW_BANK_TRANSFER_IBAN";
    private static final String TEST_DATA_SW_BANK_TRANSFER_BIC = "TEST_DATA_SW_BANK_TRANSFER_BIC";
    private static final int INTEGRATION_SERVICE_REQUEST_TIMEOUT = 5000; // @akovalenko 14.10.16: extended from 1.5 sec to 5 sec

    private static final Random randomSource = new Random();
    private BlockingSphereClient ctpClient;
    private URL ctPayoneIntegrationBaseUrl;
    private LoadingCache<String, Type> typeCache;

    /** maps legible payment names to payment IDs (and vice versa)*/
    private BiMap<String, String> payments = HashBiMap.create();

    // FIXME use BeforeClass
    @Before
    public void initializeCommercetoolsClient() throws MalformedURLException {
        final PropertyProvider propertyProvider = new PropertyProvider();
        ctPayoneIntegrationBaseUrl =
                new URL(propertyProvider.getMandatoryNonEmptyProperty("CT_PAYONE_INTEGRATION_URL"));

        //only for creation of test data
        final ServiceFactory serviceFactory = ServiceFactory.withPropertiesFrom(propertyProvider);
        ctpClient = serviceFactory.createCommercetoolsClient();

        typeCache = serviceFactory.createTypeCache(ctpClient);
    }

    public String getHandlePaymentUrl(final String paymentId) throws MalformedURLException {
        return getServiceUrl("/commercetools/handle/payments/" + paymentId);
    }

    public String getNotificationUrl() throws MalformedURLException {
        return getServiceUrl("/payone/notification");
    }

    public String getHealthUrl() throws MalformedURLException {
        return getServiceUrl("/health");
    }

    final String getServiceUrl(String suffix) throws MalformedURLException {
        return new URL(
                ctPayoneIntegrationBaseUrl.getProtocol(),
                ctPayoneIntegrationBaseUrl.getHost(),
                ctPayoneIntegrationBaseUrl.getPort(),
                suffix)
                .toExternalForm();
    }

    public HttpResponse sendGetRequestToUrl(final String url) throws IOException {
        return Request.Get(url)
                .connectTimeout(INTEGRATION_SERVICE_REQUEST_TIMEOUT)
                .execute()
                .returnResponse();
    }

    protected String getConfigurationParameter(final String configParameterName) {
        final String envVariable = System.getenv(configParameterName);
        if (!Strings.isNullOrEmpty(envVariable)) {
            return envVariable;
        }

        final String sysProperty = System.getProperty(configParameterName);
        if (!Strings.isNullOrEmpty(sysProperty)) {
            return sysProperty;
        }

        throw new RuntimeException(String.format(
                "Environment variable required for configuration must not be null or empty: %s",
                configParameterName));
    }

    protected MonetaryAmount createMonetaryAmountFromCent(final Long centAmount, final String currencyCode) {
        return MoneyImpl.ofCents(centAmount, currencyCode);
    }

    protected String createCartAndOrderForPayment(final Payment payment, final String currencyCode) {
        return createCartAndOrderForPayment(payment, currencyCode, "TestBuyer");
    }

    protected String createCartAndOrderForPayment(final Payment payment, final String currencyCode, final String buyerLastName) {
        // create cart and order with product
        final Product product = ctpClient.executeBlocking(ProductQuery.of()).getResults().get(0);

        final CartDraft cardDraft = CartDraftBuilder.of(Monetary.getCurrency(currencyCode)).build();

        final Cart cart = ctpClient.executeBlocking(CartUpdateCommand.of(
                ctpClient.executeBlocking(CartCreateCommand.of(cardDraft)),
                ImmutableList.of(
                        AddPayment.of(payment),
                        AddLineItem.of(product.getId(), product.getMasterData().getCurrent().getMasterVariant().getId(), 1),
                        SetShippingAddress.of(Address.of(CountryCode.DE)),
                        SetBillingAddress.of(Address.of(CountryCode.DE).withLastName(buyerLastName))
                )));

        final String orderNumber = getRandomOrderNumber();

        ctpClient.executeBlocking(OrderFromCartCreateCommand.of(
                OrderFromCartDraft.of(cart, orderNumber, PaymentState.PENDING)));

        return orderNumber;
    }

    protected String getUnconfirmedVisaPseudoCardPan() {
        return getConfigurationParameter(TEST_DATA_VISA_CREDIT_CARD_NO_3_DS);
    }

    protected String getTestData3DsPassword() {
        return getConfigurationParameter(TEST_DATA_3_DS_PASSWORD);
    }

    protected String getVerifiedVisaPseudoCardPan() {
        return getConfigurationParameter(TEST_DATA_VISA_CREDIT_CARD_3_DS);
    }

    protected String getTestDataSwBankTransferIban() {
        return getConfigurationParameter(TEST_DATA_SW_BANK_TRANSFER_IBAN);
    }

    protected String getTestDataSwBankTransferBic() {
        return getConfigurationParameter(TEST_DATA_SW_BANK_TRANSFER_BIC);
    }


    protected String getRandomOrderNumber() {
        return String.valueOf(randomSource.nextInt() + System.currentTimeMillis());
    }

    protected BlockingSphereClient ctpClient() {
        return ctpClient;
    }

    /**
     * Gets the latest version of the payment via its legible name - a name that exits only in this test context.
     * @param paymentName legible name of the payment
     * @return the payment fetched from the commercetools client, can be null!
     * @see #fetchPaymentById(String)
     */
    protected Payment fetchPaymentByLegibleName(final String paymentName) {
        return fetchPaymentById(payments.get(paymentName));
    }

    /**
     * Gets the latest version of the payment via its ID.
     * @param paymentId unique ID of the payment
     * @return the payment fetched from the commercetools client, can be null!
     * @see #fetchPaymentByLegibleName(String)
     */
    protected Payment fetchPaymentById(final String paymentId) {
        return ctpClient.executeBlocking(PaymentByIdGet.of(
                Preconditions.checkNotNull(paymentId, "paymentId must not be null!")));
    }

    /**
     * Gets the ID of the type with name (aka key) {@code typeName}.
     * @param typeName the name (key) of a type
     * @return the type's ID
     * @throws ExecutionException if a checked exception was thrown while loading the value.
     */
    protected String typeIdFromTypeName(final String typeName) throws ExecutionException {
        return typeCache.get(typeName).getId();
    }

    public String getTransactionStateByPaymentId(final String paymentId, final String transactionId) {
        return getTransactionState(fetchPaymentById(paymentId), transactionId);
    }

    protected String getTransactionState(final Payment payment, final String transactionId) {
        final Optional<Transaction> transaction = payment.getTransactions()
                .stream()
                .filter(i -> transactionId.equals(i.getId()))
                .findFirst();

        final TransactionState transactionState = transaction.isPresent()? transaction.get().getState() : null;
        return transactionState != null? transactionState.toSphereName() : "<UNKNOWN>";
    }

    public String getIdOfLastTransactionByPaymentId(final String paymentId) {
        return getIdOfLastTransaction(fetchPaymentById(paymentId));
    }

    protected String getIdOfLastTransaction(final Payment payment) {
        Preconditions.checkNotNull(payment,
                "payment is null. This could be due to a restarting service instance which has not finished cleaning the platform from custom types, payments, orders and carts!");
        return Iterables.getLast(payment.getTransactions()).getId();
    }

    protected String getIdOfFirstTransaction(final Payment payment) {
        return Objects.requireNonNull(payment,
                "payment is null. This could be due to a restarting service instance which has not finished cleaning the platform from custom types, payments, orders and carts!")
                .getTransactions().get(0).getId();
    }

    protected void registerPaymentWithLegibleName(final String paymentName, final Payment payment) {
        Preconditions.checkState(!payments.containsKey(paymentName),
                String.format("Legible payment name '%s' already in use for ID '%s'.",
                        paymentName, payments.get(paymentName)));

        Preconditions.checkState(!payments.containsValue(payment.getId()),
                String.format("Payment with ID '%s' already known as '%s' instead of '%s'",
                        payment.getId(), payments.inverse().get(payment.getId()), paymentName));

        payments.put(paymentName, payment.getId());
    }

    protected HttpResponse requestToHandlePaymentByLegibleName(final String paymentName) throws IOException {
        Preconditions.checkState(payments.containsKey(paymentName),
                String.format("Legible payment name '%s' not mapped to any payment ID.", paymentName));

        return Request.Get(getHandlePaymentUrl(payments.get(paymentName)))
                .connectTimeout(INTEGRATION_SERVICE_REQUEST_TIMEOUT)
                .execute()
                .returnResponse();
    }

    protected String getIdForLegibleName(final String paymentName) {
        return payments.get(paymentName);
    }


    protected String getInteractionRequestCount(final Payment payment,
                                                final String transactionId,
                                                final String requestType) throws ExecutionException {
        final String interactionTypeId = typeIdFromTypeName(CustomTypeBuilder.PAYONE_INTERACTION_REQUEST);
        return Long.toString(payment.getInterfaceInteractions().stream()
                .filter(i -> interactionTypeId.equals(i.getType().getId()))
                .filter(i -> transactionId.equals(i.getFieldAsString(CustomFieldKeys.TRANSACTION_ID_FIELD)))
                .filter(i -> {
                    final String requestField = i.getFieldAsString(CustomFieldKeys.REQUEST_FIELD);
                    return (requestField != null) && requestField.contains("request=" + requestType);
                })
                .count());
    }

    protected String getInteractionRequestCountOverAllTransactions(final Payment payment,
                                                                   final String requestType) throws ExecutionException {
        final String interactionTypeId = typeIdFromTypeName(CustomTypeBuilder.PAYONE_INTERACTION_REQUEST);
        return Long.toString(payment.getInterfaceInteractions().stream()
                .filter(i -> interactionTypeId.equals(i.getType().getId()))
                .filter(i -> {
                    final String requestField = i.getFieldAsString(CustomFieldKeys.REQUEST_FIELD);
                    return (requestField != null) && requestField.contains("request=" + requestType);
                })
                .count());
    }

    protected Optional<CustomFields> getInteractionRedirect(final Payment payment,
                                            final String transactionId) throws ExecutionException {
        final String interactionTypeId = typeIdFromTypeName(CustomTypeBuilder.PAYONE_INTERACTION_REDIRECT);
        return payment.getInterfaceInteractions().stream()
                .filter(i -> interactionTypeId.equals(i.getType().getId()))
                .filter(i -> transactionId.equals(i.getFieldAsString(CustomFieldKeys.TRANSACTION_ID_FIELD)))
                .filter(i -> {
                    final String redirectField = i.getFieldAsString(CustomFieldKeys.REDIRECT_URL_FIELD);
                    return redirectField != null && !redirectField.isEmpty();
                })
                .findFirst();

    }

    protected long countPaymentsWithNotificationOfAction(final ImmutableList<String> paymentNames, final String txaction) throws ExecutionException {
        final List<ExecutionException> exceptions = Lists.newArrayList();
        final long result = paymentNames.stream().mapToLong(paymentName -> {
            final Payment payment = fetchPaymentByLegibleName(paymentName);
            try {
                return getTotalNotificationCountOfAction(payment, txaction);
            } catch (final ExecutionException e) {
                LOG.error("Exception: %s", e);
                exceptions.add(e);
                return 0L;
            }
        }).filter(notifications -> notifications > 0L).count();

        if (!exceptions.isEmpty()) {
            throw exceptions.get(0);
        }

        return result;
    }

    protected long getTotalNotificationCountOfAction(final Payment payment, final String txaction) throws ExecutionException {
        final String interactionTypeId = typeIdFromTypeName(CustomTypeBuilder.PAYONE_INTERACTION_NOTIFICATION);

        return payment.getInterfaceInteractions().stream()
                .filter(i -> interactionTypeId.equals(i.getType().getId()))
                .filter(i -> txaction.equals(i.getFieldAsString(CustomFieldKeys.TX_ACTION_FIELD)))
                .filter(i -> {
                    final String notificationField = i.getFieldAsString(CustomFieldKeys.NOTIFICATION_FIELD);
                    return (notificationField != null) &&
                            (notificationField.toLowerCase().contains("transactionstatus=completed")
                                    || notificationField.toLowerCase().contains("transactionstatus=null"));
                })
                .count();
    }

    public boolean customStringFieldIsNull(final String paymentName, final String fieldName) {
        final Payment payment = fetchPaymentByLegibleName(paymentName);
        return payment.getCustom().getFieldAsString(fieldName) == null;
    }
}

