package jp.ne.paypay.example;

import jp.ne.paypay.ApiClient;
import jp.ne.paypay.ApiException;
import jp.ne.paypay.Configuration;
import jp.ne.paypay.api.PaymentApi;
import jp.ne.paypay.api.WalletApi;
import jp.ne.paypay.model.AccountLinkQRCode;
import jp.ne.paypay.model.AuthorizationScope;
import jp.ne.paypay.model.CaptureObject;
import jp.ne.paypay.model.LinkQRCodeResponse;
import jp.ne.paypay.model.MerchantOrderItem;
import jp.ne.paypay.model.MoneyAmount;
import jp.ne.paypay.model.NotDataResponse;
import jp.ne.paypay.model.Payment;
import jp.ne.paypay.model.PaymentDetails;
import jp.ne.paypay.model.PaymentState;
import jp.ne.paypay.model.PaymentStateRevert;
import jp.ne.paypay.model.QRCode;
import jp.ne.paypay.model.QRCodeDetails;
import jp.ne.paypay.model.Refund;
import jp.ne.paypay.model.RefundDetails;
import jp.ne.paypay.model.RevertAuthResponse;
import jp.ne.paypay.model.WalletBalance;
import org.apache.commons.lang3.RandomStringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PaymentApiExample {


  public static void main(String[] args) throws ApiException{
    ApiClient apiClient = new Configuration().getDefaultApiClient();
    apiClient.setProductionMode(false);
    apiClient.setApiKey("API_KEY");
    apiClient.setApiSecretKey("API_SECRET_KEY");
    apiClient.setAssumeMerchant("ASSUME_MERCHANT_ID");
    String userAuthorizationId = "USER_AUTHORIZATION_ID";

    PaymentApi paymentApi = new PaymentApi(apiClient);
    WalletApi walletApiInstance = new WalletApi(apiClient);

    createAccountLinkQrCode(paymentApi);
    preAuthCaptureFlow(walletApiInstance, paymentApi, userAuthorizationId);
    preAuthRevertAuthFlow(walletApiInstance, paymentApi, userAuthorizationId);
    directDebitFlow(walletApiInstance, paymentApi, userAuthorizationId,  false);
    //Continuous payment flow
    directDebitFlow(walletApiInstance, paymentApi, userAuthorizationId, true);
    appInvokeFlow(paymentApi, walletApiInstance, userAuthorizationId);
  }

  protected static Payment getPaymentObject(String merchantPaymentId, String userAuthorizationId, int amount) {
    Payment payment = new Payment();
    payment.setAmount(new MoneyAmount().amount(amount).currency(MoneyAmount.CurrencyEnum.JPY));
    payment.setMerchantPaymentId(merchantPaymentId);
    payment.setUserAuthorizationId(userAuthorizationId);
    payment.setStoreId(RandomStringUtils.randomAlphabetic(8));
    payment.setTerminalId(RandomStringUtils.randomAlphanumeric(8));
    payment.setOrderReceiptNumber(RandomStringUtils.randomAlphanumeric(8));
    payment.setOrderDescription("Payment for Order ID:" + UUID.randomUUID().toString());
    MerchantOrderItem merchantOrderItem =
            new MerchantOrderItem()
                    .category("Dessert").name("Red Velvet Cake")
                    .productId(RandomStringUtils.randomAlphanumeric(8)).quantity(1)
                    .unitPrice(new MoneyAmount().amount(10).currency(MoneyAmount.CurrencyEnum.JPY));
    List<MerchantOrderItem> merchantOrderItems = new ArrayList<>();
    merchantOrderItems.add(merchantOrderItem);
    payment.setOrderItems(merchantOrderItems);
    return payment;
  }


  private static void createAccountLinkQrCode(final PaymentApi apiInstance){
    try{
      AccountLinkQRCode accountLinkQRCode = new AccountLinkQRCode();
      List<AuthorizationScope> scopes = new ArrayList<>();
      scopes.add(AuthorizationScope.DIRECT_DEBIT);
      scopes.add(AuthorizationScope.PENDING_PAYMENTS);
      scopes.add(AuthorizationScope.CONTINUOUS_PAYMENTS);
      scopes.add(AuthorizationScope.PREAUTH_CAPTURE_NATIVE);
      accountLinkQRCode.setScopes(scopes);
      accountLinkQRCode.setNonce(RandomStringUtils.randomAlphanumeric(8).toLowerCase());
      accountLinkQRCode.setDeviceId("device_id");
      accountLinkQRCode.setRedirectUrl("merchant.domain/test");
      accountLinkQRCode.setPhoneNumber("phone_number");
      accountLinkQRCode.setReferenceId("reference_id");
      accountLinkQRCode.setRedirectType(QRCode.RedirectTypeEnum.WEB_LINK);
      LinkQRCodeResponse response = apiInstance.createAccountLinkQRCode(accountLinkQRCode);
      System.out.println(response.getResultInfo().getCode());
      System.out.println(response.getData());

    }catch (ApiException e){
      e.printStackTrace();
      System.out.println(e.getResponseBody());
    }

  }

  private static void appInvokeFlow(final PaymentApi paymentApi, final WalletApi walletApiInstance,
                                    final String userAuthorizationId) {
    int amount =1;
    QRCodeDetails qrCodeDetails = createQRCode(paymentApi, amount);

    String merchantPaymentId  = qrCodeDetails!=null ?qrCodeDetails.getData().getMerchantPaymentId(): null;
    WalletBalance walletBalance = getWalletBalance(walletApiInstance, userAuthorizationId);
    if(merchantPaymentId != null && walletBalance != null && walletBalance.getData().isHasEnoughBalance()) {
      System.out.println("The QR Code can be used as a deeplink to invoke PayPay app and receive Payments. The user "
              + "can makes the payment using PayPay App");
      System.out.println("For this example, we will create payment using the API...");
      Payment payment = getPaymentObject(merchantPaymentId, userAuthorizationId, amount);
      PaymentDetails paymentDetails = createPayment(paymentApi, payment, false);
      if (paymentDetails != null) {
        System.out.println("Payment created successfully, Now calling the API to get payment details for payment " + "ID:"
                + merchantPaymentId);
        String refundId = UUID.randomUUID().toString();
        paymentDetails = getPaymentDetails(paymentApi, merchantPaymentId);
        if (paymentDetails != null) {
          System.out.println("Creating Refund for the payment:" + paymentDetails.getData().getPaymentId());
          createRefund(paymentApi, paymentDetails.getData().getPaymentId(), refundId);
          System.out.println("Get refund details:" + refundId);
          getRefundDetails(paymentApi, refundId);
          System.out.println("Finally cancel the payment");
          cancelPayment(paymentApi, merchantPaymentId);
        }
      }
    }
    if(qrCodeDetails != null) {
      System.out.println("Delete the QR Code: "+qrCodeDetails.getData().getCodeId());
      deleteQrCode(paymentApi, qrCodeDetails.getData().getCodeId());
    }
  }

  private static void directDebitFlow(WalletApi walletApiInstance, PaymentApi paymentApi, String userAuthorizationId, boolean continuousPayment){

    String merchantPaymentId  = UUID.randomUUID().toString();
    WalletBalance walletBalance = getWalletBalance(walletApiInstance, userAuthorizationId);
    if(walletBalance != null && walletBalance.getData().isHasEnoughBalance()){
      System.out.println("There is enough balance, now creating payment...");
      PaymentDetails paymentDetails;
      Payment payment = getPaymentObject(merchantPaymentId, userAuthorizationId, 1);
      if(continuousPayment){
         payment.setAmount(new MoneyAmount().amount(2).currency(MoneyAmount.CurrencyEnum.JPY));
         paymentDetails = createContinuousPayment(paymentApi, payment);
      }else{
        paymentDetails = createPayment(paymentApi, payment, false);
      }
      if (paymentDetails != null) {
        System.out.println("Payment created successfully, Now calling the API to get payment details for payment "
                + "ID:"+merchantPaymentId);
        String refundId = UUID.randomUUID().toString();
        paymentDetails = getPaymentDetails(paymentApi, merchantPaymentId);
          System.out.println("Creating Refund for the payment:" + paymentDetails.getData().getPaymentId());
          createRefund(paymentApi, paymentDetails.getData().getPaymentId(), refundId);
          System.out.println("Get refund details:"+refundId);
          getRefundDetails(paymentApi, refundId);
          System.out.println("Finally cancel the payment");
          cancelPayment(paymentApi, merchantPaymentId);
      }
    }

  }

  private static void preAuthCaptureFlow(WalletApi walletApiInstance, PaymentApi paymentApi, String userAuthorizationId){

    String merchantPaymentId  = UUID.randomUUID().toString();
    System.out.println("Checking wallet balance...");
    int amount =1;
    WalletBalance walletBalance = getWalletBalance(walletApiInstance, userAuthorizationId);
    if(walletBalance != null && walletBalance.getData().isHasEnoughBalance()){
      System.out.println("There is enough balance, now creating payment...");
      Payment payment = getPaymentObject(merchantPaymentId, userAuthorizationId, amount);
      PaymentDetails paymentDetails = createPayment(paymentApi, payment, true);
      if (paymentDetails != null) {
        System.out.println("Now capture the payment authorization for a payment, Don't capture if you need to check cancel payment");
        capturePayment(paymentApi, merchantPaymentId, amount);
        System.out.println("Payment created successfully, Now calling the API to get payment details for payment "
                + "ID:"+merchantPaymentId);
        paymentDetails = getPaymentDetails(paymentApi, merchantPaymentId);
        if(paymentDetails != null) {
          if(paymentDetails.getData().getStatus() == PaymentState.StatusEnum.COMPLETED){
            String refundId = UUID.randomUUID().toString();
            System.out.println("Creating Refund for the payment:" + paymentDetails.getData().getPaymentId());
            createRefund(paymentApi, paymentDetails.getData().getPaymentId(), refundId);
            System.out.println("Get refund details:"+refundId);
            getRefundDetails(paymentApi, refundId);
          }else{
            System.out.println("Finally cancel the payment");
            cancelPayment(paymentApi, merchantPaymentId);
          }
        }
      }
    }

  }

  private static void preAuthRevertAuthFlow(WalletApi walletApiInstance, PaymentApi paymentApi, String userAuthorizationId){

    String merchantPaymentId  = UUID.randomUUID().toString();
    System.out.println("Checking wallet balance...");
    WalletBalance walletBalance = getWalletBalance(walletApiInstance, userAuthorizationId);
    if(walletBalance != null && walletBalance.getData().isHasEnoughBalance()){
      System.out.println("There is enough balance, now creating payment...");
      Payment payment = getPaymentObject(merchantPaymentId, userAuthorizationId, 1);
      PaymentDetails paymentDetails = createPayment(paymentApi, payment, true);
      if (paymentDetails != null) {
        System.out.println("Payment Authorized successfully, Now calling the API to get payment details for payment "
                + "ID:"+merchantPaymentId);
        paymentDetails = getPaymentDetails(paymentApi, merchantPaymentId);
        if(paymentDetails != null) {
          System.out.println("Reverting payment with Payment ID"+paymentDetails.getData().getPaymentId());
          paymentRevertAuth(paymentApi, paymentDetails.getData().getPaymentId());
          System.out.println("Check the payment details, the status should be CANCELED");
          getPaymentDetails(paymentApi, merchantPaymentId);
        }
      }
    }

  }
  private static WalletBalance getWalletBalance(final WalletApi apiInstance, String userAuthorizationId) {
    WalletBalance result = null;
    try {
      result = apiInstance.checkWalletBalance(userAuthorizationId, 1, "JPY", null);
      System.out.println("\nAPI RESPONSE\n------------------\n");
      System.out.println(result);
    } catch (ApiException e) {

      e.printStackTrace();
      System.out.println("--------------------");
      System.out.println(e.getResponseBody());
    }
    return result;
  }

  private static PaymentDetails createPayment(final PaymentApi apiInstance, Payment payment, boolean authorization) {
    PaymentDetails result = null;
    try {
      if(authorization){
        result = apiInstance.createPaymentAuthorization(payment, "false");
      }else{
        result = apiInstance.createPayment(payment, "false");
      }

      System.out.println("\nAPI RESPONSE\n------------------\n");
      System.out.println(result);
    } catch (ApiException e) {
      System.err.println(e.getResponseBody());
    }
    return result;
  }

  private static PaymentDetails createContinuousPayment(final PaymentApi apiInstance, Payment payment) {
    PaymentDetails result = null;
    try {
      result = apiInstance.createContinuousPayment(payment);
      System.out.println("\nAPI RESPONSE\n------------------\n");
      System.out.println(result.getResultInfo().getCode());
    } catch (ApiException e) {
      System.err.println(e.getResponseBody());
    }
    return result;
  }

  private static void capturePayment(final PaymentApi apiInstance, String merchantPaymentId, int amount){
    try {
      CaptureObject captureObject = new CaptureObject();
      captureObject.setMerchantCaptureId(UUID.randomUUID().toString());
      captureObject.setMerchantPaymentId(merchantPaymentId);
      captureObject.setAmount(new MoneyAmount().amount(amount).currency(MoneyAmount.CurrencyEnum.JPY));
      captureObject.setOrderDescription("new Order");
      PaymentDetails paymentDetails = apiInstance.capturePaymentAuth(captureObject);
      System.out.println(paymentDetails);
    } catch (ApiException e) {
      e.printStackTrace();
      System.out.println(e.getResponseBody());
    }

  }
  private static void createRefund(final PaymentApi apiInstance, String paymentId, String refundId) {

    try {
      Refund refund = getRefundObject(paymentId, refundId);
      RefundDetails result = apiInstance.refundPayment(refund);
      System.out.println("\nAPI RESPONSE\n------------------\n");
      System.out.println(result);
    } catch (ApiException e) {
      System.err.println(e.getResponseBody());
    }
  }

  protected static Refund getRefundObject(String paymentId, String refundId) {
    Refund refund = new Refund();
    refund.setAmount(new MoneyAmount().amount(1).currency(MoneyAmount.CurrencyEnum.JPY));
    refund.setMerchantRefundId(refundId);
    refund.setPaymentId(paymentId);
    refund.setReason("Testing");
    return refund;
  }

  private static QRCodeDetails createQRCode(final PaymentApi apiInstance, int amount) {
    QRCodeDetails result = null;
    try {
      QRCode qrCode = new QRCode();
      qrCode.setAmount(new MoneyAmount().amount(amount).currency(MoneyAmount.CurrencyEnum.JPY));
      qrCode.setMerchantPaymentId(UUID.randomUUID().toString());
      qrCode.setCodeType("ORDER_QR");
      qrCode.setStoreId(RandomStringUtils.randomAlphabetic(8));
      qrCode.setStoreInfo("Just Bake");
      qrCode.setTerminalId(RandomStringUtils.randomAlphanumeric(8));
      qrCode.setRedirectUrl("https://paypay.ne.jp/");
      qrCode.setRedirectType(QRCode.RedirectTypeEnum.WEB_LINK);//For Deep Link, RedirectTypeEnum.APP_DEEP_LINK
      qrCode.setOrderDescription("Payment for Order ID:"+UUID.randomUUID().toString());
      result = apiInstance.createQRCode(qrCode);
      System.out.println("\nAPI RESPONSE\n------------------\n");
      System.out.println(result);
    } catch (ApiException e) {
      System.out.println(e.getResponseHeaders().toString());
      System.err.println(e.getResponseBody());
    }
    return result;
  }

  protected static void getRefundDetails(final PaymentApi apiInstance, final String merchantRefundId) {
    try {
      RefundDetails result = apiInstance.getRefundDetails(merchantRefundId);
      System.out.println("\nAPI RESPONSE\n------------------\n");
      System.out.println(result);
    } catch (ApiException e) {
      System.err.println(e.getResponseBody());
    }
  }

  private static PaymentDetails getPaymentDetails(final PaymentApi apiInstance, final String merchantPaymentId) {
    PaymentDetails result = null;
    try {
      result = apiInstance.getPaymentDetails(merchantPaymentId);
      System.out.println("\nAPI RESPONSE\n------------------\n");
      System.out.println(result);
    } catch (ApiException e) {
      System.err.println(e.getResponseBody());
    }
    return result;
  }

  private static void deleteQrCode(final PaymentApi apiInstance, final String qrCode) {
    try {
      NotDataResponse result = apiInstance.deleteQRCode(qrCode);
      System.out.println("\nAPI RESPONSE\n------------------\n");
      System.out.println(result);
    } catch (ApiException e) {
      System.err.println(e.getResponseBody());
    }
  }

  private static void cancelPayment(final PaymentApi apiInstance, final String merchantPaymentId) {
    try {
      NotDataResponse result = apiInstance.cancelPayment(merchantPaymentId);
      System.out.println("\n\nAPI RESPONSE\n------------------\n");
      System.out.println(result);
    } catch (ApiException e) {
      System.err.println("Exception when calling PaymentApi#cancelPayment" + e.getMessage());
      System.err.println(e.getResponseBody());
    }
  }

  private static void paymentRevertAuth(final PaymentApi apiInstance, String paymentId) {

    try {
      PaymentStateRevert payment = new PaymentStateRevert();
      payment.setPaymentId(paymentId);
      payment.setMerchantRevertId(UUID.randomUUID().toString());
      RevertAuthResponse result = apiInstance.revertAuth(payment);
      System.out.println("\nAPI RESPONSE\n------------------\n");
      System.out.println(result);
    } catch (ApiException e) {
      System.err.println(e.getResponseBody());
    }
  }
}

